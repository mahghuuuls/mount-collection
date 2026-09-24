package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExperienceCoordinatorTest {
    @Test void plannedFallbackCannotUpgradeBeforeCompletionAndIsDeliveredOnce() {
        AtomicInteger calls = new AtomicInteger();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {
            assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, event.getDisposition()); calls.incrementAndGet();
        }, ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, () -> true, event ->
                assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, event.getDisposition()));
        coordinator.arrivalPlanned(request, ArrivalDisposition.UNMOUNTED_FALLBACK);
        coordinator.arrivalPlanned(request, ArrivalDisposition.COMBINED);
        assertFalse(coordinator.recoveryAdmission(request, owner).allowsBoarding());
        coordinator.complete(completion(request, owner)); coordinator.complete(completion(request, owner));
        assertEquals(1, calls.get());
    }

    @Test void finalAdmissionFallbackUpdatesExistingRequestWithoutNewRegistry() {
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {}, ignored -> {});
        coordinator.admit(request, owner, () -> true, event ->
                assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, event.getDisposition()));
        coordinator.recoveryAdmission(request, owner).useUnmountedFallback();
        assertFalse(coordinator.recoveryAdmission(request, owner).allowsBoarding());
        coordinator.complete(completion(request, owner));
    }
    @Test void recoveryContextErrorsDoNotBecomeExpiredOrConsumeCompletion() {
        AtomicInteger delivered = new AtomicInteger();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> delivered.incrementAndGet(), ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean(true);
        coordinator.admit(request, owner, () -> true, ignored -> {}, () -> {
            if (broken.get()) { throw new IllegalStateException("lookup failed"); }
            return RecoveryAdmission.optOut();
        });
        assertEquals(RecoveryAdmission.Mode.UNAVAILABLE, coordinator.recoveryAdmission(request, owner).getMode());
        assertEquals(RecoveryAdmission.Mode.UNAVAILABLE, coordinator.recoveryAdmission(request, UUID.randomUUID()).getMode());
        broken.set(false);
        assertEquals(RecoveryAdmission.Mode.OPT_OUT, coordinator.recoveryAdmission(request, owner).getMode());
        coordinator.complete(completion(request, owner));
        assertEquals(1, delivered.get());
        assertEquals(RecoveryAdmission.Mode.EXPIRED, coordinator.recoveryAdmission(request, owner).getMode());
    }

    @Test void expiryCannotBeRevivedAndFatalAdmissionFailuresEscape() {
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> fail("expired request replay"), ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        FatalTransferSafetyException fatal = FatalTransferSafetyException.abandonmentFailure(UUID.randomUUID());
        coordinator.admit(request, owner, current::get, ignored -> {}, () -> { throw fatal; });
        assertSame(fatal, assertThrows(FatalTransferSafetyException.class,
                () -> coordinator.recoveryAdmission(request, owner)));
        current.set(false);
        assertEquals(RecoveryAdmission.Mode.EXPIRED, coordinator.recoveryAdmission(request, owner).getMode());
        current.set(true);
        assertEquals(RecoveryAdmission.Mode.EXPIRED, coordinator.recoveryAdmission(request, owner).getMode());
        coordinator.complete(completion(request, owner));
        assertEquals(RecoveryAdmission.Mode.UNAVAILABLE, RecoveryAdmission.automatic(null).getMode());
    }
    @Test void boardingStillRunsOnceAfterCosmeticFailureAndCannotReplay() {
        AtomicInteger boarded = new AtomicInteger();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> { throw new IllegalStateException(); }, ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, () -> true, event -> boarded.incrementAndGet());
        ExperienceCompletion event = completion(request, owner);
        coordinator.complete(event); coordinator.complete(event);
        assertEquals(1, boarded.get());
    }

    @Test void registrationAndSessionInvalidationNeverBoard() {
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> current.set(false), ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, current::get, event -> fail("stale boarding"));
        coordinator.complete(completion(request, owner));
        current.set(true);
        coordinator.admit(request, owner, () -> true, event -> fail("registration boarding"));
        coordinator.complete(new ExperienceCompletion(request, owner, MountId.create(), UUID.randomUUID(),
                new LastKnownEvidence(0, 0, 64, 0), ExperienceCompletion.Kind.REGISTERED));
    }

    @Test void boardingExceptionDoesNotEscapeButFatalSafetyStillDoes() {
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {}, ignored -> {});
        UUID request = UUID.randomUUID(), owner = UUID.randomUUID();
        coordinator.admit(request, owner, () -> true, event -> { throw new IllegalStateException(); });
        assertDoesNotThrow(() -> coordinator.complete(completion(request, owner)));
        FatalTransferSafetyException fatal = FatalTransferSafetyException.abandonmentFailure(UUID.randomUUID());
        coordinator.admit(request, owner, () -> true, event -> { throw fatal; });
        assertSame(fatal, assertThrows(FatalTransferSafetyException.class,
                () -> coordinator.complete(completion(request, owner))));
    }
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
