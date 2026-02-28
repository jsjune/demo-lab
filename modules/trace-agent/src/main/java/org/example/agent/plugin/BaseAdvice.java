package org.example.agent.plugin;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/**
 * Base class for injected logic to reduce boilerplate in plugins.
 * Handles common tasks like local variable management for timers.
 */
public abstract class BaseAdvice extends AdviceAdapter {
    protected int startTimeId;

    protected BaseAdvice(int api, MethodVisitor mv, int access, String name, String descriptor) {
        super(api, mv, access, name, descriptor);
    }

    protected void pushCurrentTime() {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
    }

    protected void captureStartTime() {
        startTimeId = newLocal(Type.LONG_TYPE);
        pushCurrentTime();
        mv.visitVarInsn(LSTORE, startTimeId);
    }

    protected void calculateDurationAndPush() {
        pushCurrentTime();
        mv.visitVarInsn(LLOAD, startTimeId);
        mv.visitInsn(LSUB);
    }
}
