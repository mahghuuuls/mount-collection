package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

public interface RecallWorldGateway {

    LocateResult locate(EntityPlayerMP player, MountRecord record);

    boolean providerSupports(Source source, MountProvider provider);

    Optional<Destination> plan(
            EntityPlayerMP player,
            Source source,
            MountCharacteristics characteristics,
            int normalRadius,
            int fallbackRadius);

    boolean commit(
            EntityPlayerMP player, Source source, Destination destination, MountProvider provider);

    default Optional<TransferPlan> captureTransfer(
            EntityPlayerMP player,
            Source source,
            Destination destination,
            MountProvider provider) {
        return Optional.empty();
    }

    default CandidateAction spawnCandidate(TransferOperation operation) {
        return CandidateAction.FAILED;
    }

    default TransferEvidence inspectTransfer(TransferOperation operation) {
        return TransferEvidence.conflict();
    }

    default TransferEvidence inspectTransfer(
            TransferOperation operation, boolean inspectSource, boolean inspectCandidate) {
        return inspectTransfer(operation);
    }

    default PhysicalAction validateSourceRemoval(TransferOperation operation) {
        return PhysicalAction.SUCCESS;
    }

    default CheckpointStatus checkpointCandidate(
            TransferOperation operation, boolean operationMarkerExpected) {
        return CheckpointStatus.FAILED;
    }

    default CheckpointStatus checkpointCandidateAbsent(TransferOperation operation) {
        return CheckpointStatus.FAILED;
    }

    default PhysicalAction removeSource(TransferOperation operation) {
        return PhysicalAction.FAILED;
    }

    default PhysicalAction removeCandidate(TransferOperation operation) {
        return PhysicalAction.FAILED;
    }

    default PhysicalAction clearCandidateOperationMarker(TransferOperation operation) {
        return PhysicalAction.FAILED;
    }

    default CheckpointStatus checkpointSourceAbsent(TransferOperation operation) {
        return CheckpointStatus.FAILED;
    }

    default boolean pauseAfterPhase(com.mahghuuuls.mountcollection.persistence.TransferPhase phase) {
        return false;
    }

    default void transferPhaseAcknowledged(
            com.mahghuuuls.mountcollection.persistence.TransferPhase phase) {}

    enum CandidateAction {
        SUCCESS,
        FAILED,
        UNAVAILABLE,
        CONFLICT
    }

    enum PhysicalAction {
        SUCCESS,
        FAILED_RESTORED,
        FAILED,
        UNAVAILABLE,
        CONFLICT
    }

    enum CheckpointStatus {
        VERIFIED,
        FAILED,
        UNAVAILABLE,
        INTEGRITY_CONFLICT
    }

    final class TransferPlan {
        private final UUID candidateEntityId;
        private final LastKnownEvidence sourceEvidence;
        private final NBTTagCompound sourceSnapshot;

        public TransferPlan(
                UUID candidateEntityId,
                LastKnownEvidence sourceEvidence,
                NBTTagCompound sourceSnapshot) {
            this.candidateEntityId = Objects.requireNonNull(candidateEntityId, "candidateEntityId");
            this.sourceEvidence = Objects.requireNonNull(sourceEvidence, "sourceEvidence");
            this.sourceSnapshot = Objects.requireNonNull(sourceSnapshot, "sourceSnapshot").copy();
        }

        public UUID getCandidateEntityId() { return candidateEntityId; }
        public LastKnownEvidence getSourceEvidence() { return sourceEvidence; }
        public NBTTagCompound copySourceSnapshot() { return sourceSnapshot.copy(); }
    }

    final class TransferEvidence {
        public enum Presence {
            EXACT,
            FINALIZED,
            MISSING,
            UNAVAILABLE,
            CONFLICT,
            NOT_INSPECTED
        }

        private final Presence source;
        private final Presence candidate;
        private final LastKnownEvidence actualSourceEvidence;
        private final LastKnownEvidence actualCandidateEvidence;

        public TransferEvidence(Presence source, Presence candidate) {
            this(source, candidate, null, null);
        }

        public TransferEvidence(
                Presence source,
                Presence candidate,
                LastKnownEvidence actualSourceEvidence,
                LastKnownEvidence actualCandidateEvidence) {
            this.source = Objects.requireNonNull(source, "source");
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.actualSourceEvidence = actualSourceEvidence;
            this.actualCandidateEvidence = actualCandidateEvidence;
        }

        public static TransferEvidence conflict() {
            return new TransferEvidence(Presence.CONFLICT, Presence.CONFLICT);
        }

        public Presence getSource() { return source; }
        public Presence getCandidate() { return candidate; }
        public Optional<LastKnownEvidence> getActualSourceEvidence() {
            return Optional.ofNullable(actualSourceEvidence);
        }
        public Optional<LastKnownEvidence> getActualCandidateEvidence() {
            return Optional.ofNullable(actualCandidateEvidence);
        }
        public boolean hasConflict() {
            return source == Presence.CONFLICT || candidate == Presence.CONFLICT;
        }
    }

    final class LocateResult {
        public enum Status {
            FOUND,
            MISSING,
            UNAVAILABLE,
            INTEGRITY_CONFLICT
        }

        private final Status status;
        private final Source source;

        private LocateResult(Status status, Source source) {
            this.status = status;
            this.source = source;
        }

        public static LocateResult found(Source source) {
            return new LocateResult(Status.FOUND, Objects.requireNonNull(source, "source"));
        }

        public static LocateResult missing() {
            return new LocateResult(Status.MISSING, null);
        }

        public static LocateResult unavailable() {
            return new LocateResult(Status.UNAVAILABLE, null);
        }

        public static LocateResult integrityConflict() {
            return new LocateResult(Status.INTEGRITY_CONFLICT, null);
        }

        public Status getStatus() {
            return status;
        }

        public Optional<Source> getSource() {
            return Optional.ofNullable(source);
        }
    }

    final class Source {
        private final UUID physicalEntityId;
        private final int dimensionId;
        private final boolean hasPassengers;
        private final Object implementationHandle;

        public Source(
                UUID physicalEntityId,
                int dimensionId,
                boolean hasPassengers,
                Object implementationHandle) {
            this.physicalEntityId = Objects.requireNonNull(physicalEntityId, "physicalEntityId");
            this.dimensionId = dimensionId;
            this.hasPassengers = hasPassengers;
            this.implementationHandle = implementationHandle;
        }

        public UUID getPhysicalEntityId() {
            return physicalEntityId;
        }

        public int getDimensionId() {
            return dimensionId;
        }

        public boolean hasPassengers() {
            return hasPassengers;
        }

        public Object getImplementationHandle() {
            return implementationHandle;
        }
    }

    final class Destination {
        private final LastKnownEvidence evidence;

        public Destination(LastKnownEvidence evidence) {
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        public LastKnownEvidence getEvidence() {
            return evidence;
        }
    }
}
