package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PendingTransferRecoverySchedulerTest {

    @Test
    void earlyWorldLoadsDoNotReconcileBeforeEntityLoadingCanExposeRelocation() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();

        scheduler.repositoryActivated();
        scheduler.worldLoaded();
        scheduler.worldLoaded();

        assertNull(scheduler.poll());
    }

    @Test
    void sourceObservationCarriesNoPhasePolicy() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.repositoryActivated();

        scheduler.controlledEntityJoined(operationId, false);

        PendingTransferRecoveryScheduler.Request request = scheduler.poll();
        assertEquals(operationId, request.getOperationId());
        assertTrue(request.isSourceObserved());
        assertFalse(request.isCandidateObserved());
    }

    @Test
    void playerBeforeCandidateDoesNotCreateReadiness() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.repositoryActivated();

        scheduler.playerJoined();
        assertNull(scheduler.poll());

        scheduler.controlledEntityJoined(operationId, true);
        PendingTransferRecoveryScheduler.Request request = scheduler.poll();
        assertEquals(operationId, request.getOperationId());
        assertFalse(request.isSourceObserved());
        assertTrue(request.isCandidateObserved());
        assertNull(scheduler.poll());
    }

    @Test
    void candidateFirstRequestsOnlyItsOperation() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID candidateOperation = UUID.randomUUID();
        UUID unrelatedOperation = UUID.randomUUID();
        scheduler.repositoryActivated();

        scheduler.controlledEntityJoined(candidateOperation, true);
        scheduler.controlledEntityJoined(unrelatedOperation, false);

        assertEquals(candidateOperation, scheduler.poll().getOperationId());
        assertEquals(unrelatedOperation, scheduler.poll().getOperationId());
    }

    @Test
    void observationsForBothRolesAreAccumulated() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.repositoryActivated();

        scheduler.controlledEntityJoined(operationId, false);
        scheduler.controlledEntityJoined(operationId, true);

        PendingTransferRecoveryScheduler.Request request = scheduler.poll();
        assertEquals(operationId, request.getOperationId());
        assertTrue(request.isSourceObserved());
        assertTrue(request.isCandidateObserved());
        assertNull(scheduler.poll());
    }

    @Test
    void laterWorldLoadRetriesOnlyPreviouslyProvenOperation() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.repositoryActivated();
        scheduler.controlledEntityJoined(operationId, true);
        assertEquals(operationId, scheduler.poll().getOperationId());

        scheduler.worldLoaded();

        assertEquals(operationId, scheduler.poll().getOperationId());
        assertNull(scheduler.poll());
    }

    @Test
    void resetDropsReadinessAndQueuedWork() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.controlledEntityJoined(operationId, true);

        scheduler.reset();
        scheduler.worldLoaded();

        assertNull(scheduler.poll());
    }

    @Test
    void deferredRequestRetainsAccumulatedRoleEvidence() {
        PendingTransferRecoveryScheduler scheduler = new PendingTransferRecoveryScheduler();
        UUID operationId = UUID.randomUUID();
        scheduler.controlledEntityJoined(operationId, false);
        PendingTransferRecoveryScheduler.Request first = scheduler.poll();
        scheduler.controlledEntityJoined(operationId, true);

        scheduler.defer(first);

        PendingTransferRecoveryScheduler.Request retried = scheduler.poll();
        assertEquals(operationId, retried.getOperationId());
        assertTrue(retried.isSourceObserved());
        assertTrue(retried.isCandidateObserved());
    }
}
