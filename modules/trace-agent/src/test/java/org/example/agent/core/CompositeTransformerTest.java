package org.example.agent.core;

import org.example.agent.config.AgentConfig;
import org.example.agent.config.SpringVersionProfile;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompositeTransformerTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
        setResolvedProfile(SpringVersionProfile.SPRING_5);
    }

    @AfterEach
    void tearDown() throws Exception {
        setResolvedProfile(null);
        stateGuard.close();
    }

    @Test
    void transform_nullClassName_returnsNull() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        byte[] out = ct.transform(null, null, null, null, new byte[]{1, 2});
        assertNull(out);
    }

    @Test
    void transform_selfClass_skipsAlways() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        ct.addTargetPrefixes(List.of("org/example/agent/"));
        byte[] out = ct.transform(null, "org/example/agent/core/TraceRuntime", null, null, new byte[]{1});
        assertNull(out);
    }

    @Test
    void transform_prefixMiss_returnsNull() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        ct.addTargetPrefixes(List.of("com/acme/"));
        byte[] out = ct.transform(null, "org/example/any/ClassA", null, null, new byte[]{1});
        assertNull(out);
    }

    @Test
    void transform_chainStopsOnNullButAppliesLaterTransformer() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        ct.addTargetPrefixes(List.of("com/example/"));

        ct.addTransformer(transformerReturning(null));
        ct.addTransformer(transformerReturning(new byte[]{9, 9}));

        byte[] out = ct.transform(null, "com/example/MyClass", null, null, new byte[]{1, 2});
        assertNotNull(out);
        assertArrayEquals(new byte[]{9, 9}, out);
    }

    @Test
    void transform_exceptionInOneTransformer_doesNotBlockNextTransformer() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        ct.addTargetPrefixes(List.of("com/example/"));

        ct.addTransformer(transformerThrowing());
        ct.addTransformer(transformerReturning(new byte[]{5}));

        byte[] out = ct.transform(null, "com/example/MyClass", null, null, new byte[]{1});
        assertNotNull(out);
        assertArrayEquals(new byte[]{5}, out);
    }

    @Test
    void transform_allTransformersReturnNull_returnsNull() throws Exception {
        CompositeTransformer ct = new CompositeTransformer();
        ct.addTargetPrefixes(List.of("com/example/"));
        ct.addTransformer(transformerReturning(null));
        ct.addTransformer(transformerReturning(null));

        byte[] out = ct.transform(null, "com/example/MyClass", null, null, new byte[]{1, 2, 3});
        assertNull(out);
    }

    private void setResolvedProfile(SpringVersionProfile profile) throws Exception {
        Field field = AgentConfig.class.getDeclaredField("resolvedProfile");
        field.setAccessible(true);
        field.set(null, profile);
    }

    private ClassFileTransformer transformerReturning(byte[] out) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
            ) {
                return out;
            }
        };
    }

    private ClassFileTransformer transformerThrowing() {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
            ) throws IllegalClassFormatException {
                throw new IllegalClassFormatException("boom");
            }
        };
    }
}
