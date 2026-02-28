package org.example.agent.plugin.http;

import org.example.agent.TracerPlugin;
import org.example.agent.config.AgentConfig;
import org.example.agent.core.AgentLogger;
import org.example.agent.core.TxIdHolder;
import org.example.agent.plugin.BaseAdvice;
import org.example.agent.plugin.ReflectionUtils;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

public class HttpPlugin implements TracerPlugin {

    @Override public String pluginId() { return "http"; }

    @Override
    public List<String> targetClassPrefixes() {
        return Arrays.asList(
            AgentConfig.getHttpDispatcherClass(),
            AgentConfig.getHttpRestTemplateClass(),
            AgentConfig.getHttpAccessorClass(),
            AgentConfig.getHttpWebClientClassPrefix()
        );
    }

    @Override
    public List<ClassFileTransformer> transformers() {
        return Arrays.asList(
            new DispatcherServletTransformer(),
            new RestTemplateTransformer(),
            new WebClientTransformer()
        );
    }

    /**
     * ClassWriter that uses the application's classloader to resolve type hierarchies.
     * Required when COMPUTE_FRAMES is used: ASM calls getCommonSuperClass() for every
     * two types that merge in the control flow, and the default implementation loads
     * classes with the system classloader which may not see application types.
     * Fallback to "java/lang/Object" on any error keeps instrumentation safe.
     */
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
            } catch (Throwable e) {
                return "java/lang/Object";
            }
        }
    }

    static class DispatcherServletTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!AgentConfig.getHttpDispatcherClass().equals(className.replace('.', '/'))) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                // COMPUTE_FRAMES is required here: the DispatcherServletAdvice injects
                // conditional branches (IFEQ labels) which shift bytecode offsets and
                // invalidate the existing stack map frames. COMPUTE_MAXS only recomputes
                // max stack/locals and does NOT recompute frames, causing VerifyError.
                ClassWriter writer = new SafeClassWriter(reader, loader);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("doDispatch".equals(name) && descriptor.contains("HttpServletRequest")) {
                            // Primary: use the resolved SpringVersionProfile servlet package.
                            // Cross-check against the actual descriptor as a safety net in case
                            // auto-detection ran on an unrelated classloader.
                            boolean profileIsJakarta = AgentConfig.getServletPackage().startsWith("jakarta");
                            boolean descriptorIsJakarta = descriptor.contains("jakarta");
                            boolean isJakarta = profileIsJakarta || descriptorIsJakarta;
                            return new DispatcherServletAdvice(mv, access, name, descriptor, isJakarta);
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
        // Local variable slot for "is this request being tracked?"
        // -1 = uninitialized (onMethodEnter not yet called or ignored).
        // Set in onMethodEnter via isErrorDispatch check; read in onMethodExit.
        private int isTrackedId = -1;

        protected DispatcherServletAdvice(MethodVisitor mv, int access, String name, String descriptor, boolean isJakarta) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.isJakarta = isJakarta;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("TraceIgnore")) {
                ignored = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        protected void onMethodEnter() {
            if (ignored) return;
            captureStartTime();
            String pkg = isJakarta ? "jakarta/servlet/http/HttpServletRequest" : "javax/servlet/http/HttpServletRequest";

            // isTracked = !isErrorDispatch(request)
            // isErrorDispatch detects Spring Boot's internal /error forward via request
            // attributes, so a real developer-defined /error endpoint is NOT filtered.
            isTrackedId = newLocal(Type.BOOLEAN_TYPE);
            int forceTraceId = newLocal(Type.BOOLEAN_TYPE);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                "isErrorDispatch", "(Ljava/lang/Object;)Z", false);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IXOR);                    // negate: 1 ^ isErrorDispatch
            mv.visitVarInsn(ISTORE, isTrackedId);

            Label skip = new Label();
            mv.visitVarInsn(ILOAD, isTrackedId);
            mv.visitJumpInsn(IFEQ, skip);          // if not tracked, skip onHttpInStart

            // forceTrace = "true".equalsIgnoreCase(request.getHeader(AgentConfig.getForceSampleHeader()))
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/config/AgentConfig",
                "getForceSampleHeader", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", true);
            mv.visitLdcInsn("true");
            mv.visitInsn(SWAP);  // stack: [..., "true", headerVal] — "true".equalsIgnoreCase(headerVal)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String",
                "equalsIgnoreCase", "(Ljava/lang/String;)Z", false);
            mv.visitVarInsn(ISTORE, forceTraceId);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getMethod", "()Ljava/lang/String;", true);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getRequestURI", "()Ljava/lang/String;", true);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/config/AgentConfig", "getHeaderKey", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKEINTERFACE, pkg, "getHeader", "(Ljava/lang/String;)Ljava/lang/String;", true);
            mv.visitVarInsn(ILOAD, forceTraceId);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime",
                "onHttpInStart", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);

            mv.visitLabel(skip);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (ignored || isTrackedId == -1) return;

            // Skip trace-end injection for un-tracked requests (e.g. error dispatch forwards)
            Label skip = new Label();
            mv.visitVarInsn(ILOAD, isTrackedId);
            mv.visitJumpInsn(IFEQ, skip);

            String requestPkg  = isJakarta ? "jakarta/servlet/http/HttpServletRequest"  : "javax/servlet/http/HttpServletRequest";
            String responsePkg = isJakarta ? "jakarta/servlet/http/HttpServletResponse" : "javax/servlet/http/HttpServletResponse";
            if (opcode == ATHROW) {
                // Stack: [..., throwable] — DUP so the original throwable survives for re-throw
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getMethod", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getRequestURI", "()Ljava/lang/String;", true);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpInError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V", false);
            } else {
                // Normal return: read the actual HTTP response status from the response object (arg index 2)
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getMethod", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, requestPkg, "getRequestURI", "()Ljava/lang/String;", true);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitMethodInsn(INVOKEINTERFACE, responsePkg, "getStatus", "()I", true);
                calculateDurationAndPush();
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpInEnd",
                    "(Ljava/lang/String;Ljava/lang/String;IJ)V", false);
            }

            mv.visitLabel(skip);
        }
    }

    static class RestTemplateTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            String normalized = className.replace('.', '/');
            // Also match HttpAccessor — createRequest() is defined there, not in RestTemplate itself.
            if (!AgentConfig.getHttpRestTemplateClass().equals(normalized)
                    && !normalized.contains("DummyRestTemplate")
                    && !AgentConfig.getHttpAccessorClass().equals(normalized)) return null;
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
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/http/HttpPlugin", "injectHeaderToRequest", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            }
        }
    }

    static class RestTemplateAdvice extends BaseAdvice {
        private final int httpMethodIdx;
        private final int callbackIdx;
        private boolean ignored = false;

        protected RestTemplateAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            Type[] args = Type.getArgumentTypes(descriptor);
            // Spring 5 / 6.0 → SPRING_5 / SPRING_6_0: doExecute(URI, HttpMethod, RequestCallback, ResponseExtractor) — 4 params
            // Spring 6.1+    → SPRING_6_1:             doExecute(URI, String, HttpMethod, RequestCallback, ResponseExtractor) — 5 params
            // Index selection is based on actual descriptor arity for robustness.
            if (args.length == 4) {
                this.httpMethodIdx = 2;
                this.callbackIdx = 3;
            } else {
                this.httpMethodIdx = 3;
                this.callbackIdx = 4;
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("TraceIgnore")) {
                ignored = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        protected void onMethodEnter() {
            if (ignored) return;
            captureStartTime();
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (ignored) return;
            if (opcode == ATHROW) {
                // Stack at entry: [..., throwable]
                // DUP so the original throwable remains for ATHROW after the call.
                mv.visitInsn(DUP);
                // Stack: [..., throwable, throwable]
                mv.visitVarInsn(ALOAD, httpMethodIdx);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/springframework/http/HttpMethod", "name", "()Ljava/lang/String;", false);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "toString", "()Ljava/lang/String;", false);
                calculateDurationAndPush();
                // Stack: [..., throwable, throwable, method, url, duration(J)]
                mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpOutError",
                    "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;J)V", false);
                // Stack: [..., throwable]  — original throwable re-thrown by ATHROW
                return;
            }
            // Normal return: RestTemplate throws on 4xx/5xx by default (DefaultResponseErrorHandler),
            // so reaching here means success (2xx/3xx). Use 200 as a proxy status code.
            // STRUCTURAL LIMITATION: if a custom ResponseErrorHandler suppresses 4xx/5xx exceptions,
            // the actual status code cannot be retrieved at this bytecode injection point (doExecute
            // does not expose the ClientHttpResponse after execute()). Accepted trade-off.
            mv.visitVarInsn(ALOAD, httpMethodIdx);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/springframework/http/HttpMethod", "name", "()Ljava/lang/String;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/net/URI", "toString", "()Ljava/lang/String;", false);
            mv.visitIntInsn(SIPUSH, 200);
            calculateDurationAndPush();
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TraceRuntime", "onHttpOut", "(Ljava/lang/String;Ljava/lang/String;IJ)V", false);
        }
    }

    static class WebClientTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            // Prefix match — tolerates inner-class renames across Spring versions
            String normalized = className.replace('.', '/');
            if (!normalized.startsWith(AgentConfig.getHttpWebClientClassPrefix())) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        // Descriptor guard: only instrument exchange(ClientRequest)
                        if ("exchange".equals(name) && descriptor.contains("ClientRequest")) {
                            return new WebClientAdvice(mv, access, name, descriptor);
                        }
                        return mv;
                    }
                }, ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Exception e) { return null; }
        }
    }

    static class WebClientAdvice extends BaseAdvice {
        // Internal name of the method's declared return type (e.g. "reactor/core/publisher/Mono"
        // or "reactor/core/publisher/Flux"). Used for CHECKCAST after wrapWebClientExchange so
        // the JVM verifier accepts the subsequent ARETURN regardless of the reactive return type.
        private final String returnTypeInternalName;

        protected WebClientAdvice(MethodVisitor mv, int access, String name, String descriptor) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            Type rt = Type.getReturnType(descriptor);
            this.returnTypeInternalName = (rt.getSort() == Type.OBJECT) ? rt.getInternalName() : null;
        }
        @Override
        protected void onMethodEnter() {
            // ClientRequest is immutable — rebuild it with the txId header and replace arg slot 1.
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/core/TxIdHolder", "get", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "org/example/agent/plugin/http/HttpPlugin", "rebuildClientRequestWithHeader",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
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
            // wrapWebClientExchange returns Object; CHECKCAST to the method's declared return type
            // (Mono, Flux, or any Publisher subtype) so the JVM verifier accepts ARETURN.
            if (returnTypeInternalName != null) {
                mv.visitTypeInsn(CHECKCAST, returnTypeInternalName);
            }
        }
    }

    /**
     * Rebuilds a {@code ClientRequest} with the txId header added.
     * {@code ClientRequest} is immutable in Spring WebFlux — its internal headers are wrapped
     * with {@code HttpHeaders.readOnlyHttpHeaders()}, so direct {@code add()} calls throw
     * {@code UnsupportedOperationException}.  The only safe way to add a header is via the
     * {@code ClientRequest.from(original).header(key, value).build()} builder API.
     *
     * @return a new {@code ClientRequest} with the header, or the original if txId is null
     *         or the rebuild fails for any reason.
     */
    public static Object rebuildClientRequestWithHeader(Object request, String txId) {
        if (txId == null || request == null) return request;
        try {
            Class<?> crClass = Class.forName(
                "org.springframework.web.reactive.function.client.ClientRequest",
                false, request.getClass().getClassLoader());
            java.lang.reflect.Method fromMethod = crClass.getMethod("from", crClass);
            Object builder = fromMethod.invoke(null, request);
            java.lang.reflect.Method headerMethod = builder.getClass().getMethod("header", String.class, String[].class);
            Object builtBuilder = headerMethod.invoke(builder, AgentConfig.getHeaderKey(), new String[]{txId});
            java.lang.reflect.Method buildMethod = builtBuilder.getClass().getMethod("build");
            return buildMethod.invoke(builtBuilder);
        } catch (Throwable t) {
            return request;
        }
    }

    public static void injectHeaderToRequest(Object request, String txId) {
        if (txId == null || request == null) return;
        AgentLogger.debug("[HTTP-OUT] injectHeaderToRequest called — txId=" + txId + ", request=" + request.getClass().getSimpleName());
        boolean[] injected = {false};
        ReflectionUtils.invokeMethod(request, "getHeaders").ifPresent(headers -> {
            ReflectionUtils.invokeMethod(headers, "add", AgentConfig.getHeaderKey(), txId);
            injected[0] = true;
        });
        if (!injected[0]) {
            AgentLogger.debug("[HTTP-OUT] injectHeaderToRequest — getHeaders() returned empty for " + request.getClass().getSimpleName());
        }
    }

    public static void injectWebClientHeader(Object request, String txId) {
        if (txId == null || request == null) return;
        ReflectionUtils.invokeMethod(request, "headers").ifPresent(headers ->
            ReflectionUtils.invokeMethod(headers, "add", AgentConfig.getHeaderKey(), txId)
        );
    }
}
