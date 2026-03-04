package org.example.agent.plugin.executor;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

class ExecutorPluginTransformerCoverageTest {

    @Test
    void executorTransformer_transformsThreadPoolExecute() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/util/concurrent/ThreadPoolExecutor",
            AsmTestUtils.MethodSpec.of("execute", "(Ljava/lang/Runnable;)V"));

        ExecutorPlugin.ExecutorTransformer t = new ExecutorPlugin.ExecutorTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "java/util/concurrent/ThreadPoolExecutor",
            null, null, original);

        assertNotNull(out);
    }

    @Test
    void executorTransformer_transformsForkJoinExternalRunnable() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/util/concurrent/ForkJoinPool",
            AsmTestUtils.MethodSpec.of("externalSubmit", "(Ljava/lang/Runnable;)V"));

        ExecutorPlugin.ExecutorTransformer t = new ExecutorPlugin.ExecutorTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "java/util/concurrent/ForkJoinPool",
            null, null, original);

        assertNotNull(out);
    }

    @Test
    void executorTransformer_nonTarget_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods("com/example/NoExecutor");
        ExecutorPlugin.ExecutorTransformer t = new ExecutorPlugin.ExecutorTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "com/example/NoExecutor", null, null, original));
    }

    @Test
    void asyncExecutionAspectTransformer_transformsHandleError() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "org/springframework/aop/interceptor/AsyncExecutionAspectSupport",
            AsmTestUtils.MethodSpec.of("handleError",
                "(Ljava/lang/Throwable;Ljava/lang/reflect/Method;[Ljava/lang/Object;)V"));

        ExecutorPlugin.AsyncExecutionAspectTransformer t = new ExecutorPlugin.AsyncExecutionAspectTransformer();
        byte[] out = t.transform(getClass().getClassLoader(),
            "org/springframework/aop/interceptor/AsyncExecutionAspectSupport", null, null, original);

        assertNotNull(out);
    }

    @Test
    void runnableWrappingAdvice_onMethodEnter_wrapsRunnable() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        ExecutorPlugin.RunnableWrappingAdvice advice = new ExecutorPlugin.RunnableWrappingAdvice(
            mv, Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V");

        advice.onMethodEnter();

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESPECIAL),
            eq("org/example/agent/core/ContextCapturingRunnable"),
            eq("<init>"),
            eq("(Ljava/lang/Runnable;)V"),
            eq(false)
        );
    }
}
