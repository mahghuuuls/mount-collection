package com.mahghuuuls.mountcollection.persistence;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.ResourceLocation;

final class MountStoreCodec {

    static final int CURRENT_ROOT_VERSION = 6;
    private static final int INT_TAG = 3;
    private static final int LONG_TAG = 4;
    private static final int DOUBLE_TAG = 6;
    private static final int STRING_TAG = 8;
    private static final int LIST_TAG = 9;
    private static final int COMPOUND_TAG = 10;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_TRANSFER_SNAPSHOT_BYTES = 1_048_576;
    private static final int MAX_RETAINED_MALFORMED_TRANSFERS = 128;

    private MountStoreCodec() {}

    static int readRootVersion(NBTTagCompound root) {
        return root.hasKey("RootVersion") ? root.getInteger("RootVersion") : 0;
    }

    static boolean hasValidRootShape(NBTTagCompound root) {
        int version = readRootVersion(root);
        if (version == CURRENT_ROOT_VERSION) {
            return root.hasKey("RootVersion", INT_TAG)
                    && root.hasKey("NextRegistrationOrder", LONG_TAG)
                    && root.hasKey("ActiveTick", LONG_TAG)
                    && root.hasKey("StoreRevision", LONG_TAG)
                    && hasRequiredCompoundList(root, "Records")
                    && hasRequiredCompoundList(root, "Players")
                    && hasRequiredCompoundList(root, "Transfers")
                    && hasRequiredCompoundList(root, "Restorations");
        }
        return hasNumericIfPresent(root, "RootVersion")
                && hasNumericIfPresent(root, "NextRegistrationOrder")
                && hasNumericIfPresent(root, "ActiveTick")
                && (!root.hasKey("StoreRevision") || root.hasKey("StoreRevision", LONG_TAG))
                && hasCompoundList(root, "Records")
                && hasCompoundList(root, "Players")
                && hasCompoundList(root, "Transfers");
    }

    static MountRepository.RepositorySnapshot decodeKnown(NBTTagCompound root) {
        int rootVersion = readRootVersion(root);
        Map<MountId, MountRecord> records = new LinkedHashMap<>();
        Map<UUID, MountRepository.PlayerState> players = new LinkedHashMap<>();
        Map<UUID, TransferOperation> transfers = new LinkedHashMap<>();
        Map<UUID, RestorationOperation> restorations = new LinkedHashMap<>();
        List<NBTTagCompound> retainedMalformedRecords = new ArrayList<>();
        List<NBTTagCompound> retainedMalformedPlayers = new ArrayList<>();
        List<NBTTagCompound> retainedMalformedTransfers = new ArrayList<>();
        List<NBTTagCompound> retainedMalformedRestorations = new ArrayList<>();

        NBTTagList recordList = root.getTagList("Records", COMPOUND_TAG);
        for (int index = 0; index < recordList.tagCount(); index++) {
            NBTTagCompound raw = recordList.getCompoundTagAt(index).copy();
            try {
                MountRecord record = decodeRecord(raw, rootVersion);
                if (records.putIfAbsent(record.getMountId(), record) != null) {
                    retainedMalformedRecords.add(raw);
                    MountRecord first = records.get(record.getMountId());
                    records.put(record.getMountId(), first.integrityBlocked("duplicate Mount ID in saved data"));
                }
            } catch (RuntimeException exception) {
                retainMalformedRecord(raw, records, retainedMalformedRecords);
            }
        }

        NBTTagList restorationList = root.getTagList("Restorations", COMPOUND_TAG);
        Map<MountId, UUID> restorationMounts = new LinkedHashMap<>();
        Map<UUID, NBTTagCompound> acceptedRestorationRaw = new LinkedHashMap<>();
        Set<UUID> quarantinedRestorationIds = new LinkedHashSet<>();
        Set<MountId> quarantinedRestorationMounts = new LinkedHashSet<>();
        for (int index = 0; index < restorationList.tagCount(); index++) {
            NBTTagCompound raw = restorationList.getCompoundTagAt(index).copy();
            try {
                RestorationOperation operation = decodeRestoration(raw);
                if (quarantinedRestorationIds.contains(operation.getOperationId())
                        || quarantinedRestorationMounts.contains(operation.getMountId())) {
                    retainBounded(raw, retainedMalformedRestorations);
                    blockTransferRecord(records, operation.getMountId(),
                            "duplicate persisted restoration operation");
                    continue;
                }
                RestorationOperation duplicateOperation =
                        restorations.get(operation.getOperationId());
                UUID duplicateMountOperation = restorationMounts.get(operation.getMountId());
                if (duplicateOperation != null || duplicateMountOperation != null) {
                    retainBounded(raw, retainedMalformedRestorations);
                    blockTransferRecord(records, operation.getMountId(),
                            "duplicate persisted restoration operation");
                    RestorationOperation first = duplicateOperation != null
                            ? duplicateOperation : restorations.get(duplicateMountOperation);
                    if (first != null) {
                        retainBounded(
                                acceptedRestorationRaw.get(first.getOperationId()),
                                retainedMalformedRestorations);
                        restorations.remove(first.getOperationId());
                        restorationMounts.remove(first.getMountId());
                        quarantinedRestorationIds.add(first.getOperationId());
                        quarantinedRestorationMounts.add(first.getMountId());
                        blockTransferRecord(records, first.getMountId(),
                                "duplicate persisted restoration operation");
                    }
                    quarantinedRestorationIds.add(operation.getOperationId());
                    quarantinedRestorationMounts.add(operation.getMountId());
                } else {
                    restorations.put(operation.getOperationId(), operation);
                    restorationMounts.put(operation.getMountId(), operation.getOperationId());
                    acceptedRestorationRaw.put(operation.getOperationId(), raw);
                }
            } catch (RuntimeException exception) {
                retainBounded(raw, retainedMalformedRestorations);
                try {
                    blockTransferRecord(records, MountId.parse(raw.getString("MountId")),
                            "malformed persisted restoration operation");
                } catch (RuntimeException ignored) {
                    // The raw entry is retained even when it cannot identify a known record.
                }
            }
        }

        NBTTagList transferList = root.getTagList("Transfers", COMPOUND_TAG);
        Map<MountId, UUID> transferMounts = new LinkedHashMap<>();
        Map<UUID, NBTTagCompound> acceptedTransferRaw = new LinkedHashMap<>();
        Set<UUID> quarantinedOperationIds = new LinkedHashSet<>();
        Set<MountId> quarantinedTransferMounts = new LinkedHashSet<>();
        for (int index = 0; index < transferList.tagCount(); index++) {
            NBTTagCompound raw = transferList.getCompoundTagAt(index).copy();
            try {
                TransferOperation operation = decodeTransfer(raw);
                if (quarantinedOperationIds.contains(operation.getOperationId())
                        || quarantinedTransferMounts.contains(operation.getMountId())) {
                    retainBounded(raw, retainedMalformedTransfers);
                    blockTransferRecord(records, operation.getMountId(),
                            "duplicate persisted transfer operation");
                    quarantinedOperationIds.add(operation.getOperationId());
                    quarantinedTransferMounts.add(operation.getMountId());
                    continue;
                }
                TransferOperation duplicateOperation = transfers.get(operation.getOperationId());
                UUID duplicateMountOperation = transferMounts.get(operation.getMountId());
                if (duplicateOperation != null || duplicateMountOperation != null) {
                    retainBounded(raw, retainedMalformedTransfers);
                    blockTransferRecord(records, operation.getMountId(),
                            "duplicate persisted transfer operation");
                    if (duplicateOperation != null) {
                        quarantinedOperationIds.add(duplicateOperation.getOperationId());
                        quarantinedTransferMounts.add(duplicateOperation.getMountId());
                        blockTransferRecord(records, duplicateOperation.getMountId(),
                                "duplicate persisted transfer operation");
                        retainBounded(
                                acceptedTransferRaw.get(duplicateOperation.getOperationId()),
                                retainedMalformedTransfers);
                        transfers.remove(duplicateOperation.getOperationId());
                        transferMounts.remove(duplicateOperation.getMountId());
                    }
                    else if (duplicateMountOperation != null) {
                        TransferOperation first = transfers.remove(duplicateMountOperation);
                        if (first != null) {
                            quarantinedOperationIds.add(first.getOperationId());
                            quarantinedTransferMounts.add(first.getMountId());
                            blockTransferRecord(records, first.getMountId(),
                                    "duplicate persisted transfer operation");
                            retainBounded(
                                    acceptedTransferRaw.get(first.getOperationId()),
                                    retainedMalformedTransfers);
                        }
                        transferMounts.remove(operation.getMountId());
                    }
                    quarantinedOperationIds.add(operation.getOperationId());
                    quarantinedTransferMounts.add(operation.getMountId());
                } else {
                    transfers.put(operation.getOperationId(), operation);
                    transferMounts.put(operation.getMountId(), operation.getOperationId());
                    acceptedTransferRaw.put(operation.getOperationId(), raw);
                }
            } catch (RuntimeException exception) {
                retainMalformedTransfer(
                        raw,
                        records,
                        transfers,
                        transferMounts,
                        acceptedTransferRaw,
                        quarantinedOperationIds,
                        quarantinedTransferMounts,
                        retainedMalformedTransfers);
            }
        }

        for (RestorationOperation restoration :
                new java.util.ArrayList<>(restorations.values())) {
            TransferOperation conflictingTransfer = null;
            for (TransferOperation transfer : transfers.values()) {
                if (transfer.getMountId().equals(restoration.getMountId())) {
                    conflictingTransfer = transfer;
                    break;
                }
            }
            if (conflictingTransfer != null) {
                retainBounded(
                        acceptedRestorationRaw.get(restoration.getOperationId()),
                        retainedMalformedRestorations);
                retainBounded(
                        acceptedTransferRaw.get(conflictingTransfer.getOperationId()),
                        retainedMalformedTransfers);
                restorations.remove(restoration.getOperationId());
                transfers.remove(conflictingTransfer.getOperationId());
                blockTransferRecord(records, restoration.getMountId(),
                        "conflicting persisted transfer and restoration operations");
            }
        }

        NBTTagList playerList = root.getTagList("Players", COMPOUND_TAG);
        for (int index = 0; index < playerList.tagCount(); index++) {
            try {
                NBTTagCompound raw = playerList.getCompoundTagAt(index);
                if (rootVersion == CURRENT_ROOT_VERSION) {
                    requireTag(raw, "Version", INT_TAG);
                    if (raw.getInteger("Version") != 3) {
                        throw new IllegalArgumentException("unsupported current player schema");
                    }
                } else if (raw.hasKey("Version")
                        && (!raw.hasKey("Version", INT_TAG)
                                || raw.getInteger("Version") > 3)) {
                    throw new IllegalArgumentException("future player schema");
                }
                UUID ownerId = parseUuid(raw.getString("OwnerId"));
                MountRepository.PlayerState state = new MountRepository.PlayerState();
                state.revision = requireNonNegative(raw.getLong("Revision"), "revision");
                state.recallCooldownDeadline = raw.hasKey("RecallCooldownDeadline")
                        ? requireNonNegative(
                                raw.getLong("RecallCooldownDeadline"), "recallCooldownDeadline")
                        : 0L;
                state.recallCooldownDuration = raw.hasKey("RecallCooldownDuration")
                        ? requireNonNegative(
                                raw.getLong("RecallCooldownDuration"), "recallCooldownDuration")
                        : 0L;
                if (raw.hasKey("SelectedMountId")) {
                    state.selectedMountId = MountId.parse(raw.getString("SelectedMountId"));
                }
                if (raw.hasKey("PendingNotification")) {
                    requireTag(raw, "PendingNotification", STRING_TAG);
                    state.pendingNotificationKey = requireBounded(
                            raw.getString("PendingNotification"), "pendingNotification");
                }
                if (raw.hasKey("Ordinals") && !hasCompoundList(raw, "Ordinals")) {
                    throw new IllegalArgumentException("malformed ordinal container");
                }
                NBTTagList ordinalList = raw.getTagList("Ordinals", COMPOUND_TAG);
                for (int ordinalIndex = 0; ordinalIndex < ordinalList.tagCount(); ordinalIndex++) {
                    NBTTagCompound ordinal = ordinalList.getCompoundTagAt(ordinalIndex);
                    String typeKey = requireBounded(ordinal.getString("TypeKey"), "typeKey");
                    int next = ordinal.getInteger("Next");
                    if (next < 1) {
                        throw new IllegalArgumentException("next ordinal must be positive");
                    }
                    state.nextOrdinals.put(typeKey, next);
                }
                MountRepository.PlayerState existing = players.putIfAbsent(ownerId, state);
                if (existing != null) {
                    existing.selectionIntegrityBlocked = true;
                    retainedMalformedPlayers.add(raw.copy());
                }
            } catch (RuntimeException exception) {
                retainedMalformedPlayers.add(playerList.getCompoundTagAt(index).copy());
            }
        }

        long nextOrder = root.hasKey("NextRegistrationOrder")
                ? root.getLong("NextRegistrationOrder")
                : deriveNextOrder(records);
        long derivedNextOrder = deriveNextOrder(records);
        if (nextOrder < 1L || nextOrder < derivedNextOrder) {
            nextOrder = derivedNextOrder;
        }
        long activeTick = root.hasKey("ActiveTick") ? root.getLong("ActiveTick") : 0L;
        long storeRevision = root.hasKey("StoreRevision")
                ? requireNonNegative(root.getLong("StoreRevision"), "storeRevision")
                : 0L;
        return new MountRepository.RepositorySnapshot(
                records,
                players,
                transfers,
                restorations,
                retainedMalformedRecords,
                retainedMalformedPlayers,
                retainedMalformedTransfers,
                retainedMalformedRestorations,
                nextOrder,
                activeTick,
                storeRevision,
                false);
    }

    static NBTTagCompound encode(MountRepository.RepositorySnapshot snapshot, NBTTagCompound root) {
        root.setInteger("RootVersion", CURRENT_ROOT_VERSION);
        root.setLong("NextRegistrationOrder", snapshot.nextRegistrationOrder);
        root.setLong("ActiveTick", snapshot.activeTick);
        root.setLong("StoreRevision", snapshot.storeRevision);

        NBTTagList records = new NBTTagList();
        for (MountRecord record : snapshot.records.values()) {
            NBTTagCompound preserved = record.copyPreservedRaw();
            records.appendTag(preserved == null ? encodeRecord(record) : preserved);
        }
        for (NBTTagCompound retained : snapshot.retainedMalformedRecords) {
            records.appendTag(retained.copy());
        }
        root.setTag("Records", records);

        NBTTagList players = new NBTTagList();
        for (Map.Entry<UUID, MountRepository.PlayerState> entry : snapshot.players.entrySet()) {
            NBTTagCompound player = new NBTTagCompound();
            player.setInteger("Version", 3);
            player.setString("OwnerId", entry.getKey().toString());
            if (entry.getValue().selectedMountId != null) {
                player.setString("SelectedMountId", entry.getValue().selectedMountId.toString());
            }
            player.setLong("Revision", entry.getValue().revision);
            player.setLong("RecallCooldownDeadline", entry.getValue().recallCooldownDeadline);
            player.setLong("RecallCooldownDuration", entry.getValue().recallCooldownDuration);
            if (entry.getValue().pendingNotificationKey != null) {
                player.setString("PendingNotification", entry.getValue().pendingNotificationKey);
            }
            NBTTagList ordinals = new NBTTagList();
            for (Map.Entry<String, Integer> ordinalEntry : entry.getValue().nextOrdinals.entrySet()) {
                NBTTagCompound ordinal = new NBTTagCompound();
                ordinal.setString("TypeKey", ordinalEntry.getKey());
                ordinal.setInteger("Next", ordinalEntry.getValue());
                ordinals.appendTag(ordinal);
            }
            player.setTag("Ordinals", ordinals);
            players.appendTag(player);
        }
        for (NBTTagCompound retained : snapshot.retainedMalformedPlayers) {
            players.appendTag(retained.copy());
        }
        root.setTag("Players", players);

        NBTTagList transfers = new NBTTagList();
        for (TransferOperation operation : snapshot.transfers.values()) {
            transfers.appendTag(encodeTransfer(operation));
        }
        for (NBTTagCompound retained : snapshot.retainedMalformedTransfers) {
            transfers.appendTag(retained.copy());
        }
        root.setTag("Transfers", transfers);

        NBTTagList restorations = new NBTTagList();
        for (RestorationOperation operation : snapshot.restorations.values()) {
            restorations.appendTag(encodeRestoration(operation));
        }
        for (NBTTagCompound retained : snapshot.retainedMalformedRestorations) {
            restorations.appendTag(retained.copy());
        }
        root.setTag("Restorations", restorations);
        return root;
    }

    private static TransferOperation decodeTransfer(NBTTagCompound raw) {
        requireTag(raw, "Version", INT_TAG);
        int version = raw.getInteger("Version");
        if (version != TransferOperation.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported transfer operation schema");
        }
        requireTag(raw, "OperationId", STRING_TAG);
        requireTag(raw, "MountId", STRING_TAG);
        requireTag(raw, "OwnerId", STRING_TAG);
        requireTag(raw, "SourceEntityId", STRING_TAG);
        requireTag(raw, "CandidateEntityId", STRING_TAG);
        requireTag(raw, "SourceSnapshot", COMPOUND_TAG);
        requireTag(raw, "SourceEvidence", COMPOUND_TAG);
        requireTag(raw, "DestinationEvidence", COMPOUND_TAG);
        requireTag(raw, "CooldownDeadline", LONG_TAG);
        requireTag(raw, "CooldownDuration", LONG_TAG);
        requireTag(raw, "Phase", STRING_TAG);
        NBTTagCompound snapshot = raw.getCompoundTag("SourceSnapshot");
        requireTag(snapshot, "id", STRING_TAG);
        requireBounded(snapshot.getString("id"), "source entity type");
        requireBoundedSnapshot(snapshot);
        TransferPhase phase = TransferPhase.valueOf(
                requireBounded(raw.getString("Phase"), "transfer phase"));
        String integrityReason = null;
        if (raw.hasKey("IntegrityReason")) {
            requireTag(raw, "IntegrityReason", STRING_TAG);
            integrityReason = requireBoundedLength(
                    raw.getString("IntegrityReason"), "integrity reason", 160);
        }
        if ((phase == TransferPhase.INTEGRITY_BLOCKED) != (integrityReason != null)) {
            throw new IllegalArgumentException("transfer integrity reason does not match phase");
        }
        return new TransferOperation(
                parseUuid(raw.getString("OperationId")),
                MountId.parse(raw.getString("MountId")),
                parseUuid(raw.getString("OwnerId")),
                parseUuid(raw.getString("SourceEntityId")),
                parseUuid(raw.getString("CandidateEntityId")),
                decodeEvidence(raw.getCompoundTag("SourceEvidence")),
                decodeEvidence(raw.getCompoundTag("DestinationEvidence")),
                snapshot,
                requireNonNegative(raw.getLong("CooldownDeadline"), "cooldownDeadline"),
                requireNonNegative(raw.getLong("CooldownDuration"), "cooldownDuration"),
                phase,
                integrityReason);
    }

    private static NBTTagCompound encodeTransfer(TransferOperation operation) {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("Version", TransferOperation.CURRENT_VERSION);
        raw.setString("OperationId", operation.getOperationId().toString());
        raw.setString("MountId", operation.getMountId().toString());
        raw.setString("OwnerId", operation.getOwnerId().toString());
        raw.setString("SourceEntityId", operation.getSourceEntityId().toString());
        raw.setString("CandidateEntityId", operation.getCandidateEntityId().toString());
        raw.setTag("SourceEvidence", encodeEvidence(operation.getSourceEvidence()));
        raw.setTag("DestinationEvidence", encodeEvidence(operation.getDestinationEvidence()));
        raw.setTag("SourceSnapshot", operation.copySourceSnapshot());
        raw.setLong("CooldownDeadline", operation.getCooldownDeadline());
        raw.setLong("CooldownDuration", operation.getCooldownDuration());
        raw.setString("Phase", operation.getPhase().name());
        if (operation.getIntegrityReason() != null) {
            raw.setString("IntegrityReason", operation.getIntegrityReason());
        }
        return raw;
    }

    private static RestorationOperation decodeRestoration(NBTTagCompound raw) {
        requireTag(raw, "Version", INT_TAG);
        requireTag(raw, "OperationId", STRING_TAG);
        requireTag(raw, "MountId", STRING_TAG);
        requireTag(raw, "OwnerId", STRING_TAG);
        requireTag(raw, "CandidateEntityId", STRING_TAG);
        requireTag(raw, "DestinationEvidence", COMPOUND_TAG);
        requireTag(raw, "CooldownDeadline", LONG_TAG);
        requireTag(raw, "CooldownDuration", LONG_TAG);
        requireTag(raw, "Phase", STRING_TAG);
        if (raw.getInteger("Version") != RestorationOperation.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported restoration schema");
        }
        RestorationPhase phase;
        try {
            phase = RestorationPhase.valueOf(raw.getString("Phase"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unknown restoration phase", exception);
        }
        String reason = raw.hasKey("IntegrityReason")
                ? requireBounded(raw.getString("IntegrityReason"), "integrityReason") : null;
        return new RestorationOperation(
                parseUuid(raw.getString("OperationId")),
                MountId.parse(raw.getString("MountId")),
                parseUuid(raw.getString("OwnerId")),
                parseUuid(raw.getString("CandidateEntityId")),
                decodeEvidence(raw.getCompoundTag("DestinationEvidence")),
                requireNonNegative(raw.getLong("CooldownDeadline"), "cooldownDeadline"),
                requireNonNegative(raw.getLong("CooldownDuration"), "cooldownDuration"),
                phase,
                reason);
    }

    private static NBTTagCompound encodeRestoration(RestorationOperation operation) {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("Version", RestorationOperation.CURRENT_VERSION);
        raw.setString("OperationId", operation.getOperationId().toString());
        raw.setString("MountId", operation.getMountId().toString());
        raw.setString("OwnerId", operation.getOwnerId().toString());
        raw.setString("CandidateEntityId", operation.getCandidateEntityId().toString());
        raw.setTag("DestinationEvidence", encodeEvidence(operation.getDestinationEvidence()));
        raw.setLong("CooldownDeadline", operation.getCooldownDeadline());
        raw.setLong("CooldownDuration", operation.getCooldownDuration());
        raw.setString("Phase", operation.getPhase().name());
        if (operation.getIntegrityReason() != null) {
            raw.setString("IntegrityReason", operation.getIntegrityReason());
        }
        return raw;
    }

    private static void retainMalformedTransfer(
            NBTTagCompound raw,
            Map<MountId, MountRecord> records,
            Map<UUID, TransferOperation> transfers,
            Map<MountId, UUID> transferMounts,
            Map<UUID, NBTTagCompound> acceptedTransferRaw,
            Set<UUID> quarantinedOperationIds,
            Set<MountId> quarantinedTransferMounts,
            List<NBTTagCompound> retained) {
        Set<MountId> implicated = new LinkedHashSet<>();
        UUID operationId = parseUuidIfPossible(raw, "OperationId");
        if (operationId != null) {
            quarantinedOperationIds.add(operationId);
            TransferOperation accepted = transfers.remove(operationId);
            if (accepted != null) {
                implicated.add(accepted.getMountId());
                transferMounts.remove(accepted.getMountId());
                retainBounded(acceptedTransferRaw.get(operationId), retained);
            }
        }
        try {
            MountId mountId = MountId.parse(raw.getString("MountId"));
            implicated.add(mountId);
        } catch (RuntimeException ignored) {
            // Other persisted identities can still implicate a known record below.
        }
        UUID sourceId = parseUuidIfPossible(raw, "SourceEntityId");
        UUID candidateId = parseUuidIfPossible(raw, "CandidateEntityId");
        UUID ownerId = parseUuidIfPossible(raw, "OwnerId");
        for (MountRecord record : records.values()) {
            if ((sourceId != null && sourceId.equals(record.getPhysicalEntityId()))
                    || (candidateId != null && candidateId.equals(record.getPhysicalEntityId()))
                    || (ownerId != null && ownerId.equals(record.getOwnerId()))) {
                implicated.add(record.getMountId());
            }
        }
        for (MountId mountId : implicated) {
            quarantinedTransferMounts.add(mountId);
            UUID acceptedOperationId = transferMounts.remove(mountId);
            if (acceptedOperationId != null) {
                TransferOperation accepted = transfers.remove(acceptedOperationId);
                quarantinedOperationIds.add(acceptedOperationId);
                if (accepted != null) {
                    quarantinedTransferMounts.add(accepted.getMountId());
                }
                retainBounded(acceptedTransferRaw.get(acceptedOperationId), retained);
            }
            blockTransferRecord(records, mountId,
                    "malformed persisted transfer operation");
        }
        retainBounded(raw, retained);
    }

    private static UUID parseUuidIfPossible(NBTTagCompound raw, String key) {
        if (!raw.hasKey(key, STRING_TAG)) {
            return null;
        }
        try {
            return parseUuid(raw.getString(key));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void blockTransferRecord(
            Map<MountId, MountRecord> records, MountId mountId, String reason) {
        MountRecord record = records.get(mountId);
        if (record != null) {
            records.put(mountId, record.integrityBlocked(reason));
        }
    }

    private static MountRecord decodeRecord(NBTTagCompound raw, int rootVersion) {
        requireTag(raw, "Version", INT_TAG);
        int version = raw.getInteger("Version");
        MountId mountId = MountId.parse(raw.getString("MountId"));
        UUID ownerId = parseUuid(raw.getString("OwnerId"));
        if (version > MountRecord.CURRENT_VERSION) {
            return retainedBlocked(mountId, ownerId, raw, "future record schema");
        }
        boolean legacyCharacteristics = rootVersion == 4 && version == 1;
        boolean characteristicsOnly = rootVersion == 5 && version == 2;
        boolean current = rootVersion == CURRENT_ROOT_VERSION
                && version == MountRecord.CURRENT_VERSION;
        if (!legacyCharacteristics && !characteristicsOnly && !current) {
            throw new IllegalArgumentException("unsupported record schema for root schema");
        }
        ResourceLocation providerId = parseId(raw.getString("ProviderId"));
        ResourceLocation entityTypeId = parseId(raw.getString("EntityTypeId"));
        String typeKey = requireBounded(raw.getString("FallbackTypeKey"), "fallbackTypeKey");
        int ordinal = raw.getInteger("FallbackOrdinal");
        long order = raw.getLong("RegistrationOrder");
        UUID physicalId = raw.hasKey("PhysicalEntityId")
                ? parseUuid(raw.getString("PhysicalEntityId"))
                : null;
        LastKnownEvidence evidence = raw.hasKey("LastKnown", COMPOUND_TAG)
                ? decodeEvidence(raw.getCompoundTag("LastKnown"))
                : null;
        MountCondition condition = MountCondition.valueOf(raw.getString("Condition"));
        String reason = raw.hasKey("IntegrityReason")
                ? boundedNullable(raw.getString("IntegrityReason"), 160)
                : null;
        int payloadVersion = raw.hasKey("ProviderPayloadVersion")
                ? raw.getInteger("ProviderPayloadVersion")
                : 0;
        if (payloadVersion < 0) {
            throw new IllegalArgumentException("negative provider payload version");
        }
        if (raw.hasKey("ProviderPayload") && !raw.hasKey("ProviderPayload", COMPOUND_TAG)) {
            throw new IllegalArgumentException("malformed provider payload container");
        }
        NBTTagCompound payload = raw.hasKey("ProviderPayload", COMPOUND_TAG)
                ? raw.getCompoundTag("ProviderPayload")
                : new NBTTagCompound();
        MountCharacteristics characteristics = legacyCharacteristics
                ? MountCharacteristics.solidGround()
                : decodeCharacteristics(raw);
        RecoveryState recoveryState = current ? decodeRecoveryState(raw, condition) : null;
        return new MountRecord(
                mountId, ownerId, providerId, entityTypeId, typeKey, ordinal, order,
                physicalId, evidence, condition, reason, characteristics,
                payloadVersion, payload, recoveryState, null);
    }

    private static NBTTagCompound encodeRecord(MountRecord record) {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("Version", MountRecord.CURRENT_VERSION);
        raw.setString("MountId", record.getMountId().toString());
        raw.setString("OwnerId", record.getOwnerId().toString());
        raw.setString("ProviderId", record.getProviderId().toString());
        raw.setString("EntityTypeId", record.getEntityTypeId().toString());
        raw.setString("FallbackTypeKey", record.getFallbackTypeKey());
        raw.setInteger("FallbackOrdinal", record.getFallbackOrdinal());
        raw.setLong("RegistrationOrder", record.getRegistrationOrder());
        if (record.getPhysicalEntityId() != null) {
            raw.setString("PhysicalEntityId", record.getPhysicalEntityId().toString());
        }
        if (record.getLastKnown() != null) {
            raw.setTag("LastKnown", encodeEvidence(record.getLastKnown()));
        }
        raw.setString("Condition", record.getCondition().name());
        if (record.getIntegrityReason() != null) {
            raw.setString("IntegrityReason", record.getIntegrityReason());
        }
        raw.setInteger("ProviderPayloadVersion", record.getProviderPayloadVersion());
        raw.setTag("ProviderPayload", record.copyProviderPayload());
        raw.setString("PlacementProfile", record.getCharacteristics().getPlacementProfile().name());
        NBTTagList traits = new NBTTagList();
        for (MountTrait trait : record.getCharacteristics().getTraits()) {
            traits.appendTag(new NBTTagString(trait.name()));
        }
        raw.setTag("MountTraits", traits);
        if (record.getRecoveryState() != null) {
            raw.setTag("Recovery", encodeRecoveryState(record.getRecoveryState()));
        }
        return raw;
    }

    private static RecoveryState decodeRecoveryState(
            NBTTagCompound raw, MountCondition condition) {
        boolean required = condition == MountCondition.RECOVERING
                || condition == MountCondition.READY_FOR_RECALL;
        boolean allowed = required
                || condition == MountCondition.OPERATION_IN_PROGRESS
                || condition == MountCondition.INTEGRITY_BLOCKED;
        if (!raw.hasKey("Recovery")) {
            if (required) {
                throw new IllegalArgumentException("Recovery state is missing");
            }
            return null;
        }
        requireTag(raw, "Recovery", COMPOUND_TAG);
        if (!allowed) {
            throw new IllegalArgumentException("unexpected Recovery state");
        }
        NBTTagCompound recovery = raw.getCompoundTag("Recovery");
        requireTag(recovery, "Version", INT_TAG);
        requireTag(recovery, "SourceEntityId", STRING_TAG);
        requireTag(recovery, "SourceEvidence", COMPOUND_TAG);
        requireTag(recovery, "ProviderPayloadVersion", INT_TAG);
        requireTag(recovery, "ProviderPayload", COMPOUND_TAG);
        requireTag(recovery, "Deadline", LONG_TAG);
        requireTag(recovery, "Duration", LONG_TAG);
        if (recovery.getInteger("Version") != RecoveryState.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported Recovery state schema");
        }
        int payloadVersion = recovery.getInteger("ProviderPayloadVersion");
        if (payloadVersion < 0) {
            throw new IllegalArgumentException("negative Recovery provider payload version");
        }
        NBTTagCompound payload = recovery.getCompoundTag("ProviderPayload");
        requireBoundedSnapshot(payload, "Recovery provider payload");
        return new RecoveryState(
                parseUuid(recovery.getString("SourceEntityId")),
                decodeEvidence(recovery.getCompoundTag("SourceEvidence")),
                new com.mahghuuuls.mountcollection.api.ProviderPayload(payloadVersion, payload),
                requireNonNegative(recovery.getLong("Deadline"), "Recovery deadline"),
                requireNonNegative(recovery.getLong("Duration"), "Recovery duration"));
    }

    private static NBTTagCompound encodeRecoveryState(RecoveryState state) {
        NBTTagCompound recovery = new NBTTagCompound();
        recovery.setInteger("Version", RecoveryState.CURRENT_VERSION);
        recovery.setString("SourceEntityId", state.getSourceEntityId().toString());
        recovery.setTag("SourceEvidence", encodeEvidence(state.getSourceEvidence()));
        com.mahghuuuls.mountcollection.api.ProviderPayload payload = state.getProviderPayload();
        recovery.setInteger("ProviderPayloadVersion", payload.getVersion());
        recovery.setTag("ProviderPayload", payload.copyData());
        recovery.setLong("Deadline", state.getDeadline());
        recovery.setLong("Duration", state.getDuration());
        return recovery;
    }

    private static MountCharacteristics decodeCharacteristics(NBTTagCompound raw) {
        requireTag(raw, "PlacementProfile", STRING_TAG);
        requireTag(raw, "MountTraits", LIST_TAG);
        PlacementProfile profile = PlacementProfile.valueOf(raw.getString("PlacementProfile"));
        NBTTagList rawTraits = (NBTTagList) raw.getTag("MountTraits");
        if (rawTraits.tagCount() > MountTrait.values().length
                || rawTraits.tagCount() > 0 && rawTraits.getTagType() != STRING_TAG) {
            throw new IllegalArgumentException("malformed mount traits");
        }
        Set<MountTrait> traits = new LinkedHashSet<>();
        for (int index = 0; index < rawTraits.tagCount(); index++) {
            MountTrait trait = MountTrait.valueOf(rawTraits.getStringTagAt(index));
            if (!traits.add(trait)) {
                throw new IllegalArgumentException("duplicate mount trait");
            }
        }
        return new MountCharacteristics(profile, traits);
    }

    private static MountRecord retainedBlocked(
            MountId mountId, UUID ownerId, NBTTagCompound raw, String reason) {
        return new MountRecord(
                mountId,
                ownerId,
                new ResourceLocation("mountcollection", "unavailable"),
                new ResourceLocation("mountcollection", "unavailable"),
                "mountcollection:unavailable",
                Math.max(1, raw.getInteger("FallbackOrdinal")),
                Math.max(1L, raw.getLong("RegistrationOrder")),
                null,
                null,
                MountCondition.INTEGRITY_BLOCKED,
                reason,
                MountCharacteristics.solidGround(),
                0,
                new NBTTagCompound(),
                raw);
    }

    private static void retainMalformedRecord(
            NBTTagCompound raw,
            Map<MountId, MountRecord> records,
            List<NBTTagCompound> retainedMalformedRecords) {
        try {
            MountId mountId = MountId.parse(raw.getString("MountId"));
            UUID ownerId = parseUuid(raw.getString("OwnerId"));
            if (!records.containsKey(mountId)) {
                records.put(mountId, retainedBlocked(mountId, ownerId, raw, "malformed record"));
                return;
            }
            MountRecord first = records.get(mountId);
            records.put(mountId, first.integrityBlocked("duplicate Mount ID in saved data"));
        } catch (RuntimeException ignored) {
            // The raw record is retained below when it cannot be safely indexed.
        }
        retainedMalformedRecords.add(raw.copy());
    }

    private static LastKnownEvidence decodeEvidence(NBTTagCompound raw) {
        requireTag(raw, "Dimension", INT_TAG);
        requireTag(raw, "X", DOUBLE_TAG);
        requireTag(raw, "Y", DOUBLE_TAG);
        requireTag(raw, "Z", DOUBLE_TAG);
        return new LastKnownEvidence(
                raw.getInteger("Dimension"), raw.getDouble("X"), raw.getDouble("Y"), raw.getDouble("Z"));
    }

    private static NBTTagCompound encodeEvidence(LastKnownEvidence evidence) {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setInteger("Dimension", evidence.getDimensionId());
        raw.setDouble("X", evidence.getX());
        raw.setDouble("Y", evidence.getY());
        raw.setDouble("Z", evidence.getZ());
        return raw;
    }

    private static long deriveNextOrder(Map<MountId, MountRecord> records) {
        long maximum = 0L;
        for (MountRecord record : records.values()) {
            maximum = Math.max(maximum, record.getRegistrationOrder());
        }
        return maximum == Long.MAX_VALUE ? Long.MAX_VALUE : maximum + 1L;
    }

    private static ResourceLocation parseId(String value) {
        return new ResourceLocation(requireBounded(value, "resource ID"));
    }

    private static UUID parseUuid(String value) {
        return UUID.fromString(requireBounded(value, "UUID"));
    }

    private static String requireBounded(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(name + " is missing or oversized");
        }
        return value;
    }

    private static String boundedNullable(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String requireBoundedLength(
            String value, String name, int maximumLength) {
        if (value == null || value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is missing or oversized");
        }
        return value;
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static boolean hasCompoundList(NBTTagCompound root, String key) {
        if (!root.hasKey(key)) {
            return true;
        }
        if (root.getTagId(key) != LIST_TAG) {
            return false;
        }
        NBTTagList list = (NBTTagList) root.getTag(key);
        return list.tagCount() == 0 || list.getTagType() == COMPOUND_TAG;
    }

    private static boolean hasRequiredCompoundList(NBTTagCompound root, String key) {
        return root.hasKey(key) && hasCompoundList(root, key);
    }

    private static boolean hasNumericIfPresent(NBTTagCompound root, String key) {
        return !root.hasKey(key) || root.hasKey(key, 99);
    }

    private static void requireTag(NBTTagCompound root, String key, int type) {
        if (!root.hasKey(key, type)) {
            throw new IllegalArgumentException("missing or wrongly typed " + key);
        }
    }

    private static void requireBoundedSnapshot(NBTTagCompound snapshot) {
        requireBoundedSnapshot(snapshot, "transfer snapshot");
    }

    private static void requireBoundedSnapshot(NBTTagCompound snapshot, String label) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(snapshot, output);
            if (output.size() > MAX_TRANSFER_SNAPSHOT_BYTES) {
                throw new IllegalArgumentException(label + " is oversized");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " could not be measured", exception);
        }
    }

    private static void retainBounded(
            NBTTagCompound raw, List<NBTTagCompound> retained) {
        if (raw != null && retained.size() < MAX_RETAINED_MALFORMED_TRANSFERS) {
            retained.add(raw.copy());
        }
    }
}
