package org.example.agent.integration;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for bytecode transformation integration tests.
 */
public abstract class ByteBuddyIntegrationTest {

    protected Class<?> transformAndLoad(Class<?> target, ClassFileTransformer transformer) throws Exception {
        String className = target.getName();
        String path = className.replace('.', '/') + ".class";
        byte[] originalBuffer = target.getClassLoader().getResourceAsStream(path).readAllBytes();

        byte[] transformedBuffer = transformer.transform(
            target.getClassLoader(),
            className.replace('.', '/'),
            target,
            null,
            originalBuffer
        );

        if (transformedBuffer == null) {
            transformedBuffer = originalBuffer;
        }

        return new ByteCodeClassLoader(target.getClassLoader())
            .defineClass(className, transformedBuffer);
    }

    private static class ByteCodeClassLoader extends ClassLoader {
        public ByteCodeClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> defineClass(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }
}
