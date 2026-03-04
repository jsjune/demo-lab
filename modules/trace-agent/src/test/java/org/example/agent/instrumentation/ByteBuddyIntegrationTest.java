package org.example.agent.instrumentation;

import java.lang.instrument.ClassFileTransformer;
import java.io.InputStream;

/**
 * Base class for bytecode transformation integration tests.
 */
public abstract class ByteBuddyIntegrationTest {

    protected Class<?> transformAndLoad(Class<?> target, ClassFileTransformer transformer) throws Exception {
        String className = target.getName();
        String path = className.replace('.', '/') + ".class";
        byte[] originalBuffer;
        try (InputStream in = target.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Cannot load bytecode resource '" + path + "' for class " + className);
            }
            originalBuffer = in.readAllBytes();
        }

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
