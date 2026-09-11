package com.mahghuuuls.mountcollection.persistence;

import java.util.Objects;
import java.util.UUID;

/** Immutable crash journal for creating one representative from a Recovery snapshot. */
public final class RestorationOperation {

    public static final int CURRENT_VERSION = 2;
    private static final int MAX_INTEGRITY_REASON_LENGTH = 160;

    private final UUID operationId;
    private final MountId mountId;
    private final UUID ownerId;
    private final UUID candidateEntityId;
    private final LastKnownEvidence destinationEvidence;
    private final long cooldownDeadline;
    private final long cooldownDuration;
    private final RestorationPhase phase;
    private final String integrityReason;

    public RestorationOperation(
            UUID operationId,
            MountId mountId,
            UUID ownerId,
            UUID candidateEntityId,
            LastKnownEvidence destinationEvidence,
            long cooldownDeadline,
            long cooldownDuration,
            RestorationPhase phase,
            String integrityReason) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.mountId = Objects.requireNonNull(mountId, "mountId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.candidateEntityId = Objects.requireNonNull(candidateEntityId, "candidateEntityId");
        this.destinationEvidence = Objects.requireNonNull(destinationEvidence, "destinationEvidence");
        if (cooldownDeadline < 0L || cooldownDuration < 0L) {
            throw new IllegalArgumentException("restoration timing must not be negative");
        }
        this.cooldownDeadline = cooldownDeadline;
        this.cooldownDuration = cooldownDuration;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.integrityReason = integrityReason;
        if ((phase == RestorationPhase.INTEGRITY_BLOCKED) != (integrityReason != null)) {
            throw new IllegalArgumentException("restoration integrity reason mismatch");
        }
        if (integrityReason != null
                && integrityReason.length() > MAX_INTEGRITY_REASON_LENGTH) {
            throw new IllegalArgumentException("restoration integrity reason is too long");
        }
    }

    public UUID getOperationId() { return operationId; }
    public MountId getMountId() { return mountId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getCandidateEntityId() { return candidateEntityId; }
    public LastKnownEvidence getDestinationEvidence() { return destinationEvidence; }
    public long getCooldownDeadline() { return cooldownDeadline; }
    public long getCooldownDuration() { return cooldownDuration; }
    public RestorationPhase getPhase() { return phase; }
    public String getIntegrityReason() { return integrityReason; }

    RestorationOperation withPhase(RestorationPhase next) {
        return new RestorationOperation(operationId, mountId, ownerId, candidateEntityId,
                destinationEvidence, cooldownDeadline, cooldownDuration, next, null);
    }

    RestorationOperation withEvidence(LastKnownEvidence evidence) {
        return new RestorationOperation(operationId, mountId, ownerId, candidateEntityId,
                evidence, cooldownDeadline, cooldownDuration, phase, integrityReason);
    }

    RestorationOperation integrityBlocked(String reason) {
        return new RestorationOperation(operationId, mountId, ownerId, candidateEntityId,
                destinationEvidence, cooldownDeadline, cooldownDuration,
                RestorationPhase.INTEGRITY_BLOCKED,
                Objects.requireNonNull(reason, "reason"));
    }
}
