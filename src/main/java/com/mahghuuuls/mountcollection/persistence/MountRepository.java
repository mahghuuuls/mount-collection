package com.mahghuuuls.mountcollection.persistence;

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

    public static final class RegistrationCandidate {
        private final UUID ownerId;
        private final ResourceLocation providerId;
        private final ResourceLocation entityTypeId;
        private final String fallbackTypeKey;
        private final UUID physicalEntityId;
        private final LastKnownEvidence lastKnown;
        private final MountId claimedMountId;
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
                    lastKnown, claimedMountId, new ProviderPayload(0, new NBTTagCompound()));
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
            this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
            this.fallbackTypeKey = Objects.requireNonNull(fallbackTypeKey, "fallbackTypeKey");
            this.physicalEntityId = Objects.requireNonNull(physicalEntityId, "physicalEntityId");
            this.lastKnown = Objects.requireNonNull(lastKnown, "lastKnown");
            this.claimedMountId = claimedMountId;
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
    private final List<NBTTagCompound> retainedMalformedRecords = new ArrayList<>();
    private final List<NBTTagCompound> retainedMalformedPlayers = new ArrayList<>();
    private final Runnable dirtyMarker;

    private long nextRegistrationOrder = 1L;
    private long activeTick;
    private boolean readOnly;

    public MountRepository() {
        this(() -> {});
    }

    MountRepository(Runnable dirtyMarker) {
        this.dirtyMarker = Objects.requireNonNull(dirtyMarker, "dirtyMarker");
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
        dirtyMarker.run();
    }

    synchronized RepositorySnapshot snapshot() {
        return new RepositorySnapshot(
                records,
                players,
                retainedMalformedRecords,
                retainedMalformedPlayers,
                nextRegistrationOrder,
                activeTick,
                readOnly);
    }

    synchronized void load(RepositorySnapshot snapshot) {
        records.clear();
        records.putAll(snapshot.records);
        players.clear();
        players.putAll(snapshot.players);
        copyRaw(snapshot.retainedMalformedRecords, retainedMalformedRecords);
        copyRaw(snapshot.retainedMalformedPlayers, retainedMalformedPlayers);
        nextRegistrationOrder = snapshot.nextRegistrationOrder;
        activeTick = snapshot.activeTick;
        readOnly = snapshot.readOnly;
        rebuildIndexesAndQuarantineConflicts();
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
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            MountRecord selected = entry.getValue().selectedMountId == null
                    ? null
                    : records.get(entry.getValue().selectedMountId);
            entry.getValue().selectionIntegrityBlocked = selected == null
                    ? entry.getValue().selectedMountId != null
                    : !entry.getKey().equals(selected.getOwnerId())
                            || !selected.getCondition().isOperational();
        }
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
        final List<NBTTagCompound> retainedMalformedRecords;
        final List<NBTTagCompound> retainedMalformedPlayers;
        final long nextRegistrationOrder;
        final long activeTick;
        final boolean readOnly;

        RepositorySnapshot(
                Map<MountId, MountRecord> records,
                Map<UUID, PlayerState> players,
                List<NBTTagCompound> retainedMalformedRecords,
                List<NBTTagCompound> retainedMalformedPlayers,
                long nextRegistrationOrder,
                long activeTick,
                boolean readOnly) {
            this.records = new LinkedHashMap<>(records);
            this.players = copyPlayers(players);
            this.retainedMalformedRecords = copyRaw(retainedMalformedRecords);
            this.retainedMalformedPlayers = copyRaw(retainedMalformedPlayers);
            this.nextRegistrationOrder = nextRegistrationOrder;
            this.activeTick = activeTick;
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
                player.nextOrdinals.putAll(entry.getValue().nextOrdinals);
                copy.put(entry.getKey(), player);
            }
            return copy;
        }
    }
}
