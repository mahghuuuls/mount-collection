package com.mahghuuuls.mountcollection.persistence;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

public final class MountRepository {

    @FunctionalInterface
    interface AcknowledgedPersistence {
        boolean commit(RepositorySnapshot expectedSnapshot);
    }

    @FunctionalInterface
    public interface ProviderPayloadVerifier {
        ProviderResult<ProviderPayload> verify(ResourceLocation providerId, ProviderPayload payload);
    }

    public enum RegistrationStatus {
        SUCCESS,
        READ_ONLY,
        DUPLICATE_ENTITY,
        OWNED_BY_OTHER,
        INTEGRITY_CONFLICT,
        COUNTER_EXHAUSTED
    }

    public enum ReconciliationStatus {
        UNTRACKED,
        VERIFIED,
        REATTACH_REQUIRED,
        INTEGRITY_CONFLICT
    }

    public enum RecallCommitStatus {
        SUCCESS,
        REJECTED
    }

    public enum TransferStatus {
        SUCCESS,
        REJECTED,
        INTEGRITY_CONFLICT,
        PERSISTENCE_FAILURE
    }

    public enum RecoveryStatus {
        SUCCESS,
        REJECTED,
        PERSISTENCE_FAILURE
    }

    public static final class RegistrationCandidate {
        private final UUID ownerId;
        private final ResourceLocation providerId;
        private final ResourceLocation entityTypeId;
        private final String fallbackTypeKey;
        private final UUID physicalEntityId;
        private final LastKnownEvidence lastKnown;
        private final MountId claimedMountId;
        private final MountCharacteristics characteristics;
        private final ProviderPayload providerPayload;

        public RegistrationCandidate(
                UUID ownerId,
                ResourceLocation providerId,
                ResourceLocation entityTypeId,
                String fallbackTypeKey,
                UUID physicalEntityId,
                LastKnownEvidence lastKnown,
                MountId claimedMountId) {
            this(ownerId, providerId, entityTypeId, fallbackTypeKey, physicalEntityId,
                    lastKnown, claimedMountId, MountCharacteristics.solidGround(),
                    new ProviderPayload(0, new NBTTagCompound()));
        }

        public RegistrationCandidate(
                UUID ownerId,
                ResourceLocation providerId,
                ResourceLocation entityTypeId,
                String fallbackTypeKey,
                UUID physicalEntityId,
                LastKnownEvidence lastKnown,
                MountId claimedMountId,
                ProviderPayload providerPayload) {
            this(ownerId, providerId, entityTypeId, fallbackTypeKey, physicalEntityId,
                    lastKnown, claimedMountId, MountCharacteristics.solidGround(), providerPayload);
        }

        public RegistrationCandidate(
                UUID ownerId,
                ResourceLocation providerId,
                ResourceLocation entityTypeId,
                String fallbackTypeKey,
                UUID physicalEntityId,
                LastKnownEvidence lastKnown,
                MountId claimedMountId,
                MountCharacteristics characteristics,
                ProviderPayload providerPayload) {
            this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
            this.fallbackTypeKey = Objects.requireNonNull(fallbackTypeKey, "fallbackTypeKey");
            this.physicalEntityId = Objects.requireNonNull(physicalEntityId, "physicalEntityId");
            this.lastKnown = Objects.requireNonNull(lastKnown, "lastKnown");
            this.claimedMountId = claimedMountId;
            this.characteristics = Objects.requireNonNull(characteristics, "characteristics");
            this.providerPayload = Objects.requireNonNull(providerPayload, "providerPayload");
        }
    }

    public static final class RegistrationResult {
        private final RegistrationStatus status;
        private final MountRecord record;

        private RegistrationResult(RegistrationStatus status, MountRecord record) {
            this.status = status;
            this.record = record;
        }

        static RegistrationResult success(MountRecord record) {
            return new RegistrationResult(RegistrationStatus.SUCCESS, record);
        }

        static RegistrationResult failure(RegistrationStatus status) {
            return new RegistrationResult(status, null);
        }

        public RegistrationStatus getStatus() {
            return status;
        }

        public Optional<MountRecord> getRecord() {
            return Optional.ofNullable(record);
        }
    }

    public static final class CollectionInspection {
        private final int count;
        private final MountId selectedMountId;
        private final long revision;

        private CollectionInspection(int count, MountId selectedMountId, long revision) {
            this.count = count;
            this.selectedMountId = selectedMountId;
            this.revision = revision;
        }

        public int getCount() { return count; }
        public Optional<MountId> getSelectedMountId() { return Optional.ofNullable(selectedMountId); }
        public long getRevision() { return revision; }
    }

    public static final class CooldownState {
        private final long deadline;
        private final long duration;

        private CooldownState(long deadline, long duration) {
            this.deadline = deadline;
            this.duration = duration;
        }

        public long getDeadline() { return deadline; }
        public long getDuration() { return duration; }
    }

    /** A one-use persistence finalizer obtained before the world is mutated. */
    public final class RecallCommit {
        private final MountId mountId;
        private final PlayerState player;
        private final MountRecord record;
        private boolean open = true;

        private RecallCommit(MountId mountId, PlayerState player, MountRecord record) {
            this.mountId = mountId;
            this.player = player;
            this.record = record;
        }

        public void complete(LastKnownEvidence evidence, long cooldownDeadline, long cooldownDuration) {
            Objects.requireNonNull(evidence, "evidence");
            synchronized (MountRepository.this) {
                if (!open) {
                    throw new IllegalStateException("recall commit is already closed");
                }
                // prepareRecall reserves a stable record on the logical server thread. Completion
                // deliberately has no policy revalidation that could reject an already-moved entity.
                records.put(mountId, record.withLastKnown(evidence));
                player.recallCooldownDeadline = cooldownDeadline;
                player.recallCooldownDuration = cooldownDuration;
                open = false;
                dirtyMarker.run();
            }
        }

        public void cancel() {
            synchronized (MountRepository.this) {
                open = false;
            }
        }
    }

    private final Map<MountId, MountRecord> records = new LinkedHashMap<>();
    private final Map<UUID, MountId> physicalIndex = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<MountId>> ownerIndex = new LinkedHashMap<>();
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final Map<UUID, TransferOperation> transfers = new LinkedHashMap<>();
    private final Map<UUID, RestorationOperation> restorations = new LinkedHashMap<>();
    private final List<NBTTagCompound> retainedMalformedRecords = new ArrayList<>();
    private final List<NBTTagCompound> retainedMalformedPlayers = new ArrayList<>();
    private final List<NBTTagCompound> retainedMalformedTransfers = new ArrayList<>();
    private final List<NBTTagCompound> retainedMalformedRestorations = new ArrayList<>();
    private final Runnable dirtyMarker;
    private AcknowledgedPersistence acknowledgedPersistence = ignored -> true;

    private long nextRegistrationOrder = 1L;
    private long activeTick;
    private long storeRevision;
    private boolean readOnly;

    public MountRepository() {
        this(() -> {});
    }

    MountRepository(Runnable dirtyMarker) {
        this.dirtyMarker = Objects.requireNonNull(dirtyMarker, "dirtyMarker");
    }

    synchronized void setAcknowledgedPersistence(AcknowledgedPersistence persistence) {
        this.acknowledgedPersistence = Objects.requireNonNull(persistence, "persistence");
    }

    public synchronized RegistrationResult register(RegistrationCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        RegistrationStatus preflight = preflightInternal(candidate);
        if (preflight != RegistrationStatus.SUCCESS) {
            return RegistrationResult.failure(preflight);
        }
        if (nextRegistrationOrder < 1L || nextRegistrationOrder == Long.MAX_VALUE) {
            return RegistrationResult.failure(RegistrationStatus.COUNTER_EXHAUSTED);
        }

        PlayerState player = players.computeIfAbsent(candidate.ownerId, ignored -> new PlayerState());
        int ordinal = player.peekNextOrdinal(candidate.fallbackTypeKey);
        if (ordinal == Integer.MAX_VALUE || player.revision == Long.MAX_VALUE) {
            return RegistrationResult.failure(RegistrationStatus.COUNTER_EXHAUSTED);
        }
        MountId mountId;
        do {
            mountId = MountId.create();
        } while (records.containsKey(mountId));

        MountRecord record = new MountRecord(
                mountId,
                candidate.ownerId,
                candidate.providerId,
                candidate.entityTypeId,
                candidate.fallbackTypeKey,
                ordinal,
                nextRegistrationOrder,
                candidate.physicalEntityId,
                candidate.lastKnown,
                MountCondition.LIVING,
                null,
                candidate.characteristics,
                candidate.providerPayload.getVersion(),
                candidate.providerPayload.copyData(),
                null);

        nextRegistrationOrder++;
        player.consumeOrdinal(candidate.fallbackTypeKey);
        player.selectedMountId = mountId;
        player.selectionIntegrityBlocked = false;
        player.revision++;
        records.put(mountId, record);
        physicalIndex.put(candidate.physicalEntityId, mountId);
        ownerIndex.computeIfAbsent(candidate.ownerId, ignored -> new LinkedHashSet<>()).add(mountId);
        dirtyMarker.run();
        return RegistrationResult.success(record);
    }

    public synchronized RegistrationStatus preflightRegistration(RegistrationCandidate candidate) {
        return preflightInternal(Objects.requireNonNull(candidate, "candidate"));
    }

    private RegistrationStatus preflightInternal(RegistrationCandidate candidate) {
        if (readOnly) {
            return RegistrationStatus.READ_ONLY;
        }
        MountId indexed = physicalIndex.get(candidate.physicalEntityId);
        if (indexed != null) {
            if (candidate.claimedMountId != null && !indexed.equals(candidate.claimedMountId)) {
                MountRecord indexedRecord = records.get(indexed);
                if (indexedRecord != null) {
                    blockIntegrity(indexedRecord, "physical entity carries a different Mount ID");
                }
                MountRecord claimedRecord = records.get(candidate.claimedMountId);
                if (claimedRecord != null) {
                    blockIntegrity(claimedRecord, "Mount ID claimed by a different physical entity");
                }
                return RegistrationStatus.INTEGRITY_CONFLICT;
            }
            MountRecord existing = records.get(indexed);
            return existing != null && existing.getOwnerId().equals(candidate.ownerId)
                    ? RegistrationStatus.DUPLICATE_ENTITY
                    : RegistrationStatus.OWNED_BY_OTHER;
        }
        if (candidate.claimedMountId != null) {
            MountRecord claimed = records.get(candidate.claimedMountId);
            if (claimed == null) {
                return RegistrationStatus.INTEGRITY_CONFLICT;
            }
            if (claimed.getCondition() != MountCondition.LIVING) {
                return RegistrationStatus.INTEGRITY_CONFLICT;
            }
            if (!candidate.physicalEntityId.equals(claimed.getPhysicalEntityId())) {
                blockIntegrity(claimed, "Mount ID claimed by a different physical entity");
                return RegistrationStatus.INTEGRITY_CONFLICT;
            }
            return claimed.getOwnerId().equals(candidate.ownerId)
                    ? RegistrationStatus.DUPLICATE_ENTITY
                    : RegistrationStatus.OWNED_BY_OTHER;
        }
        return RegistrationStatus.SUCCESS;
    }

    public synchronized Optional<MountRecord> find(MountId mountId) {
        return Optional.ofNullable(records.get(mountId));
    }

    public synchronized Optional<MountRecord> findByPhysicalEntity(UUID physicalEntityId) {
        MountId mountId = physicalIndex.get(physicalEntityId);
        return mountId == null ? Optional.empty() : Optional.ofNullable(records.get(mountId));
    }

    public synchronized List<MountRecord> getOwnedRecords(UUID ownerId) {
        Set<MountId> ids = ownerIndex.get(ownerId);
        if (ids == null) {
            return Collections.emptyList();
        }
        List<MountRecord> result = new ArrayList<>();
        for (MountId id : ids) {
            MountRecord record = records.get(id);
            if (record != null) {
                result.add(record);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized CollectionInspection inspectCollection(UUID ownerId) {
        PlayerState player = players.get(ownerId);
        int count = ownerIndex.containsKey(ownerId) ? ownerIndex.get(ownerId).size() : 0;
        return new CollectionInspection(
                count,
                player == null || player.selectionIntegrityBlocked ? null : player.selectedMountId,
                player == null ? 0L : player.revision);
    }

    public synchronized long getRecallCooldownDeadline(UUID ownerId) {
        PlayerState player = players.get(ownerId);
        return player == null ? 0L : player.recallCooldownDeadline;
    }

    public synchronized CooldownState getRecallCooldown(UUID ownerId) {
        PlayerState player = players.get(ownerId);
        return player == null
                ? new CooldownState(0L, 0L)
                : new CooldownState(player.recallCooldownDeadline, player.recallCooldownDuration);
    }

    public synchronized boolean normalizeRecallCooldown(
            UUID ownerId, long currentTick, long defaultMaximumDuration) {
        PlayerState player = players.get(ownerId);
        if (readOnly || player == null || currentTick < 0L || defaultMaximumDuration < 0L) {
            return false;
        }
        long bound = player.recallCooldownDuration > 0L
                ? player.recallCooldownDuration
                : defaultMaximumDuration;
        long rawRemaining = player.recallCooldownDeadline <= currentTick
                ? 0L
                : player.recallCooldownDeadline - currentTick;
        if (rawRemaining <= bound) {
            return false;
        }
        player.recallCooldownDeadline = bound > Long.MAX_VALUE - currentTick
                ? Long.MAX_VALUE
                : currentTick + bound;
        player.recallCooldownDuration = bound;
        dirtyMarker.run();
        return true;
    }

    public synchronized Optional<RecallCommit> prepareRecall(
            UUID ownerId, MountId mountId, UUID physicalEntityId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(mountId, "mountId");
        Objects.requireNonNull(physicalEntityId, "physicalEntityId");
        PlayerState player = players.get(ownerId);
        MountRecord record = records.get(mountId);
        if (readOnly
                || player == null
                || player.selectionIntegrityBlocked
                || !mountId.equals(player.selectedMountId)
                || record == null
                || record.getCondition() != MountCondition.LIVING
                || !ownerId.equals(record.getOwnerId())
                || !physicalEntityId.equals(record.getPhysicalEntityId())) {
            return Optional.empty();
        }
        return Optional.of(new RecallCommit(mountId, player, record));
    }

    public synchronized RecallCommitStatus commitRecall(
            UUID ownerId,
            MountId mountId,
            UUID physicalEntityId,
            LastKnownEvidence evidence,
            long cooldownDeadline) {
        return commitRecall(ownerId, mountId, physicalEntityId, evidence, cooldownDeadline, 0L);
    }

    public synchronized RecallCommitStatus commitRecall(
            UUID ownerId,
            MountId mountId,
            UUID physicalEntityId,
            LastKnownEvidence evidence,
            long cooldownDeadline,
            long cooldownDuration) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(mountId, "mountId");
        Objects.requireNonNull(physicalEntityId, "physicalEntityId");
        Objects.requireNonNull(evidence, "evidence");
        if (cooldownDeadline < 0L || cooldownDuration < 0L || readOnly) {
            return RecallCommitStatus.REJECTED;
        }
        PlayerState player = players.get(ownerId);
        MountRecord record = records.get(mountId);
        if (player == null
                || player.selectionIntegrityBlocked
                || !mountId.equals(player.selectedMountId)
                || record == null
                || record.getCondition() != MountCondition.LIVING
                || !ownerId.equals(record.getOwnerId())
                || !physicalEntityId.equals(record.getPhysicalEntityId())) {
            return RecallCommitStatus.REJECTED;
        }
        records.put(mountId, record.withLastKnown(evidence));
        player.recallCooldownDeadline = cooldownDeadline;
        player.recallCooldownDuration = cooldownDuration;
        dirtyMarker.run();
        return RecallCommitStatus.SUCCESS;
    }

    /**
     * Durably replaces the living physical association with one Recovery snapshot.
     * The caller must not suppress vanilla death unless this acknowledgement succeeds.
     */
    public synchronized RecoveryStatus enterRecovery(
            UUID ownerId,
            MountId mountId,
            UUID physicalEntityId,
            RecoveryState recoveryState) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(mountId, "mountId");
        Objects.requireNonNull(physicalEntityId, "physicalEntityId");
        Objects.requireNonNull(recoveryState, "recoveryState");
        PlayerState player = players.get(ownerId);
        MountRecord record = records.get(mountId);
        if (readOnly
                || player == null
                || player.selectionIntegrityBlocked
                || record == null
                || record.getCondition() != MountCondition.LIVING
                || !ownerId.equals(record.getOwnerId())
                || !physicalEntityId.equals(record.getPhysicalEntityId())
                || !physicalEntityId.equals(recoveryState.getSourceEntityId())
                || recoveryState.getDeadline()
                        > saturatedAdd(activeTick, recoveryState.getDuration())) {
            return RecoveryStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        physicalIndex.remove(physicalEntityId);
        records.put(mountId, record.enterRecovery(
                recoveryState, recoveryState.getDeadline() <= activeTick));
        player.pendingNotificationKey = recoveryState.getDeadline() <= activeTick
                ? "mountcollection.message.recovery_ready"
                : "mountcollection.message.recovery_started";
        return durableCheckpoint(before)
                ? RecoveryStatus.SUCCESS
                : RecoveryStatus.PERSISTENCE_FAILURE;
    }

    public synchronized RecoveryStatus markRecoveryReady(MountId mountId, long currentTick) {
        Objects.requireNonNull(mountId, "mountId");
        MountRecord record = records.get(mountId);
        if (readOnly || currentTick < 0L || record == null
                || record.getCondition() != MountCondition.RECOVERING
                || record.getRecoveryState() == null
                || record.getRecoveryState().getDeadline() > currentTick) {
            return RecoveryStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        records.put(mountId, record.recoveryReady());
        PlayerState player = players.get(record.getOwnerId());
        if (player != null) {
            player.pendingNotificationKey = "mountcollection.message.recovery_ready";
        }
        return durableCheckpoint(before)
                ? RecoveryStatus.SUCCESS
                : RecoveryStatus.PERSISTENCE_FAILURE;
    }

    public synchronized List<MountRecord> getRecoveringRecords() {
        List<MountRecord> result = new ArrayList<>();
        for (MountRecord record : records.values()) {
            if (record.getCondition() == MountCondition.RECOVERING) {
                result.add(record);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean isControlledRecoverySource(
            MountId mountId, UUID sourceEntityId) {
        MountRecord record = records.get(Objects.requireNonNull(mountId, "mountId"));
        return record != null
                && record.getRecoveryState() != null
                && (record.getCondition() == MountCondition.RECOVERING
                        || record.getCondition() == MountCondition.READY_FOR_RECALL
                        || record.getCondition() == MountCondition.OPERATION_IN_PROGRESS)
                && record.getRecoveryState().getSourceEntityId().equals(
                        Objects.requireNonNull(sourceEntityId, "sourceEntityId"));
    }

    /** Removes tracking while leaving the physical entity's ordinary death path untouched. */
    public synchronized boolean removeForNormalDeath(UUID physicalEntityId) {
        return removeForNormalDeath(
                physicalEntityId, "mountcollection.message.recovery_unavailable");
    }

    public synchronized boolean removeForNormalDeath(
            UUID physicalEntityId, String notificationKey) {
        Objects.requireNonNull(physicalEntityId, "physicalEntityId");
        Objects.requireNonNull(notificationKey, "notificationKey");
        MountId mountId = physicalIndex.get(physicalEntityId);
        MountRecord record = mountId == null ? null : records.get(mountId);
        if (readOnly || record == null
                || (record.getCondition() != MountCondition.LIVING
                        && record.getCondition() != MountCondition.PROVIDER_UNAVAILABLE)
                || !physicalEntityId.equals(record.getPhysicalEntityId())) {
            return false;
        }
        physicalIndex.remove(physicalEntityId);
        records.remove(mountId);
        Set<MountId> owned = ownerIndex.get(record.getOwnerId());
        if (owned != null) {
            owned.remove(mountId);
            if (owned.isEmpty()) {
                ownerIndex.remove(record.getOwnerId());
            }
        }
        PlayerState player = players.get(record.getOwnerId());
        if (player != null && mountId.equals(player.selectedMountId)) {
            player.selectedMountId = null;
            player.selectionIntegrityBlocked = false;
            if (player.revision != Long.MAX_VALUE) {
                player.revision++;
            }
        }
        if (player != null) {
            player.pendingNotificationKey = notificationKey;
        }
        dirtyMarker.run();
        return true;
    }

    public synchronized Optional<String> consumePendingNotification(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        PlayerState player = players.get(ownerId);
        if (player == null || player.pendingNotificationKey == null) {
            return Optional.empty();
        }
        String result = player.pendingNotificationKey;
        player.pendingNotificationKey = null;
        dirtyMarker.run();
        return Optional.of(result);
    }

    public synchronized java.util.List<UUID> getPendingNotificationOwners() {
        java.util.List<UUID> owners = new java.util.ArrayList<>();
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            if (entry.getValue().pendingNotificationKey != null) {
                owners.add(entry.getKey());
            }
        }
        return java.util.Collections.unmodifiableList(owners);
    }

    public synchronized TransferStatus beginRestoration(RestorationOperation operation) {
        Objects.requireNonNull(operation, "operation");
        MountRecord record = records.get(operation.getMountId());
        PlayerState player = players.get(operation.getOwnerId());
        if (readOnly || operation.getPhase() != RestorationPhase.PREPARED
                || restorations.containsKey(operation.getOperationId())
                || record == null || record.getCondition() != MountCondition.READY_FOR_RECALL
                || record.getRecoveryState() == null
                || player == null || player.selectionIntegrityBlocked
                || !operation.getMountId().equals(player.selectedMountId)
                || !operation.getOwnerId().equals(record.getOwnerId())
                || operation.getCooldownDeadline()
                        > saturatedAdd(activeTick, operation.getCooldownDuration())) {
            return TransferStatus.REJECTED;
        }
        if (findTransferByMount(operation.getMountId()).isPresent()
                || findRestorationByMount(operation.getMountId()).isPresent()) {
            return TransferStatus.INTEGRITY_CONFLICT;
        }
        RepositorySnapshot before = snapshot();
        restorations.put(operation.getOperationId(), operation);
        records.put(operation.getMountId(), record.restorationInProgress(null, null));
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized Optional<RestorationOperation> findRestoration(UUID operationId) {
        return Optional.ofNullable(restorations.get(operationId));
    }

    public synchronized Optional<RestorationOperation> findRestorationByMount(MountId mountId) {
        for (RestorationOperation operation : restorations.values()) {
            if (operation.getMountId().equals(mountId)) {
                return Optional.of(operation);
            }
        }
        return Optional.empty();
    }

    public synchronized List<RestorationOperation> getPendingRestorations() {
        return Collections.unmodifiableList(new ArrayList<>(restorations.values()));
    }

    public synchronized boolean isControlledRestorationCandidate(
            UUID operationId, MountId mountId, UUID candidateEntityId) {
        RestorationOperation operation = restorations.get(operationId);
        return operation != null
                && operation.getPhase() != RestorationPhase.INTEGRITY_BLOCKED
                && operation.getMountId().equals(mountId)
                && operation.getCandidateEntityId().equals(candidateEntityId);
    }

    public synchronized TransferStatus markRestorationSpawnIntent(UUID operationId) {
        return advanceRestoration(operationId, RestorationPhase.PREPARED,
                RestorationPhase.CANDIDATE_SPAWN_INTENT);
    }

    public synchronized TransferStatus relocateRestorationEvidence(
            UUID operationId, LastKnownEvidence evidence, boolean source) {
        Objects.requireNonNull(evidence, "evidence");
        RestorationOperation operation = restorations.get(operationId);
        MountRecord record = operation == null ? null : records.get(operation.getMountId());
        if (readOnly || record == null || record.getRecoveryState() == null
                || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || (source ? operation.getPhase() != RestorationPhase.PREPARED
                        : operation.getPhase() != RestorationPhase.CANDIDATE_SPAWNED
                                && operation.getPhase() != RestorationPhase.ASSOCIATED)) {
            return TransferStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        if (source) {
            records.put(record.getMountId(), record.withRecoverySourceEvidence(evidence));
        } else {
            restorations.put(operationId, operation.withEvidence(evidence));
            if (operation.getPhase() == RestorationPhase.ASSOCIATED) {
                records.put(record.getMountId(), record.withLastKnown(evidence));
            }
        }
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus relocateCapturedSource(MountId mountId, LastKnownEvidence evidence) {
        MountRecord record = records.get(mountId);
        if (readOnly || record == null || record.getRecoveryState() == null
                || record.getCondition() == MountCondition.INTEGRITY_BLOCKED) {
            return TransferStatus.REJECTED;
        }
        RestorationOperation operation = findRestorationByMount(mountId).orElse(null);
        if (operation != null && operation.getPhase() != RestorationPhase.PREPARED) {
            return TransferStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        records.put(mountId, record.withRecoverySourceEvidence(evidence));
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus markRestorationCandidateSpawned(UUID operationId) {
        return advanceRestoration(operationId, RestorationPhase.CANDIDATE_SPAWN_INTENT,
                RestorationPhase.CANDIDATE_SPAWNED);
    }

    public synchronized TransferStatus rollbackRestorationSpawnIntent(UUID operationId) {
        return advanceRestoration(operationId, RestorationPhase.CANDIDATE_SPAWN_INTENT,
                RestorationPhase.PREPARED);
    }

    /** Caller must have removed the exact candidate and durably verified its absence. */
    public synchronized TransferStatus cancelContainedRestoration(UUID operationId) {
        RestorationOperation operation = restorations.get(operationId);
        MountRecord record = operation == null ? null : records.get(operation.getMountId());
        if (readOnly || operation == null
                || operation.getPhase() != RestorationPhase.CANDIDATE_SPAWN_INTENT
                || record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || record.getRecoveryState() == null
                || physicalIndex.containsKey(operation.getCandidateEntityId())) {
            return TransferStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        // Retaining PREPARED here would let queued observations retry a reported failure.
        restorations.remove(operationId);
        records.put(operation.getMountId(), record.restorationCancelled());
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus associateRestorationCandidate(UUID operationId) {
        RestorationOperation operation = restorations.get(operationId);
        MountRecord record = operation == null ? null : records.get(operation.getMountId());
        if (readOnly || operation == null
                || operation.getPhase() != RestorationPhase.CANDIDATE_SPAWNED
                || record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || physicalIndex.containsKey(operation.getCandidateEntityId())) {
            return operation == null ? TransferStatus.REJECTED
                    : blockRestorationInternal(operation, "restoration association evidence changed");
        }
        RepositorySnapshot before = snapshot();
        physicalIndex.put(operation.getCandidateEntityId(), operation.getMountId());
        records.put(operation.getMountId(), record.restorationInProgress(
                operation.getCandidateEntityId(), operation.getDestinationEvidence()));
        restorations.put(operationId, operation.withPhase(RestorationPhase.ASSOCIATED));
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus finishRestoration(UUID operationId) {
        RestorationOperation operation = restorations.get(operationId);
        MountRecord record = operation == null ? null : records.get(operation.getMountId());
        PlayerState player = operation == null ? null : players.get(operation.getOwnerId());
        if (readOnly || operation == null || operation.getPhase() != RestorationPhase.ASSOCIATED
                || record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || record.getRecoveryState() == null || player == null
                || !operation.getCandidateEntityId().equals(record.getPhysicalEntityId())) {
            return operation == null ? TransferStatus.REJECTED
                    : blockRestorationInternal(operation, "restoration finalization evidence changed");
        }
        RepositorySnapshot before = snapshot();
        player.recallCooldownDeadline = operation.getCooldownDeadline();
        player.recallCooldownDuration = operation.getCooldownDuration();
        records.put(operation.getMountId(), record.restorationCompleted());
        restorations.remove(operationId);
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus blockRestoration(UUID operationId, String reason) {
        RestorationOperation operation = restorations.get(operationId);
        return operation == null || readOnly ? TransferStatus.REJECTED
                : blockRestorationInternal(operation, reason);
    }

    private TransferStatus advanceRestoration(
            UUID operationId, RestorationPhase expected, RestorationPhase next) {
        RestorationOperation operation = restorations.get(operationId);
        if (readOnly || operation == null || operation.getPhase() != expected) {
            return TransferStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        restorations.put(operationId, operation.withPhase(next));
        return durableCheckpoint(before) ? TransferStatus.SUCCESS : TransferStatus.PERSISTENCE_FAILURE;
    }

    private TransferStatus blockRestorationInternal(
            RestorationOperation operation, String reason) {
        RepositorySnapshot before = snapshot();
        restorations.put(operation.getOperationId(), operation.integrityBlocked(reason));
        MountRecord record = records.get(operation.getMountId());
        if (record != null) {
            blockIntegrity(record, reason);
        }
        return durableCheckpoint(before)
                ? TransferStatus.INTEGRITY_CONFLICT : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus beginTransfer(TransferOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (readOnly || operation.getPhase() != TransferPhase.PREPARED
                || transfers.containsKey(operation.getOperationId())) {
            return TransferStatus.REJECTED;
        }
        PlayerState player = players.get(operation.getOwnerId());
        MountRecord record = records.get(operation.getMountId());
        NBTTagCompound sourceSnapshot = operation.copySourceSnapshot();
        if (player == null || player.selectionIntegrityBlocked
                || !operation.getMountId().equals(player.selectedMountId)
                || record == null || record.getCondition() != MountCondition.LIVING
                || !operation.getOwnerId().equals(record.getOwnerId())
                || !operation.getSourceEntityId().equals(record.getPhysicalEntityId())
                || !sourceSnapshot.hasKey("id", 8)
                || !record.getEntityTypeId().toString().equals(sourceSnapshot.getString("id"))) {
            return TransferStatus.REJECTED;
        }
        for (TransferOperation pending : transfers.values()) {
            if (pending.getMountId().equals(operation.getMountId())) {
                return TransferStatus.INTEGRITY_CONFLICT;
            }
        }
        RepositorySnapshot before = snapshot();
        transfers.put(operation.getOperationId(), operation);
        records.put(operation.getMountId(), record.operationInProgress());
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized Optional<TransferOperation> findTransfer(UUID operationId) {
        return Optional.ofNullable(transfers.get(operationId));
    }

    public synchronized List<TransferOperation> getPendingTransfers() {
        return Collections.unmodifiableList(new ArrayList<>(transfers.values()));
    }

    public synchronized Optional<TransferOperation> findTransferByMount(MountId mountId) {
        for (TransferOperation operation : transfers.values()) {
            if (operation.getMountId().equals(mountId)) {
                return Optional.of(operation);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean isControlledTransferCandidate(
            UUID operationId, MountId mountId, UUID candidateEntityId) {
        TransferOperation operation = transfers.get(operationId);
        return operation != null
                && operation.getMountId().equals(mountId)
                && operation.getCandidateEntityId().equals(candidateEntityId)
                && operation.getPhase() != TransferPhase.INTEGRITY_BLOCKED;
    }

    public synchronized boolean isControlledTransferSource(MountId mountId, UUID sourceEntityId) {
        for (TransferOperation operation : transfers.values()) {
            if (operation.getPhase() != TransferPhase.INTEGRITY_BLOCKED
                    && operation.getMountId().equals(mountId)
                    && operation.getSourceEntityId().equals(sourceEntityId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized TransferStatus markCandidateSpawnIntent(UUID operationId) {
        return advanceTransfer(
                operationId, TransferPhase.PREPARED, TransferPhase.CANDIDATE_SPAWN_INTENT);
    }

    public synchronized TransferStatus markCandidateSpawned(UUID operationId) {
        return advanceTransfer(
                operationId,
                TransferPhase.CANDIDATE_SPAWN_INTENT,
                TransferPhase.CANDIDATE_SPAWNED);
    }

    public synchronized TransferStatus updateTransferEvidence(
            UUID operationId,
            LastKnownEvidence sourceEvidence,
            LastKnownEvidence destinationEvidence) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null
                || operation.getPhase() == TransferPhase.INTEGRITY_BLOCKED) {
            return TransferStatus.REJECTED;
        }
        TransferOperation replacement;
        try {
            replacement = operation.withEvidence(sourceEvidence, destinationEvidence);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return blockTransferInternal(operation, "relocated transfer evidence is invalid");
        }
        MountRecord record = records.get(operation.getMountId());
        if (record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS) {
            return blockTransferInternal(operation, "relocated transfer record is invalid");
        }
        RepositorySnapshot before = snapshot();
        transfers.put(operationId, replacement);
        LastKnownEvidence authoritativeEvidence =
                operation.getPhase() == TransferPhase.PREPARED
                                || operation.getPhase() == TransferPhase.CANDIDATE_SPAWN_INTENT
                                || operation.getPhase() == TransferPhase.CANDIDATE_SPAWNED
                        ? sourceEvidence
                        : destinationEvidence;
        records.put(operation.getMountId(), record.withLastKnown(authoritativeEvidence));
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus associateTransferCandidate(UUID operationId) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null || operation.getPhase() != TransferPhase.CANDIDATE_SPAWNED) {
            return TransferStatus.REJECTED;
        }
        MountRecord record = records.get(operation.getMountId());
        if (record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || !operation.getSourceEntityId().equals(record.getPhysicalEntityId())
                || physicalIndex.containsKey(operation.getCandidateEntityId())) {
            return blockTransferInternal(operation, "transfer association evidence changed");
        }
        RepositorySnapshot before = snapshot();
        physicalIndex.remove(operation.getSourceEntityId());
        physicalIndex.put(operation.getCandidateEntityId(), operation.getMountId());
        records.put(operation.getMountId(), record.operationInProgressWithPhysicalEntity(
                operation.getCandidateEntityId(), operation.getDestinationEvidence()));
        transfers.put(operationId, operation.withPhase(TransferPhase.ASSOCIATED));
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus markSourceRemovalIntent(UUID operationId) {
        return advanceTransfer(
                operationId, TransferPhase.ASSOCIATED, TransferPhase.SOURCE_REMOVAL_INTENT);
    }

    public synchronized TransferStatus markTransferSourceRemoved(UUID operationId) {
        return advanceTransfer(
                operationId,
                TransferPhase.SOURCE_REMOVAL_INTENT,
                TransferPhase.SOURCE_REMOVED);
    }

    public synchronized TransferStatus rollbackSourceRemovalIntent(UUID operationId) {
        return advanceTransfer(
                operationId, TransferPhase.SOURCE_REMOVAL_INTENT, TransferPhase.ASSOCIATED);
    }

    public synchronized TransferStatus rollbackAssociatedTransfer(UUID operationId) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null || operation.getPhase() != TransferPhase.ASSOCIATED) {
            return TransferStatus.REJECTED;
        }
        MountRecord record = records.get(operation.getMountId());
        if (record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || !operation.getCandidateEntityId().equals(record.getPhysicalEntityId())) {
            return blockTransferInternal(operation, "associated rollback evidence changed");
        }
        RepositorySnapshot before = snapshot();
        physicalIndex.remove(operation.getCandidateEntityId());
        physicalIndex.put(operation.getSourceEntityId(), operation.getMountId());
        records.put(operation.getMountId(), record
                .operationInProgressWithPhysicalEntity(
                        operation.getSourceEntityId(), operation.getSourceEvidence())
                .operationCompleted());
        transfers.remove(operationId);
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus finishTransfer(UUID operationId) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null || operation.getPhase() != TransferPhase.SOURCE_REMOVED) {
            return TransferStatus.REJECTED;
        }
        PlayerState player = players.get(operation.getOwnerId());
        MountRecord record = records.get(operation.getMountId());
        if (player == null || record == null
                || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || !operation.getCandidateEntityId().equals(record.getPhysicalEntityId())) {
            return blockTransferInternal(operation, "transfer finalization evidence changed");
        }
        RepositorySnapshot before = snapshot();
        player.recallCooldownDeadline = operation.getCooldownDeadline();
        player.recallCooldownDuration = operation.getCooldownDuration();
        records.put(operation.getMountId(), record.operationCompleted());
        transfers.remove(operationId);
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus cancelTransfer(UUID operationId) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null
                || (operation.getPhase() != TransferPhase.PREPARED
                        && operation.getPhase() != TransferPhase.CANDIDATE_SPAWN_INTENT)) {
            return TransferStatus.REJECTED;
        }
        MountRecord record = records.get(operation.getMountId());
        if (record == null || record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                || !operation.getSourceEntityId().equals(record.getPhysicalEntityId())) {
            return blockTransferInternal(operation, "transfer rollback evidence changed");
        }
        RepositorySnapshot before = snapshot();
        records.put(operation.getMountId(), record.operationCompleted());
        transfers.remove(operationId);
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    public synchronized TransferStatus blockTransfer(UUID operationId, String reason) {
        TransferOperation operation = transfers.get(operationId);
        if (operation == null || readOnly) {
            return TransferStatus.REJECTED;
        }
        return blockTransferInternal(operation, reason);
    }

    private TransferStatus advanceTransfer(
            UUID operationId, TransferPhase expected, TransferPhase next) {
        TransferOperation operation = transfers.get(operationId);
        if (readOnly || operation == null || operation.getPhase() != expected) {
            return TransferStatus.REJECTED;
        }
        RepositorySnapshot before = snapshot();
        transfers.put(operationId, operation.withPhase(next));
        return durableCheckpoint(before)
                ? TransferStatus.SUCCESS
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    private TransferStatus blockTransferInternal(TransferOperation operation, String reason) {
        RepositorySnapshot before = snapshot();
        transfers.put(operation.getOperationId(), operation.integrityBlocked(reason));
        MountRecord record = records.get(operation.getMountId());
        if (record != null) {
            blockIntegrity(record, reason);
        }
        return durableCheckpoint(before)
                ? TransferStatus.INTEGRITY_CONFLICT
                : TransferStatus.PERSISTENCE_FAILURE;
    }

    private boolean durableCheckpoint(RepositorySnapshot before) {
        if (storeRevision == Long.MAX_VALUE) {
            restoreAfterFailedCommit(before, storeRevision);
            return false;
        }
        long attemptedRevision = storeRevision + 1L;
        storeRevision = attemptedRevision;
        dirtyMarker.run();
        boolean committed;
        try {
            committed = acknowledgedPersistence.commit(snapshot());
        } catch (RuntimeException exception) {
            committed = false;
        }
        if (committed) {
            return true;
        }
        restoreAfterFailedCommit(before, attemptedRevision);
        return false;
    }

    private void restoreAfterFailedCommit(RepositorySnapshot before, long attemptedRevision) {
        restoreSnapshot(before);
        storeRevision = Math.max(before.storeRevision, attemptedRevision);
        rebuildIndexesAndQuarantineConflicts();
        dirtyMarker.run();
    }

    public synchronized int getTotalRecordCount() {
        return records.size();
    }

    public synchronized ReconciliationStatus reconcile(
            UUID physicalEntityId, MountId corroboratingMountId, LastKnownEvidence evidence) {
        MountId authoritativeId = physicalIndex.get(physicalEntityId);
        if (corroboratingMountId == null) {
            if (authoritativeId == null) {
                return ReconciliationStatus.UNTRACKED;
            }
            MountRecord record = records.get(authoritativeId);
            updateLastKnown(record, evidence);
            return ReconciliationStatus.REATTACH_REQUIRED;
        }
        MountRecord corroborated = records.get(corroboratingMountId);
        if (corroborated == null
                || authoritativeId == null
                || !authoritativeId.equals(corroboratingMountId)
                || !physicalEntityId.equals(corroborated.getPhysicalEntityId())) {
            if (corroborated != null) {
                blockIntegrity(corroborated, "conflicting physical entity evidence");
            }
            if (authoritativeId != null && !authoritativeId.equals(corroboratingMountId)) {
                MountRecord authoritative = records.get(authoritativeId);
                if (authoritative != null) {
                    blockIntegrity(authoritative, "physical entity carries conflicting Mount ID evidence");
                }
            }
            return ReconciliationStatus.INTEGRITY_CONFLICT;
        }
        updateLastKnown(corroborated, evidence);
        return ReconciliationStatus.VERIFIED;
    }

    public synchronized ReconciliationStatus reportMalformedEvidence(UUID physicalEntityId) {
        MountId authoritativeId = physicalIndex.get(physicalEntityId);
        if (authoritativeId == null) {
            return ReconciliationStatus.UNTRACKED;
        }
        MountRecord record = records.get(authoritativeId);
        blockIntegrity(record, "malformed physical entity evidence");
        return ReconciliationStatus.INTEGRITY_CONFLICT;
    }

    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    public synchronized void reconcileProviderPayloads(ProviderPayloadVerifier verifier) {
        Objects.requireNonNull(verifier, "verifier");
        if (readOnly) {
            return;
        }
        boolean changed = false;
        for (MountRecord record : new ArrayList<>(records.values())) {
            if (record.getCondition() != MountCondition.LIVING
                    && record.getCondition() != MountCondition.PROVIDER_UNAVAILABLE) {
                continue;
            }
            ProviderResult<ProviderPayload> result;
            try {
                result = verifier.verify(
                        record.getProviderId(),
                        new ProviderPayload(
                                record.getProviderPayloadVersion(), record.copyProviderPayload()));
            } catch (RuntimeException exception) {
                result = null;
            }
            MountRecord replacement;
            if (result == null || !result.isSuccess() || !result.getValue().isPresent()) {
                replacement = record.providerUnavailable("stored provider or payload is unavailable");
            } else {
                replacement = record.providerAvailable(result.getValue().get());
            }
            if (replacement.getCondition() != record.getCondition()
                    || replacement.getProviderPayloadVersion() != record.getProviderPayloadVersion()
                    || !replacement.copyProviderPayload().equals(record.copyProviderPayload())) {
                records.put(record.getMountId(), replacement);
                changed = true;
            }
        }
        if (changed) {
            rebuildIndexesAndQuarantineConflicts();
            dirtyMarker.run();
        }
    }

    public synchronized long getActiveTick() {
        return activeTick;
    }

    public synchronized long getStoreRevision() {
        return storeRevision;
    }

    public synchronized void updateActiveTick(long tick) {
        if (!readOnly && tick >= 0L && tick != activeTick) {
            activeTick = tick;
            dirtyMarker.run();
        }
    }

    public synchronized void rebaseActiveTime(long tick) {
        if (readOnly || tick < 0L) {
            return;
        }
        activeTick = tick;
        for (PlayerState player : players.values()) {
            player.recallCooldownDeadline = tick;
            player.recallCooldownDuration = 0L;
        }
        for (MountRecord record : new ArrayList<>(records.values())) {
            if (record.getCondition() == MountCondition.RECOVERING) {
                records.put(record.getMountId(), record.recoveryReady());
                PlayerState player = players.get(record.getOwnerId());
                if (player != null) {
                    player.pendingNotificationKey = "mountcollection.message.recovery_ready";
                }
            }
        }
        dirtyMarker.run();
    }

    synchronized RepositorySnapshot snapshot() {
        return new RepositorySnapshot(
                records,
                players,
                transfers,
                restorations,
                retainedMalformedRecords,
                retainedMalformedPlayers,
                retainedMalformedTransfers,
                retainedMalformedRestorations,
                nextRegistrationOrder,
                activeTick,
                storeRevision,
                readOnly);
    }

    synchronized void load(RepositorySnapshot snapshot) {
        restoreSnapshot(snapshot);
        rebuildIndexesAndQuarantineConflicts();
    }

    private void restoreSnapshot(RepositorySnapshot snapshot) {
        records.clear();
        records.putAll(snapshot.records);
        players.clear();
        players.putAll(snapshot.players);
        transfers.clear();
        transfers.putAll(snapshot.transfers);
        restorations.clear();
        restorations.putAll(snapshot.restorations);
        copyRaw(snapshot.retainedMalformedRecords, retainedMalformedRecords);
        copyRaw(snapshot.retainedMalformedPlayers, retainedMalformedPlayers);
        copyRaw(snapshot.retainedMalformedTransfers, retainedMalformedTransfers);
        copyRaw(snapshot.retainedMalformedRestorations, retainedMalformedRestorations);
        nextRegistrationOrder = snapshot.nextRegistrationOrder;
        activeTick = snapshot.activeTick;
        storeRevision = snapshot.storeRevision;
        readOnly = snapshot.readOnly;
    }

    private void rebuildIndexesAndQuarantineConflicts() {
        physicalIndex.clear();
        ownerIndex.clear();
        Map<Long, MountId> firstOrder = new LinkedHashMap<>();
        Map<String, MountId> firstOrdinal = new LinkedHashMap<>();
        for (MountRecord record : new ArrayList<>(records.values())) {
            ownerIndex.computeIfAbsent(record.getOwnerId(), ignored -> new LinkedHashSet<>())
                    .add(record.getMountId());
            PlayerState player = players.computeIfAbsent(record.getOwnerId(), ignored -> new PlayerState());
            player.ensureNextOrdinalAfter(record.getFallbackTypeKey(), record.getFallbackOrdinal());
            RecoveryState recovery = record.getRecoveryState();
            if (recovery != null
                    && (recovery.getDeadline() > saturatedAdd(activeTick, recovery.getDuration())
                            || (record.getCondition() == MountCondition.READY_FOR_RECALL
                                    && recovery.getDeadline() > activeTick))) {
                blockIntegrity(record, "persisted Recovery timing is inconsistent");
                continue;
            }
            if (!record.getCondition().retainsAuthoritativePhysicalAssociation()) {
                continue;
            }
            MountId priorOrder = firstOrder.putIfAbsent(
                    record.getRegistrationOrder(), record.getMountId());
            if (priorOrder != null) {
                blockIntegrity(records.get(priorOrder), "duplicate registration order");
                blockIntegrity(record, "duplicate registration order");
            }
            String ordinalKey = record.getOwnerId() + "\u0000"
                    + record.getFallbackTypeKey() + "\u0000" + record.getFallbackOrdinal();
            MountId priorOrdinal = firstOrdinal.putIfAbsent(ordinalKey, record.getMountId());
            if (priorOrdinal != null) {
                blockIntegrity(records.get(priorOrdinal), "duplicate owner/type fallback ordinal");
                blockIntegrity(record, "duplicate owner/type fallback ordinal");
            }
        }

        Map<UUID, MountId> firstPhysical = new LinkedHashMap<>();
        for (MountRecord record : new ArrayList<>(records.values())) {
            UUID physicalId = record.getPhysicalEntityId();
            if (physicalId == null
                    || !record.getCondition().retainsAuthoritativePhysicalAssociation()) {
                continue;
            }
            MountId previous = firstPhysical.putIfAbsent(physicalId, record.getMountId());
            if (previous == null) {
                physicalIndex.put(physicalId, record.getMountId());
            } else {
                MountRecord first = records.get(previous);
                records.put(previous, first.integrityBlocked("duplicate physical entity association"));
                records.put(record.getMountId(), record.integrityBlocked("duplicate physical entity association"));
                physicalIndex.remove(physicalId);
            }
        }
        for (TransferOperation operation : new ArrayList<>(transfers.values())) {
            MountRecord record = records.get(operation.getMountId());
            boolean beforeAssociation = operation.getPhase() == TransferPhase.PREPARED
                    || operation.getPhase() == TransferPhase.CANDIDATE_SPAWN_INTENT
                    || operation.getPhase() == TransferPhase.CANDIDATE_SPAWNED;
            UUID expectedPhysical = beforeAssociation
                    ? operation.getSourceEntityId()
                    : operation.getCandidateEntityId();
            MountId candidateOwner = physicalIndex.get(operation.getCandidateEntityId());
            if (operation.getPhase() == TransferPhase.INTEGRITY_BLOCKED) {
                if (record != null) {
                    records.put(record.getMountId(), record.integrityBlocked(
                            operation.getIntegrityReason() == null
                                    ? "persisted transfer is integrity-blocked"
                                    : operation.getIntegrityReason()));
                }
            } else if (record == null
                    || (record.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                            && record.getCondition() != MountCondition.LIVING)
                    || !operation.getOwnerId().equals(record.getOwnerId())
                    || !expectedPhysical.equals(record.getPhysicalEntityId())
                    || !expectedTransferEvidence(operation, beforeAssociation).equals(record.getLastKnown())
                    || !hasConsistentTransferSnapshot(operation, record)
                    || operation.getCooldownDeadline()
                            > saturatedAdd(activeTick, operation.getCooldownDuration())
                    || (beforeAssociation && candidateOwner != null)) {
                transfers.put(operation.getOperationId(), operation.integrityBlocked(
                        "persisted transfer association is inconsistent"));
                if (record != null) {
                    records.put(record.getMountId(), record.integrityBlocked(
                            "persisted transfer association is inconsistent"));
                    physicalIndex.remove(record.getPhysicalEntityId());
                }
            } else if (record.getCondition() == MountCondition.LIVING) {
                records.put(record.getMountId(), record.operationInProgress());
            }
        }
        for (RestorationOperation operation : new ArrayList<>(restorations.values())) {
            MountRecord record = records.get(operation.getMountId());
            boolean associated = operation.getPhase() == RestorationPhase.ASSOCIATED;
            boolean conflictsWithTransfer = findTransferByMount(operation.getMountId()).isPresent();
            boolean consistent = record != null
                    && record.getRecoveryState() != null
                    && operation.getOwnerId().equals(record.getOwnerId())
                    && operation.getCooldownDeadline()
                            <= saturatedAdd(activeTick, operation.getCooldownDuration())
                    && (associated
                            ? record.getCondition() == MountCondition.OPERATION_IN_PROGRESS
                                    && operation.getCandidateEntityId().equals(
                                            record.getPhysicalEntityId())
                                    && operation.getDestinationEvidence().equals(
                                            record.getLastKnown())
                            : record.getCondition() == MountCondition.OPERATION_IN_PROGRESS
                                    && record.getPhysicalEntityId() == null);
            if (operation.getPhase() == RestorationPhase.INTEGRITY_BLOCKED) {
                if (record != null) {
                    records.put(record.getMountId(), record.integrityBlocked(
                            operation.getIntegrityReason() == null
                                    ? "persisted restoration is integrity-blocked"
                                    : operation.getIntegrityReason()));
                    if (record.getPhysicalEntityId() != null) {
                        physicalIndex.remove(record.getPhysicalEntityId());
                    }
                }
            } else if (!consistent || conflictsWithTransfer) {
                restorations.put(operation.getOperationId(), operation.integrityBlocked(
                        "persisted restoration association is inconsistent"));
                if (record != null) {
                    records.put(record.getMountId(), record.integrityBlocked(
                            "persisted restoration association is inconsistent"));
                    if (record.getPhysicalEntityId() != null) {
                        physicalIndex.remove(record.getPhysicalEntityId());
                    }
                }
            }
        }
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            MountRecord selected = entry.getValue().selectedMountId == null
                    ? null
                    : records.get(entry.getValue().selectedMountId);
            entry.getValue().selectionIntegrityBlocked = selected == null
                    ? entry.getValue().selectedMountId != null
                    : !entry.getKey().equals(selected.getOwnerId())
                            || (selected.getCondition() != MountCondition.LIVING
                                    && selected.getCondition() != MountCondition.OPERATION_IN_PROGRESS
                                    && selected.getCondition() != MountCondition.RECOVERING
                                    && selected.getCondition() != MountCondition.READY_FOR_RECALL);
        }
    }

    private static LastKnownEvidence expectedTransferEvidence(
            TransferOperation operation, boolean beforeAssociation) {
        return beforeAssociation
                ? operation.getSourceEvidence()
                : operation.getDestinationEvidence();
    }

    private static boolean hasConsistentTransferSnapshot(
            TransferOperation operation, MountRecord record) {
        NBTTagCompound snapshot = operation.copySourceSnapshot();
        if (!snapshot.hasKey("id", 8)
                || !record.getEntityTypeId().toString().equals(snapshot.getString("id"))
                || !snapshot.hasUniqueId("UUID")) {
            return false;
        }
        try {
            return operation.getSourceEntityId().equals(snapshot.getUniqueId("UUID"));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private void updateLastKnown(MountRecord record, LastKnownEvidence evidence) {
        if (record != null && !evidence.equals(record.getLastKnown())) {
            records.put(record.getMountId(), record.withLastKnown(evidence));
            dirtyMarker.run();
        }
    }

    private void blockIntegrity(MountRecord record, String reason) {
        if (record.getCondition() != MountCondition.INTEGRITY_BLOCKED) {
            records.put(record.getMountId(), record.integrityBlocked(reason));
            if (record.getPhysicalEntityId() != null) {
                physicalIndex.remove(record.getPhysicalEntityId());
            }
            dirtyMarker.run();
        }
    }

    private static void copyRaw(List<NBTTagCompound> source, List<NBTTagCompound> destination) {
        destination.clear();
        for (NBTTagCompound raw : source) {
            destination.add(raw.copy());
        }
    }

    static final class PlayerState {
        MountId selectedMountId;
        long revision;
        long recallCooldownDeadline;
        long recallCooldownDuration;
        boolean selectionIntegrityBlocked;
        String pendingNotificationKey;
        final Map<String, Integer> nextOrdinals = new LinkedHashMap<>();

        private int peekNextOrdinal(String typeKey) {
            return nextOrdinals.getOrDefault(typeKey, 1);
        }

        private void consumeOrdinal(String typeKey) {
            nextOrdinals.put(typeKey, peekNextOrdinal(typeKey) + 1);
        }

        private void ensureNextOrdinalAfter(String typeKey, int usedOrdinal) {
            int required = usedOrdinal == Integer.MAX_VALUE ? Integer.MAX_VALUE : usedOrdinal + 1;
            nextOrdinals.put(typeKey, Math.max(peekNextOrdinal(typeKey), required));
        }
    }

    static final class RepositorySnapshot {
        final Map<MountId, MountRecord> records;
        final Map<UUID, PlayerState> players;
        final Map<UUID, TransferOperation> transfers;
        final Map<UUID, RestorationOperation> restorations;
        final List<NBTTagCompound> retainedMalformedRecords;
        final List<NBTTagCompound> retainedMalformedPlayers;
        final List<NBTTagCompound> retainedMalformedTransfers;
        final List<NBTTagCompound> retainedMalformedRestorations;
        final long nextRegistrationOrder;
        final long activeTick;
        final long storeRevision;
        final boolean readOnly;

        RepositorySnapshot(
                Map<MountId, MountRecord> records,
                Map<UUID, PlayerState> players,
                Map<UUID, TransferOperation> transfers,
                Map<UUID, RestorationOperation> restorations,
                List<NBTTagCompound> retainedMalformedRecords,
                List<NBTTagCompound> retainedMalformedPlayers,
                List<NBTTagCompound> retainedMalformedTransfers,
                List<NBTTagCompound> retainedMalformedRestorations,
                long nextRegistrationOrder,
                long activeTick,
                long storeRevision,
                boolean readOnly) {
            this.records = new LinkedHashMap<>(records);
            this.players = copyPlayers(players);
            this.transfers = new LinkedHashMap<>(transfers);
            this.restorations = new LinkedHashMap<>(restorations);
            this.retainedMalformedRecords = copyRaw(retainedMalformedRecords);
            this.retainedMalformedPlayers = copyRaw(retainedMalformedPlayers);
            this.retainedMalformedTransfers = copyRaw(retainedMalformedTransfers);
            this.retainedMalformedRestorations = copyRaw(retainedMalformedRestorations);
            this.nextRegistrationOrder = nextRegistrationOrder;
            this.activeTick = activeTick;
            this.storeRevision = storeRevision;
            this.readOnly = readOnly;
        }

        private static List<NBTTagCompound> copyRaw(List<NBTTagCompound> source) {
            List<NBTTagCompound> copy = new ArrayList<>();
            for (NBTTagCompound raw : source) {
                copy.add(raw.copy());
            }
            return copy;
        }

        private static Map<UUID, PlayerState> copyPlayers(Map<UUID, PlayerState> source) {
            Map<UUID, PlayerState> copy = new LinkedHashMap<>();
            for (Map.Entry<UUID, PlayerState> entry : source.entrySet()) {
                PlayerState player = new PlayerState();
                player.selectedMountId = entry.getValue().selectedMountId;
                player.revision = entry.getValue().revision;
                player.recallCooldownDeadline = entry.getValue().recallCooldownDeadline;
                player.recallCooldownDuration = entry.getValue().recallCooldownDuration;
                player.selectionIntegrityBlocked = entry.getValue().selectionIntegrityBlocked;
                player.pendingNotificationKey = entry.getValue().pendingNotificationKey;
                player.nextOrdinals.putAll(entry.getValue().nextOrdinals);
                copy.put(entry.getKey(), player);
            }
            return copy;
        }
    }
}
