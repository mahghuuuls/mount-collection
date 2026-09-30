package com.mahghuuuls.mountcollection.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RelocationTraceLogTest {
    @Test void commonFormatterDoesNotAccessClientOnlyEntityFields() throws Exception {
        // Class loading alone does not resolve field instructions inside pose().
        List<String> forbidden = new ArrayList<>();
        try (java.io.InputStream bytes = RelocationTraceLog.class.getResourceAsStream("RelocationTraceLog.class")) {
            assertNotNull(bytes);
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                            if (owner.equals("net/minecraft/entity/Entity")
                                    && (field.equals("serverPosX") || field.equals("serverPosY") || field.equals("serverPosZ"))) {
                                forbidden.add(name + ":" + field);
                            }
                        }
                    };
                }
            }, 0);
        }
        assertTrue(forbidden.isEmpty(), "Dedicated-server fields removed by Forge: " + forbidden);
    }

    @Test void disabledCaptureIsLazyAndEnabledCaptureHasHardBudget() {
        AtomicBoolean enabled = new AtomicBoolean();
        List<String> lines = new ArrayList<>();
        RelocationTraceLog trace = new RelocationTraceLog(enabled::get, lines::add);
        trace.record(() -> { throw new AssertionError("disabled snapshot evaluated"); });
        enabled.set(true);
        for (int i = 0; i < 100; i++) { trace.record(() -> "record"); }
        assertEquals(64, lines.size());
        trace.record(() -> { throw new AssertionError("exhausted snapshot evaluated"); });
    }
    @Test void brokenLoggerOrSnapshotStopsCaptureWithoutEscaping() {
        RelocationTraceLog logger = new RelocationTraceLog(() -> true,
                line -> { throw new IllegalStateException("logger unavailable"); });
        assertDoesNotThrow(() -> logger.record(() -> "record"));
        logger.record(() -> { throw new AssertionError("should stay stopped"); });
        RelocationTraceLog snapshot = new RelocationTraceLog(() -> true,
                line -> fail("failed snapshot emitted"));
        assertDoesNotThrow(() -> snapshot.record(() -> { throw new LinkageError("native unavailable"); }));
    }
}
