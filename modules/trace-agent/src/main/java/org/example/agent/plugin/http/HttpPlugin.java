package org.example.agent.plugin.http;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.TxIdHolder;
import org.example.agent.core.SpanIdHolder;
import org.example.agent.core.TraceRuntime;
import org.example.agent.plugin.BaseAdvice;
import org.example.agent.plugin.ReflectionUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class HttpPlugin implements TracerPlugin {
    private static final List<String> DEFAULT_TARGET_PREFIXES = Arrays.asList(
        "org/springframework/web/servlet/",
        "org/springframework/web/client/",
        "org/springframework/http/client/support/",
        "org/springframework/web/reactive/function/client/",
        "org/springframework/web/reactive/DispatcherHandler"
    );

    @Override public String pluginId() { return "http"; }
    @Override public boolean requiresBootstrapSearch() { return true; }

    @Override
    public List<String> targetClassPrefixes() {
        return AgentConfig.getPluginTargetPrefixes(pluginId(), DEFAULT_TARGET_PREFIXES);
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(
            new DispatcherServletTransformer(),
            new DispatcherHandlerTransformer(),
            new RestTemplateTransformer(),
            new WebClientTransformer(),
            new HttpServletRequestTransformer()
        );
    }

    static class HttpServletRequestTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            // Check for common servlet request implementations
            if (!normalized.contains("Request") && !normalized.contains("request")) return null;
            
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, loader);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("startAsync".equals(name)) {
                            return new StartAsyncAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class StartAsyncAdvice extends BaseAdvice {
        protected StartAsyncAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                mv.visitVarInsn(ALOAD, 0); // this (request)
                // We don't have method/path here easily, but we can restore them from attributes in TraceRuntime
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "registerAsyncListenerFromRequest", "(Ljava/lang/Object;)V", false);
            }
        }
    }

    static class SafeClassWriter extends ClassWriter {
        private final ClassLoader loader;
        SafeClassWriter(ClassReader cr, ClassLoader loader) {
            super(cr, ClassWriter.COMPUTE_FRAMES);
            this.loader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        }
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> d = Class.forName(type2.replace('/', '.'), false, loader);
                if (c.isAssignableFrom(d)) return type1;
                if (d.isAssignableFrom(c)) return type2;
                if (c.isInterface() || d.isInterface()) return "java/lang/Object";
                do { c = c.getSuperclass(); } while (!c.isAssignableFrom(d));
                return c.getName().replace('.', '/');
            } catch (Throwable e) { return "java/lang/Object"; }
        }
    }

    static class DispatcherServletTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            if (!AgentConfig.getHttpDispatcherClass().equals(normalized)
                && !(normalized.startsWith("org/springframework/web/servlet/") && normalized.endsWith("/DispatcherServlet"))) {
                return null;
            }
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, loader);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("doDispatch".equals(name) && descriptor.contains("HttpServletRequest")) {
                            return new DispatcherServletAdvice(mv, access, name, descriptor, AgentConfig.getServletPackage().startsWith("jakarta") || descriptor.contains("jakarta"));
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class DispatcherServletAdvice extends BaseAdvice {
        private final boolean isJakarta;
        private boolean ignored = false;
        private int isTrackedId = -1;

        protected DispatcherServletAdvice(MethodVisitor mv, int access, String name, String descriptor, boolean isJakarta) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.isJakarta = isJakarta;
        }

        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("TraceIgnore")) ignored = true;
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        protected void onMethodEnter() {
            if (ignored) return;
            captureStartTime();
            String pkg = isJakarta ? "jakarta/servlet/http/HttpServletRequest" : "javax/servlet/http/HttpServletRequest";
            
            isTrackedId = newLocal(Type.BOOLEAN_TYPE);
            mv.visitInsn(ICONST_1); // Default to tracked
            mv.visitVarInsn(ISTORE, isTrackedId);

            Label primaryStart = new Label();
            Label end = new Label();

            // Check if this is a secondary dispatch (ASYNC resume or ERROR)
            mv.visitVarInsn(ALOAD, 1); 
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "isSecondaryDispatch", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, primaryStart);

            // Secondary Dispatch Path: Restore txId/spanId from attributes so we can record HTTP_IN_END later
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "restoreContext", "(Ljava/lang/Object;)V", false);
            
            // Check if we actually restored anything. If not, don't track this orphaned resume.
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "get", "()Ljava/lang/String;", false);
            Label hasCtx = new Label();
            mv.visitJumpInsn(IFNONNULL, hasCtx);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, isTrackedId);
            mv.visitLabel(hasCtx);
            mv.visitJumpInsn(GOTO, end);

            // Primary Dispatch Path: Start new trace
            mv.visitLabel(primaryStart);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/config/AgentConfig", "getForceSampleHeader", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", true);
            mv.visitLdcInsn("true");
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equalsIgnoreCase", "(Ljava/lang/String;)Z", false);
            int forceTraceId = newLocal(Type.BOOLEAN_TYPE);
            mv.visitVarInsn(ISTORE, forceTraceId);

            mv.visitVarInsn(ALOAD, 1); // request
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getMethod", "()Ljava/lang/String;", true);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getRequestURI", "()Ljava/lang/String;", true);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/config/AgentConfig", "getHeaderKey", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", true);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn("X-Span-Id");
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", true);
            mv.visitVarInsn(ILOAD, forceTraceId);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpInStart", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);

            mv.visitLabel(end);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (ignored || isTrackedId == -1) return;
            Label skip = new Label();
            mv.visitVarInsn(ILOAD, isTrackedId);
            mv.visitJumpInsn(IFEQ, skip);

            String requestPkg  = isJakarta ? "jakarta/servlet/http/HttpServletRequest"  : "javax/servlet/http/HttpServletRequest";
            String responsePkg = isJakarta ? "jakarta/servlet/http/HttpServletResponse" : "javax/servlet/http/HttpServletResponse";
            
            if (opcode == ATHROW) {
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getMethod", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getRequestURI", "()Ljava/lang/String;", true);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpInError", "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V", false);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/http/HttpPlugin", "isAsyncStarted", "(Ljava/lang/Object;)Z", false);
                Label asyncActive = new Label();
                mv.visitJumpInsn(IFNE, asyncActive);

                // Normal Completion or Resume Completion (isAsyncStarted is false on resume exit)
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getMethod", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getRequestURI", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitMethodInsn(INVOKEINTERFACE, responsePkg, "getStatus", "()I", true);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpInEnd", "(Ljava/lang/String;Ljava/lang/String;IJ)V", false);
                mv.visitJumpInsn(GOTO, skip);

                mv.visitLabel(asyncActive);
                // Async processing is active: Register listener to record END later
                mv.visitVarInsn(ALOAD, 1); // request
                mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getMethod", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getRequestURI", "()Ljava/lang/String;", true);
                mv.visitVarInsn(LLOAD, startTimeId);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "registerAsyncListener", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;J)V", false);

                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "clear", "()V", false);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/SpanIdHolder", "clear", "()V", false);
            }
            mv.visitLabel(skip);
        }
    }

    static class RestTemplateTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            if (!normalized.startsWith("org/springframework/web/client/") && !normalized.startsWith("org/springframework/http/client/support/")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("doExecute".equals(name) && descriptor.startsWith("(Ljava/net/URI;")) return new RestTemplateAdvice(mv, access, name, descriptor);
                        if ("createRequest".equals(name) && descriptor.contains("ClientHttpRequest")) return new RestTemplateCreateRequestAdvice(mv, access, name, descriptor);
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class RestTemplateCreateRequestAdvice extends BaseAdvice {
        protected RestTemplateCreateRequestAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ARETURN) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "get", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/SpanIdHolder", "get", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/http/HttpPlugin", "injectHeadersToRequest", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false);
            }
        }
    }

    static class RestTemplateAdvice extends BaseAdvice {
        private final int httpMethodIdx;
        protected RestTemplateAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.httpMethodIdx = Type.getArgumentTypes(descriptor).length == 4 ? 2 : 3;
        }
        @Override protected void onMethodEnter() { captureStartTime(); }
        @Override
        protected void onMethodExit(int opcode) {
            String m = "org/springframework/http/HttpMethod";
            String r = "org/example/agent/core/TraceRuntime";
            if (opcode == ATHROW) {
                mv.visitInsn(DUP); mv.visitVarInsn(ALOAD, httpMethodIdx);
                mv.visitMethodInsn(INVOKEVIRTUAL, m, "name", "()Ljava/lang/String;", false);
                mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "toString", "()Ljava/lang/String;", false);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, r, "onHttpOutError", "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V", false);
            } else {
                mv.visitVarInsn(ALOAD, httpMethodIdx); mv.visitMethodInsn(INVOKEVIRTUAL, m, "name", "()Ljava/lang/String;", false);
                mv.visitVarInsn(ALOAD, 1); mv.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "toString", "()Ljava/lang/String;", false);
                mv.visitIntInsn(SIPUSH, 200); calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, r, "onHttpOut", "(Ljava/lang/String;Ljava/lang/String;IJ)V", false);
            }
        }
    }

    static class DispatcherHandlerTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"org/springframework/web/reactive/DispatcherHandler".equals(className == null ? "" : className.replace('.', '/'))) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, loader);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("handle".equals(name) && descriptor.contains("ServerWebExchange")) return new DispatcherHandlerAdvice(mv, access, name, descriptor);
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class DispatcherHandlerAdvice extends BaseAdvice {
        protected DispatcherHandlerAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }
        @Override protected void onMethodEnter() {
            captureStartTime(); mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onWebFluxHandleStart", "(Ljava/lang/Object;)V", false);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onWebFluxHandleSyncError", "()V", false);
            else if (opcode == ARETURN) {
                mv.visitVarInsn(ALOAD, 1); mv.visitVarInsn(LLOAD, startTimeId);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "wrapWebFluxHandle", "(Ljava/lang/Object;Ljava/lang/Object;J)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "reactor/core/publisher/Mono");
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "clear", "()V", false);
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/SpanIdHolder", "clear", "()V", false);
            }
        }
    }

    static class WebClientTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className == null ? "" : className.replace('.', '/');
            if (!normalized.endsWith("DefaultExchangeFunction") && !normalized.contains("ExchangeFunctions")) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("exchange".equals(name) && descriptor.contains("ClientRequest")) return new WebClientAdvice(mv, access, name, descriptor);
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class WebClientAdvice extends BaseAdvice {
        private final String returnType;
        protected WebClientAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.returnType = Type.getReturnType(descriptor).getInternalName();
        }
        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1); // ClientRequest
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/SpanIdHolder", "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/http/HttpPlugin", "rebuildClientRequestWithHeaders", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "org/springframework/web/reactive/function/client/ClientRequest");
            mv.visitVarInsn(ASTORE, 1);
        }
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ARETURN) return;
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/springframework/web/reactive/function/client/ClientRequest", "method", "()Lorg/springframework/http/HttpMethod;", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/springframework/http/HttpMethod", "name", "()Ljava/lang/String;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, "org/springframework/web/reactive/function/client/ClientRequest", "url", "()Ljava/net/URI;", true);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "toString", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "wrapWebClientExchange", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, returnType);
        }
    }

    public static void injectHeadersToRequest(Object request, String txId, String spanId) {
        if (txId == null || request == null) return;
        ReflectionUtils.invokeMethod(request, "getHeaders").ifPresent(headers -> {
            ReflectionUtils.invokeMethod(headers, "add", AgentConfig.getHeaderKey(), txId);
            if (spanId != null) ReflectionUtils.invokeMethod(headers, "add", "X-Span-Id", spanId);
        });
    }

    public static Object rebuildClientRequestWithHeaders(Object request, String txId, String spanId) {
        if (txId == null || request == null) return request;
        try {
            AgentLogger.debug("[HTTP] WebClient: Header Propagation (txId=" + txId + ")");
            ClassLoader cl = request.getClass().getClassLoader();
            Class<?> crClass = Class.forName("org.springframework.web.reactive.function.client.ClientRequest", false, cl);
            java.lang.reflect.Method from = crClass.getMethod("from", crClass);
            Object builder = from.invoke(null, request);
            Class<?> builderClass = Class.forName("org.springframework.web.reactive.function.client.ClientRequest$Builder", false, cl);
            java.lang.reflect.Method headerMethod = builderClass.getMethod("header", String.class, String[].class);
            headerMethod.invoke(builder, AgentConfig.getHeaderKey(), new String[]{txId});
            if (spanId != null) headerMethod.invoke(builder, "X-Span-Id", new String[]{spanId});
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) { return request; }
    }

    public static boolean isAsyncStarted(Object request) {
        if (request == null) return false;
        try {
            return Boolean.TRUE.equals(TraceRuntime.invokeMethodSimple(request, "isAsyncStarted"));
        } catch (Throwable t) { return false; }
    }
}
