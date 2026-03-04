package org.example.agent.plugin.fileio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@DisplayName("플러그인: FileIO (Advice 바이트코드 검증)")
class FileIoPluginAdviceTest {

    @Test
    @DisplayName("FileInputStream.read 진입 시 경로 추출 및 시작 시간 기록이 호출되어야 한다")
    void testFileInputStreamAdviceOnMethodEnter() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        FileIoPlugin.FileInputStreamAdvice advice = new FileIoPlugin.FileInputStreamAdvice(mv, Opcodes.ACC_PUBLIC, "read", "([BII)I");

        advice.onMethodEnter();

        // 경로 추출기 호출 확인
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/plugin/fileio/FilePathExtractor"),
            eq("extract"),
            anyString(),
            eq(false)
        );
    }

    @Test
    @DisplayName("FileOutputStream.write 종료 시 onFileWrite가 호출되어야 한다")
    void testFileOutputStreamAdviceOnMethodExit() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        FileIoPlugin.FileOutputStreamAdvice advice = new FileIoPlugin.FileOutputStreamAdvice(mv, Opcodes.ACC_PUBLIC, "write", "([BII)V");

        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.RETURN);

        // TraceRuntime.onFileWrite 호출 확인
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onFileWrite"),
            eq("(Ljava/lang/String;JJZ)V"),
            eq(false)
        );
    }

    @Test
    @DisplayName("FileInputStream.read 예외 종료 시 onFileReadError가 호출되어야 한다")
    void testFileInputStreamAdviceOnMethodExit_throw_callsError() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        FileIoPlugin.FileInputStreamAdvice advice = new FileIoPlugin.FileInputStreamAdvice(mv, Opcodes.ACC_PUBLIC, "read", "([BII)I");

        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.ATHROW);

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onFileReadError"),
            eq("(Ljava/lang/String;JJLjava/lang/Throwable;)V"),
            eq(false)
        );
    }

    @Test
    @DisplayName("FileOutputStream.write 예외 종료 시 onFileWriteError가 호출되어야 한다")
    void testFileOutputStreamAdviceOnMethodExit_throw_callsError() {
        MethodVisitor mv = Mockito.mock(MethodVisitor.class);
        FileIoPlugin.FileOutputStreamAdvice advice = new FileIoPlugin.FileOutputStreamAdvice(mv, Opcodes.ACC_PUBLIC, "write", "([BII)V");

        advice.onMethodEnter();
        advice.onMethodExit(Opcodes.ATHROW);

        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onFileWriteError"),
            eq("(Ljava/lang/String;JJLjava/lang/Throwable;)V"),
            eq(false)
        );
    }
}
