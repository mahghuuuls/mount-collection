package com.mahghuuuls.mountcollection.persistence;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;

public final class TransferOperation {

    public static final int CURRENT_VERSION = 2;

    private final UUID operationId;
    private final MountId mountId;
    private final UUID ownerId;
    private final UUID sourceEntityId;
    private final UUID candidateEntityId;
    private final LastKnownEvidence sourceEvidence;
    private final LastKnownEvidence destinationEvidence;
    private final NBTTagCompound sourceSnapshot;
    private final long cooldownDeadline;
    private final long cooldownDuration;
    private final TransferPhase phase;
    private final String integrityReason;

    public TransferOperation(
            UUID operationId,
            MountId mountId,
            UUID ownerId,
            UUID sourceEntityId,
            UUID candidateEntityId,
            LastKnownEvidence sourceEvidence,
            LastKnownEvidence destinationEvidence,
            NBTTagCompound sourceSnapshot,
            long cooldownDeadline,
            long cooldownDuration,
            TransferPhase phase,
            String integrityReason) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.mountId = Objects.requireNonNull(mountId, "mountId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.sourceEntityId = Objects.requireNonNull(sourceEntityId, "sourceEntityId");
        this.candidateEntityId = Objects.requireNonNull(candidateEntityId, "candidateEntityId");
        if (sourceEntityId.equals(candidateEntityId)) {
            throw new IllegalArgumentException("source and candidate entity IDs must differ");
        }
        this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
        this.destinationEvidence = Objects.requireNonNull(destinationEvidence, "destinationEvidence");
        if (sourceEvidence.getDimensionId() == destinationEvidence.getDimensionId()) {
            throw new IllegalArgumentException("transfer dimensions must differ");
        }
        NBTTagCompound snapshot = Objects.requireNonNull(sourceSnapshot, "sourceSnapshot").copy();
        if (!snapshot.hasKey("id", 8)
                || snapshot.getString("id").isEmpty()
                || snapshot.getString("id").length() > 256) {
            throw new IllegalArgumentException("source snapshot entity type is missing or oversized");
        }
        this.sourceSnapshot = snapshot;
        if (cooldownDeadline < 0L || cooldownDuration < 0L) {
            throw new IllegalArgumentException("cooldown values must not be negative");
        }
        this.cooldownDeadline = cooldownDeadline;
        this.cooldownDuration = cooldownDuration;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.integrityReason = boundedNullable(integrityReason, 160);
    }

    public UUID getOperationId() { return operationId; }
    public MountId getMountId() { return mountId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getSourceEntityId() { return sourceEntityId; }
    public UUID getCandidateEntityId() { return candidateEntityId; }
    public LastKnownEvidence getSourceEvidence() { return sourceEvidence; }
    public LastKnownEvidence getDestinationEvidence() { return destinationEvidence; }
    public NBTTagCompound copySourceSnapshot() { return sourceSnapshot.copy(); }
    public long getCooldownDeadline() { return cooldownDeadline; }
    public long getCooldownDuration() { return cooldownDuration; }
    public TransferPhase getPhase() { return phase; }
    public String getIntegrityReason() { return integrityReason; }

    TransferOperation withPhase(TransferPhase nextPhase) {
        return copy(nextPhase, null);
    }

    public TransferOperation withEvidence(
            LastKnownEvidence nextSourceEvidence,
            LastKnownEvidence nextDestinationEvidence) {
        return new TransferOperation(
                operationId, mountId, ownerId, sourceEntityId, candidateEntityId,
                Objects.requireNonNull(nextSourceEvidence, "nextSourceEvidence"),
                Objects.requireNonNull(nextDestinationEvidence, "nextDestinationEvidence"),
                sourceSnapshot, cooldownDeadline, cooldownDuration, phase, integrityReason);
    }

    TransferOperation integrityBlocked(String reason) {
        return copy(TransferPhase.INTEGRITY_BLOCKED, reason);
    }

    private TransferOperation copy(TransferPhase nextPhase, String nextReason) {
        return new TransferOperation(
                operationId, mountId, ownerId, sourceEntityId, candidateEntityId,
                sourceEvidence, destinationEvidence, sourceSnapshot,
                cooldownDeadline, cooldownDuration, nextPhase, nextReason);
    }

    private static String boundedNullable(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
