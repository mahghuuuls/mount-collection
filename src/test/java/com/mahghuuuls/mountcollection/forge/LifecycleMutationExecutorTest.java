package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.network.MountNetwork;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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

    @Test
    void fatalSignalEscapesNetworkMappingAndStopsLaterQueuedWork() {
        CapturingDiagnostics diagnostics = new CapturingDiagnostics();
        LifecycleMutationExecutor executor = new LifecycleMutationExecutor(diagnostics, 2);
        List<Integer> calls = new ArrayList<>();
        FatalTransferSafetyException fatal = new FatalTransferSafetyException(
                UUID.randomUUID(), TransferPhase.CANDIDATE_SPAWN_INTENT,
                "unexpected post-intent gateway exception");
        executor.enqueue(() -> {
            calls.add(1);
            invokeNetworkExecution(() -> { throw fatal; });
        });
        executor.enqueue(() -> calls.add(2));

        FatalTransferSafetyException thrown = assertThrows(
                FatalTransferSafetyException.class, executor::drainAtServerTickEnd);

        assertEquals(fatal, thrown);
        assertEquals(java.util.Collections.singletonList(1), calls);
        assertTrue(executor.isFatal());
        assertEquals(0, executor.queuedCount());
    }

    private static ContextualOutcome invokeNetworkExecution(
            Supplier<ContextualOutcome> intent) {
        try {
            Method execution = MountNetwork.class.getDeclaredMethod(
                    "executeLifecycleIntent", Supplier.class);
            execution.setAccessible(true);
            return (ContextualOutcome) execution.invoke(null, intent);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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
