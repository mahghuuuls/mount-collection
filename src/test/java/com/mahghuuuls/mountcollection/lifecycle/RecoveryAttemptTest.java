package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.RestorationOperation;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RecoveryAttemptTest {
    @Test void admissionRequiresAcknowledgementAndIsConsumedEvenOnEarlyRejection() {
        AtomicInteger calls = new AtomicInteger();
        RecoveryAttempt attempt = new RecoveryAttempt(operation -> {
            calls.incrementAndGet(); return RecallWorldGateway.CandidateAction.SUCCESS;
        });
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(operation(RestorationPhase.PREPARED)));
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE,
                attempt.admit(operation(RestorationPhase.CANDIDATE_SPAWN_INTENT)));
        assertEquals(0, calls.get());
    }

    @Test void closeAndReentrantCallsCannotAdmitAgain() {
        RecoveryAttempt[] attempt = new RecoveryAttempt[1];
        AtomicInteger calls = new AtomicInteger();
        RestorationOperation intent = operation(RestorationPhase.CANDIDATE_SPAWN_INTENT);
        attempt[0] = new RecoveryAttempt(operation -> {
            calls.incrementAndGet();
            assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt[0].admit(intent));
            return RecallWorldGateway.CandidateAction.SUCCESS;
        });
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, attempt[0].admit(intent));
        assertEquals(1, calls.get());
        RecoveryAttempt closed = new RecoveryAttempt(operation -> { fail("closed candidate reused"); return null; });
        closed.close();
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, closed.admit(intent));
    }

    @Test void fatalAdmissionIsNotSwallowedAndCannotReplay() {
        FatalTransferSafetyException fatal = FatalTransferSafetyException.abandonmentFailure(UUID.randomUUID());
        RecoveryAttempt attempt = new RecoveryAttempt(operation -> { throw fatal; });
        RestorationOperation intent = operation(RestorationPhase.CANDIDATE_SPAWN_INTENT);
        assertSame(fatal, assertThrows(FatalTransferSafetyException.class, () -> attempt.admit(intent)));
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(intent));
    }

    private static RestorationOperation operation(RestorationPhase phase) {
        return new RestorationOperation(UUID.randomUUID(), MountId.create(), UUID.randomUUID(), UUID.randomUUID(),
                new LastKnownEvidence(0, 8, 64, 8), 20, 20, phase, null);
    }
}
