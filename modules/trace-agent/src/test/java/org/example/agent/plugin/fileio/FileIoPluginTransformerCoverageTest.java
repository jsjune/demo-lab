package org.example.agent.plugin.fileio;

import org.example.agent.testutil.AsmTestUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileIoPluginTransformerCoverageTest {

    @Test
    void fileInputStreamTransformer_transformsReadCandidate() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/io/FileInputStream",
            AsmTestUtils.MethodSpec.of("read", "([BII)I"));

        FileIoPlugin.FileInputStreamTransformer t = new FileIoPlugin.FileInputStreamTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "java/io/FileInputStream", null, null, original);

        assertNotNull(out);
    }

    @Test
    void fileInputStreamTransformer_nonCandidate_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/io/FileInputStream",
            AsmTestUtils.MethodSpec.of("read", "()I"));

        FileIoPlugin.FileInputStreamTransformer t = new FileIoPlugin.FileInputStreamTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "java/io/BufferedInputStream", null, null, original));
    }

    @Test
    void fileOutputStreamTransformer_transformsWriteCandidate() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/io/FileOutputStream",
            AsmTestUtils.MethodSpec.of("write", "([BII)V"));

        FileIoPlugin.FileOutputStreamTransformer t = new FileIoPlugin.FileOutputStreamTransformer();
        byte[] out = t.transform(getClass().getClassLoader(), "java/io/FileOutputStream", null, null, original);

        assertNotNull(out);
    }

    @Test
    void fileOutputStreamTransformer_nonCandidate_returnsNull() throws Exception {
        byte[] original = AsmTestUtils.classWithMethods(
            "java/io/FileOutputStream",
            AsmTestUtils.MethodSpec.of("write", "(I)V"));

        FileIoPlugin.FileOutputStreamTransformer t = new FileIoPlugin.FileOutputStreamTransformer();
        assertNull(t.transform(getClass().getClassLoader(), "java/io/BufferedOutputStream", null, null, original));
    }
}
