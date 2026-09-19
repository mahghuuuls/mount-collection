package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExperienceCoordinatorTest {
    @Test void fatalDeliveryEscapesUnchangedAndConsumesEligibility() {
        FatalTransferSafetyException fatal = FatalTransferSafetyException.abandonmentFailure(UUID.randomUUID());
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> { throw fatal; }, ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, () -> true);
        ExperienceCompletion event = completion(request, owner);
        assertSame(fatal, assertThrows(FatalTransferSafetyException.class, () -> coordinator.complete(event)));
        assertDoesNotThrow(() -> coordinator.complete(event));
    }

    private ExperienceCompletion completion(UUID request, UUID owner) {
        return new ExperienceCompletion(request, owner, MountId.create(), UUID.randomUUID(),
                new LastKnownEvidence(0, 0, 64, 0), ExperienceCompletion.Kind.ARRIVED);
    }

    @Test void consumesBeforeReentrantDeliveryAndIgnoresDuplicate() {
        AtomicInteger calls = new AtomicInteger();
        ExperienceCoordinator[] coordinator = new ExperienceCoordinator[1];
        coordinator[0] = new ExperienceCoordinator(event -> {
            calls.incrementAndGet();
            coordinator[0].complete(event);
        }, ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        assertTrue(coordinator[0].admit(request, owner, () -> true));
        ExperienceCompletion event = completion(request, owner);
        coordinator[0].complete(event);
        coordinator[0].complete(event);
        assertEquals(1, calls.get());
    }

    @Test void suppressesStaleWrongOwnerCancelledAndRestartedRequests() {
        AtomicInteger calls = new AtomicInteger();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> calls.incrementAndGet(), ignored -> {});
        UUID owner = UUID.randomUUID();
        UUID stale = UUID.randomUUID(), wrong = UUID.randomUUID(), cancelled = UUID.randomUUID(), restart = UUID.randomUUID();
        coordinator.admit(stale, owner, () -> false);
        coordinator.complete(completion(stale, owner));
        coordinator.admit(wrong, owner, () -> true);
        coordinator.complete(completion(wrong, UUID.randomUUID()));
        coordinator.admit(cancelled, owner, () -> true);
        coordinator.cancel(cancelled);
        coordinator.complete(completion(cancelled, owner));
        coordinator.admit(restart, owner, () -> true);
        coordinator.clear();
        coordinator.complete(completion(restart, owner));
        assertEquals(0, calls.get());
    }

    @Test void deliveryAndDiagnosticsFailureDoNotEscapeOrRetry() {
        AtomicInteger calls = new AtomicInteger();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {
            calls.incrementAndGet(); throw new IllegalStateException();
        }, ignored -> { throw new IllegalStateException(); });
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, () -> true);
        assertDoesNotThrow(() -> coordinator.complete(completion(request, owner)));
        coordinator.complete(completion(request, owner));
        assertEquals(1, calls.get());
    }

    @Test void boundedAdmissionAndCancelledOperationCleanup() {
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> fail(), ignored -> {});
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 1024; i++) {
            assertTrue(coordinator.admit(UUID.randomUUID(), owner, () -> true));
        }
        assertFalse(coordinator.admit(UUID.randomUUID(), owner, () -> true));
        coordinator.retainPending(id -> false);
        UUID next = UUID.randomUUID();
        assertTrue(coordinator.admit(next, owner, () -> true));
        coordinator.removeOwner(owner);
        coordinator.complete(completion(next, owner));
    }
}
