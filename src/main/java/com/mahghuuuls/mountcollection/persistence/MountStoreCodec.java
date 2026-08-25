package com.mahghuuuls.mountcollection.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

final class MountStoreCodec {

    static final int CURRENT_ROOT_VERSION = 1;
    private static final int COMPOUND_TAG = 10;
    private static final int MAX_KEY_LENGTH = 128;

    private MountStoreCodec() {}

    static int readRootVersion(NBTTagCompound root) {
        return root.hasKey("RootVersion") ? root.getInteger("RootVersion") : 0;
    }

    static boolean hasValidRootShape(NBTTagCompound root) {
        return hasNumericIfPresent(root, "RootVersion")
                && hasNumericIfPresent(root, "NextRegistrationOrder")
                && hasNumericIfPresent(root, "ActiveTick")
                && hasCompoundList(root, "Records")
                && hasCompoundList(root, "Players");
    }

    static MountRepository.RepositorySnapshot decodeKnown(NBTTagCompound root) {
        Map<MountId, MountRecord> records = new LinkedHashMap<>();
        Map<UUID, MountRepository.PlayerState> players = new LinkedHashMap<>();
        List<NBTTagCompound> retainedMalformedRecords = new ArrayList<>();
        List<NBTTagCompound> retainedMalformedPlayers = new ArrayList<>();

        NBTTagList recordList = root.getTagList("Records", COMPOUND_TAG);
        for (int index = 0; index < recordList.tagCount(); index++) {
            NBTTagCompound raw = recordList.getCompoundTagAt(index).copy();
            try {
                MountRecord record = decodeRecord(raw);
                if (records.putIfAbsent(record.getMountId(), record) != null) {
                    retainedMalformedRecords.add(raw);
                    MountRecord first = records.get(record.getMountId());
                    records.put(record.getMountId(), first.integrityBlocked("duplicate Mount ID in saved data"));
                }
            } catch (RuntimeException exception) {
                retainMalformedRecord(raw, records, retainedMalformedRecords);
            }
        }

        NBTTagList playerList = root.getTagList("Players", COMPOUND_TAG);
        for (int index = 0; index < playerList.tagCount(); index++) {
            try {
                NBTTagCompound raw = playerList.getCompoundTagAt(index);
                if (raw.hasKey("Version") && raw.getInteger("Version") > 1) {
                    throw new IllegalArgumentException("future player schema");
                }
                UUID ownerId = parseUuid(raw.getString("OwnerId"));
                MountRepository.PlayerState state = new MountRepository.PlayerState();
                state.revision = requireNonNegative(raw.getLong("Revision"), "revision");
                if (raw.hasKey("SelectedMountId")) {
                    state.selectedMountId = MountId.parse(raw.getString("SelectedMountId"));
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
        return new MountRepository.RepositorySnapshot(
                records,
                players,
                retainedMalformedRecords,
                retainedMalformedPlayers,
                nextOrder,
                activeTick,
                false);
    }

    static NBTTagCompound encode(MountRepository.RepositorySnapshot snapshot, NBTTagCompound root) {
        root.setInteger("RootVersion", CURRENT_ROOT_VERSION);
        root.setLong("NextRegistrationOrder", snapshot.nextRegistrationOrder);
        root.setLong("ActiveTick", snapshot.activeTick);

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
            player.setInteger("Version", 1);
            player.setString("OwnerId", entry.getKey().toString());
            if (entry.getValue().selectedMountId != null) {
                player.setString("SelectedMountId", entry.getValue().selectedMountId.toString());
            }
            player.setLong("Revision", entry.getValue().revision);
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
        return root;
    }

    private static MountRecord decodeRecord(NBTTagCompound raw) {
        int version = raw.hasKey("Version") ? raw.getInteger("Version") : 0;
        MountId mountId = MountId.parse(raw.getString("MountId"));
        UUID ownerId = parseUuid(raw.getString("OwnerId"));
        if (version > MountRecord.CURRENT_VERSION) {
            return retainedBlocked(mountId, ownerId, raw, "future record schema");
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
        return new MountRecord(
                mountId, ownerId, providerId, entityTypeId, typeKey, ordinal, order,
                physicalId, evidence, condition, reason, payloadVersion, payload, null);
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
        return raw;
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
        if (root.getTagId(key) != 9) {
            return false;
        }
        NBTTagList list = (NBTTagList) root.getTag(key);
        return list.tagCount() == 0 || list.getTagType() == COMPOUND_TAG;
    }

    private static boolean hasNumericIfPresent(NBTTagCompound root, String key) {
        return !root.hasKey(key) || root.hasKey(key, 99);
    }
}
