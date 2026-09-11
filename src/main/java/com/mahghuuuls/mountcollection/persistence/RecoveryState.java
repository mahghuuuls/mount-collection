package com.mahghuuuls.mountcollection.persistence;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import java.util.Objects;
import java.util.UUID;

/** Immutable provider-owned Recovery snapshot and captured active-time deadline. */
public final class RecoveryState {

    public static final int CURRENT_VERSION = 1;

    private final UUID sourceEntityId;
    private final LastKnownEvidence sourceEvidence;
    private final ProviderPayload providerPayload;
    private final long deadline;
    private final long duration;

    public RecoveryState(
            UUID sourceEntityId,
            LastKnownEvidence sourceEvidence,
            ProviderPayload providerPayload,
            long deadline,
            long duration) {
        this.sourceEntityId = Objects.requireNonNull(sourceEntityId, "sourceEntityId");
        this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
        this.providerPayload = Objects.requireNonNull(providerPayload, "providerPayload");
        if (deadline < 0L || duration < 0L) {
            throw new IllegalArgumentException("Recovery timing must not be negative");
        }
        this.deadline = deadline;
        this.duration = duration;
    }

    public UUID getSourceEntityId() {
        return sourceEntityId;
    }

    RecoveryState withSourceEvidence(LastKnownEvidence evidence) {
        return new RecoveryState(sourceEntityId, evidence, providerPayload, deadline, duration);
    }

    public LastKnownEvidence getSourceEvidence() {
        return sourceEvidence;
    }

    public ProviderPayload getProviderPayload() {
        return new ProviderPayload(providerPayload.getVersion(), providerPayload.copyData());
    }

    public long getDeadline() {
        return deadline;
    }

    public long getDuration() {
        return duration;
    }
}
