package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.RestorationOperation;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import java.util.Objects;
import java.util.function.Function;

/** Opaque, single-use world admission. Closing releases the unspawned candidate reference. */
public final class RecoveryAttempt implements AutoCloseable {
    private Function<RestorationOperation, RecallWorldGateway.CandidateAction> admission;
    public RecoveryAttempt(Function<RestorationOperation, RecallWorldGateway.CandidateAction> admission) {
        this.admission = Objects.requireNonNull(admission);
    }
    public RecallWorldGateway.CandidateAction admit(RestorationOperation acknowledged) {
        Function<RestorationOperation, RecallWorldGateway.CandidateAction> action = admission;
        admission = null; // Consume before callbacks, including throwing and reentrant callbacks.
        if (action == null || acknowledged.getPhase() != RestorationPhase.CANDIDATE_SPAWN_INTENT) {
            return RecallWorldGateway.CandidateAction.UNAVAILABLE;
        }
        return action.apply(acknowledged);
    }
    @Override public void close() { admission = null; }
}
