package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LifecycleMutationExecutorTest {

    @Test
    void boundedQueueRunsOnlyWhenTickEndDrainIsInvoked() {
        CapturingDiagnostics diagnostics = new CapturingDiagnostics();
        LifecycleMutationExecutor executor = new LifecycleMutationExecutor(diagnostics, 2);
        List<Integer> calls = new ArrayList<>();

        assertTrue(executor.enqueue(() -> calls.add(1)));
        assertTrue(executor.enqueue(() -> calls.add(2)));
        assertFalse(executor.enqueue(() -> calls.add(3)));
        assertTrue(calls.isEmpty());

        executor.drainAtServerTickEnd();

        assertEquals(java.util.Arrays.asList(1, 2), calls);
        assertEquals(0, executor.queuedCount());
        assertFalse(executor.isFatal());
    }

    @Test
    void workAddedDuringDrainWaitsForTheNextTickEndBoundary() {
        LifecycleMutationExecutor executor =
                new LifecycleMutationExecutor(new CapturingDiagnostics(), 3);
        List<Integer> calls = new ArrayList<>();
        executor.enqueue(() -> {
            calls.add(1);
            assertTrue(executor.enqueue(() -> calls.add(2)));
        });

        executor.drainAtServerTickEnd();
        assertEquals(java.util.Collections.singletonList(1), calls);
        assertEquals(1, executor.queuedCount());

        executor.drainAtServerTickEnd();
        assertEquals(java.util.Arrays.asList(1, 2), calls);
    }

    @Test
    void fatalSignalLatchesClearsLaterWorkWarnsOnceAndEscapes() {
        CapturingDiagnostics diagnostics = new CapturingDiagnostics();
        LifecycleMutationExecutor executor = new LifecycleMutationExecutor(diagnostics, 3);
        List<Integer> calls = new ArrayList<>();
        FatalTransferSafetyException fatal = new FatalTransferSafetyException(
                UUID.randomUUID(), TransferPhase.SOURCE_REMOVAL_INTENT,
                "quarantine acknowledgement failed");
        executor.enqueue(() -> {
            calls.add(1);
            throw fatal;
        });
        executor.enqueue(() -> calls.add(2));

        FatalTransferSafetyException thrown = assertThrows(
                FatalTransferSafetyException.class, executor::drainAtServerTickEnd);

        assertEquals(fatal, thrown);
        assertEquals(java.util.Collections.singletonList(1), calls);
        assertTrue(executor.isFatal());
        assertEquals(0, executor.queuedCount());
        assertFalse(executor.enqueue(() -> calls.add(3)));
        assertEquals(java.util.Collections.singletonList(
                "fatal_transfer_persistence_safety"), diagnostics.lifecycleWarnings);
    }

    private static final class CapturingDiagnostics implements DiagnosticSink {
        private final List<String> lifecycleWarnings = new ArrayList<>();

        @Override
        public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {}

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}

        @Override
        public void essentialLifecycleWarning(String event, String detail) {
            lifecycleWarnings.add(event);
        }
    }
}
