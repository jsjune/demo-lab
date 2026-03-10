package org.example.agent.instrumentation;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatcher;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Base class for ByteBuddy @Advice instrumentation integration tests.
 *
 * <p>Provides {@link #instrument} to apply advice to a target class
 * and load the instrumented class in a child ClassLoader.
 */
public abstract class ByteBuddyIntegrationTest {

    /**
     * Apply the given advice class to the target class and return the
     * instrumented class loaded in a fresh child ClassLoader.
     *
     * @param targetClass   class to instrument
     * @param adviceClass   @Advice annotated class
     * @param methodMatcher ElementMatcher selecting which methods to instrument
     * @return the instrumented class, ready for reflective invocation
     */
    protected Class<?> instrument(
            Class<?> targetClass,
            Class<?> adviceClass,
            ElementMatcher<? super MethodDescription> methodMatcher) throws Exception {

        DynamicType.Unloaded<?> unloaded = new ByteBuddy()
            .redefine(targetClass)
            .visit(Advice.to(adviceClass).on(methodMatcher))
            .make();

        return unloaded
            .load(new URLClassLoader(new URL[0], targetClass.getClassLoader()),
                  ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();
    }
}
