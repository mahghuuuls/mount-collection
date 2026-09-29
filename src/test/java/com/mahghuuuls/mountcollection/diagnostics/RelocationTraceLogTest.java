package com.mahghuuuls.mountcollection.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RelocationTraceLogTest {
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
