package com.mahghuuuls.mountcollection.persistence;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

public final class MountRecord {

    public static final int CURRENT_VERSION = 1;

    private final MountId mountId;
    private final UUID ownerId;
    private final ResourceLocation providerId;
    private final ResourceLocation entityTypeId;
    private final String fallbackTypeKey;
    private final int fallbackOrdinal;
    private final long registrationOrder;
    private final UUID physicalEntityId;
    private final LastKnownEvidence lastKnown;
    private final MountCondition condition;
    private final String integrityReason;
    private final int providerPayloadVersion;
    private final NBTTagCompound providerPayload;
    private final NBTTagCompound preservedRaw;

    MountRecord(
            MountId mountId,
            UUID ownerId,
            ResourceLocation providerId,
            ResourceLocation entityTypeId,
            String fallbackTypeKey,
            int fallbackOrdinal,
            long registrationOrder,
            UUID physicalEntityId,
            LastKnownEvidence lastKnown,
            MountCondition condition,
            String integrityReason,
            int providerPayloadVersion,
            NBTTagCompound providerPayload,
            NBTTagCompound preservedRaw) {
        this.mountId = Objects.requireNonNull(mountId, "mountId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
        this.fallbackTypeKey = requireBounded(fallbackTypeKey, "fallbackTypeKey", 128);
        if (fallbackOrdinal < 1) {
            throw new IllegalArgumentException("fallbackOrdinal must be positive");
        }
        if (registrationOrder < 1L) {
            throw new IllegalArgumentException("registrationOrder must be positive");
        }
        this.fallbackOrdinal = fallbackOrdinal;
        this.registrationOrder = registrationOrder;
        this.physicalEntityId = physicalEntityId;
        this.lastKnown = lastKnown;
        this.condition = Objects.requireNonNull(condition, "condition");
        if (condition.retainsAuthoritativePhysicalAssociation()
                && (physicalEntityId == null || lastKnown == null)) {
            throw new IllegalArgumentException(
                    "living or provider-unavailable records require physical and last-known evidence");
        }
        this.integrityReason = boundedNullable(integrityReason, 160);
        if (providerPayloadVersion < 0) {
            throw new IllegalArgumentException("providerPayloadVersion must not be negative");
        }
        this.providerPayloadVersion = providerPayloadVersion;
        this.providerPayload = Objects.requireNonNull(providerPayload, "providerPayload").copy();
        this.preservedRaw = preservedRaw == null ? null : preservedRaw.copy();
    }

    public MountId getMountId() { return mountId; }
    public UUID getOwnerId() { return ownerId; }
    public ResourceLocation getProviderId() { return providerId; }
    public ResourceLocation getEntityTypeId() { return entityTypeId; }
    public String getFallbackTypeKey() { return fallbackTypeKey; }
    public int getFallbackOrdinal() { return fallbackOrdinal; }
    public long getRegistrationOrder() { return registrationOrder; }
    public UUID getPhysicalEntityId() { return physicalEntityId; }
    public LastKnownEvidence getLastKnown() { return lastKnown; }
    public MountCondition getCondition() { return condition; }
    public String getIntegrityReason() { return integrityReason; }
    public int getProviderPayloadVersion() { return providerPayloadVersion; }
    public ProviderPayload getProviderPayload() {
        return new ProviderPayload(providerPayloadVersion, providerPayload);
    }
    public NBTTagCompound copyProviderPayload() { return providerPayload.copy(); }
    NBTTagCompound copyPreservedRaw() { return preservedRaw == null ? null : preservedRaw.copy(); }

    MountRecord withLastKnown(LastKnownEvidence evidence) {
        return copy(condition, integrityReason, physicalEntityId, evidence);
    }

    MountRecord integrityBlocked(String reason) {
        return copy(MountCondition.INTEGRITY_BLOCKED, reason, physicalEntityId, lastKnown);
    }

    MountRecord providerUnavailable(String reason) {
        return copy(MountCondition.PROVIDER_UNAVAILABLE, reason, physicalEntityId, lastKnown);
    }

    MountRecord providerAvailable(ProviderPayload payload) {
        return new MountRecord(
                mountId, ownerId, providerId, entityTypeId, fallbackTypeKey,
                fallbackOrdinal, registrationOrder, physicalEntityId, lastKnown,
                MountCondition.LIVING, null, payload.getVersion(), payload.copyData(), preservedRaw);
    }

    private MountRecord copy(
            MountCondition nextCondition,
            String nextReason,
            UUID nextPhysicalId,
            LastKnownEvidence nextEvidence) {
        return new MountRecord(
                mountId, ownerId, providerId, entityTypeId, fallbackTypeKey,
                fallbackOrdinal, registrationOrder, nextPhysicalId, nextEvidence,
                nextCondition, nextReason, providerPayloadVersion, providerPayload, preservedRaw);
    }

    private static String requireBounded(String value, String name, int maximumLength) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty() || checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximumLength + " characters");
        }
        return checked;
    }

    private static String boundedNullable(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
