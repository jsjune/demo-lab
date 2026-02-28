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

        advice.onMethodExit(Opcodes.RETURN);

        // TraceRuntime.onFileWrite 호출 확인
        verify(mv, atLeastOnce()).visitMethodInsn(
            eq(Opcodes.INVOKESTATIC),
            eq("org/example/agent/core/TraceRuntime"),
            eq("onFileWrite"),
            anyString(),
            eq(false)
        );
    }
}
