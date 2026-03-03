package org.example.agent.plugin.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.agent.core.TraceRuntime;
import org.example.agent.core.TxIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 통합 테스트: SafeClassWriter + DispatcherServletAdvice 바이트코드 변환 후
 * 실제 실행으로 에러 디스패치 필터링 동작을 검증한다.
 *
 * <p>HttpPlugin의 package-private 내부 클래스(SafeClassWriter, DispatcherServletAdvice)에
 * 접근하기 위해 같은 패키지(org.example.agent.plugin.http)에 위치한다.
 */
@DisplayName("통합: DispatcherServlet 에러 디스패치 필터링 바이트코드 변환 검증")
class HttpDispatcherIntegrationTest {

    /** Jakarta 서블릿 타입의 doDispatch를 가진 가짜 DispatcherServlet */
    public static class FakeJakartaDispatcher {
        public void doDispatch(HttpServletRequest request, HttpServletResponse response)
                throws Exception {
            // empty — 변환 후 진입/종료 bytecode가 주입된다
        }
    }

    /**
     * SafeClassWriter(COMPUTE_FRAMES) + DispatcherServletAdvice를 직접 사용하여
     * 대상 클래스를 변환하고 새 ClassLoader에 로드한다.
     * DispatcherServletTransformer의 클래스명 필터를 우회하여 테스트 전용 클래스에 적용.
     */
    private Class<?> transformAndLoad(Class<?> target, boolean isJakarta) throws Exception {
        String path = target.getName().replace('.', '/') + ".class";
        byte[] buf = target.getClassLoader().getResourceAsStream(path).readAllBytes();

        ClassReader reader = new ClassReader(buf);
        HttpPlugin.SafeClassWriter writer =
            new HttpPlugin.SafeClassWriter(reader, target.getClassLoader());

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("doDispatch".equals(name)) {
                    return new HttpPlugin.DispatcherServletAdvice(
                        mv, access, name, descriptor, isJakarta);
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        byte[] transformed = writer.toByteArray();
        return new ClassLoader(target.getClassLoader()) {
            Class<?> define(byte[] b) {
                return defineClass(target.getName(), b, 0, b.length);
            }
        }.define(transformed);
    }

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
    }

    @Test
    @DisplayName("에러 포워딩 요청(jakarta.servlet.error.request_uri 속성 있음)은 HTTP_IN_START를 발행하지 않아야 한다")
    void errorDispatch_jakartaAttribute_doesNotTraceHttpInStart() throws Exception {
        Class<?> cls = transformAndLoad(FakeJakartaDispatcher.class, true);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("doDispatch",
            HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute("jakarta.servlet.error.request_uri", "/api/chain");

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            // isErrorDispatch()는 실제 구현을 사용해야 한다
            runtimeMock.when(() -> TraceRuntime.isErrorDispatch(any()))
                       .thenCallRealMethod();

            method.invoke(instance, request, new MockHttpServletResponse());

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(anyString(), anyString(), anyString(), anyBoolean()),
                never()
            );
        }
    }

    @Test
    @DisplayName("직접 /error 요청(에러 속성 없음)은 HTTP_IN_START를 발행해야 한다")
    void directErrorEndpoint_noAttribute_tracesHttpInStart() throws Exception {
        Class<?> cls = transformAndLoad(FakeJakartaDispatcher.class, true);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("doDispatch",
            HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        // 에러 속성 없음 — 클라이언트의 직접 요청

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isErrorDispatch(any()))
                       .thenCallRealMethod();

            method.invoke(instance, request, new MockHttpServletResponse());

            // txId 헤더가 없으면 null이 전달될 수 있으므로 any()로 검증
            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), any(), any(), anyBoolean()),
                atLeastOnce()
            );
        }
    }

    @Test
    @DisplayName("에러 포워딩 요청(javax.servlet.error.request_uri 속성 있음)도 필터링해야 한다 (Spring Boot 2.x 하위 호환)")
    void errorDispatch_javaxAttribute_doesNotTraceHttpInStart() throws Exception {
        // javax 속성 감지는 isErrorDispatch()의 문자열 키 체크이므로
        // Jakarta 타입 dispatcher로 변환해도 동일하게 검증된다
        Class<?> cls = transformAndLoad(FakeJakartaDispatcher.class, true);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method method = cls.getDeclaredMethod("doDispatch",
            HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute("javax.servlet.error.request_uri", "/api/chain");

        try (MockedStatic<TraceRuntime> runtimeMock = mockStatic(TraceRuntime.class)) {
            runtimeMock.when(() -> TraceRuntime.isErrorDispatch(any()))
                       .thenCallRealMethod();

            method.invoke(instance, request, new MockHttpServletResponse());

            runtimeMock.verify(
                () -> TraceRuntime.onHttpInStart(any(), any(), any(), anyBoolean()),
                never()
            );
        }
    }

    @Test
    @DisplayName("SafeClassWriter는 COMPUTE_FRAMES로 유효한 스택맵 프레임을 생성해야 한다 (VerifyError 없음)")
    void safeClassWriter_computeFrames_producesVerifiableClass() throws Exception {
        // defineClass() 시점에 JVM 바이트코드 검증이 실행된다.
        // VerifyError 없이 클래스가 로드되고 인스턴스화되면 프레임 계산이 올바른 것이다.
        Class<?> cls = transformAndLoad(FakeJakartaDispatcher.class, true);
        Object instance = cls.getDeclaredConstructor().newInstance();

        assertNotNull(instance);
    }

    /**
     * COMPUTE_MAXS는 IFEQ 분기를 주입해도 StackMapTable 항목을 재계산하지 않음을 검증한다.
     *
     * <p>SafeClassWriter Javadoc에 명시된 계약의 음성 케이스(negative case):
     * "COMPUTE_MAXS only recomputes max_stack/max_locals and leaves existing frames unchanged."
     *
     * <p>원본 doDispatch()는 빈 메서드(분기 없음 → StackMapTable 없음)이다.
     * DispatcherServletAdvice가 IFEQ 분기를 주입한 후:
     * <ul>
     *   <li>COMPUTE_MAXS: 새 분기 타깃에 대한 프레임 항목을 생성하지 않는다 (0개)</li>
     *   <li>COMPUTE_FRAMES: 모든 새 분기 타깃에 대한 프레임 항목을 재계산한다 (N > 0개)</li>
     * </ul>
     * COMPUTE_MAXS 출력의 누락된 프레임은 JVM 검증 시 VerifyError를 유발한다.
     */
    @Test
    @DisplayName("COMPUTE_MAXS는 IFEQ 분기 주입 후 StackMapTable 항목을 생성하지 않아야 한다 (COMPUTE_FRAMES 필수 근거)")
    void computeMaxs_withBranchInjection_producesNoStackMapFrames() throws Exception {
        byte[] withComputeMaxs  = transformToBytes(FakeJakartaDispatcher.class, true, false);
        byte[] withComputeFrames = transformToBytes(FakeJakartaDispatcher.class, true, true);

        int maxsFrameCount          = countFramesInMethod(withComputeMaxs,   "doDispatch");
        int computeFramesFrameCount = countFramesInMethod(withComputeFrames, "doDispatch");

        assertEquals(0, maxsFrameCount,
            "COMPUTE_MAXS should NOT generate StackMapTable entries for injected IFEQ branch targets");
        assertTrue(computeFramesFrameCount > 0,
            "COMPUTE_FRAMES should generate StackMapTable entries for all new branch targets");
    }

    /**
     * 대상 클래스를 어드바이스로 변환하여 바이트 배열로 반환한다.
     *
     * @param useComputeFrames true → SafeClassWriter(COMPUTE_FRAMES), false → ClassWriter(COMPUTE_MAXS)
     */
    private byte[] transformToBytes(Class<?> target, boolean isJakarta, boolean useComputeFrames)
            throws Exception {
        String path = target.getName().replace('.', '/') + ".class";
        byte[] buf = target.getClassLoader().getResourceAsStream(path).readAllBytes();

        ClassReader reader = new ClassReader(buf);
        ClassWriter writer = useComputeFrames
            ? new HttpPlugin.SafeClassWriter(reader, target.getClassLoader())
            : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("doDispatch".equals(name)) {
                    return new HttpPlugin.DispatcherServletAdvice(
                        mv, access, name, descriptor, isJakarta);
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return writer.toByteArray();
    }

    /**
     * 바이트 배열로부터 지정한 메서드의 StackMapTable 항목 수를 센다.
     * EXPAND_FRAMES 없이 읽어 실제 StackMapTable 항목만 카운트한다.
     */
    private int countFramesInMethod(byte[] classBytes, String methodName) {
        ClassReader cr = new ClassReader(classBytes);
        int[] count = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!methodName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local,
                                           int numStack, Object[] stack) {
                        count[0]++;
                    }
                };
            }
        }, 0); // No EXPAND_FRAMES — count raw StackMapTable entries only
        return count[0];
    }
}
