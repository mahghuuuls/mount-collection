package com.mahghuuuls.mountcollection.persistence;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.util.ResourceLocation;

/** Durable permission to clear corroboration on one original entity, never to delete it. */
public final class AbandonmentOperation {
    public static final int VERSION = 1;
    private final UUID operationId;
    private final MountId mountId;
    private final UUID ownerId;
    private final UUID physicalId;
    private final ResourceLocation providerId;
    private final ResourceLocation entityType;
    private final LastKnownEvidence location;

    public AbandonmentOperation(UUID operationId, MountId mountId, UUID ownerId,
            UUID physicalId, ResourceLocation providerId, ResourceLocation entityType,
            LastKnownEvidence location) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.mountId = Objects.requireNonNull(mountId, "mountId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.physicalId = Objects.requireNonNull(physicalId, "physicalId");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
        this.location = Objects.requireNonNull(location, "location");
    }
    public UUID getOperationId() { return operationId; }
    public MountId getMountId() { return mountId; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getPhysicalId() { return physicalId; }
    public ResourceLocation getProviderId() { return providerId; }
    public ResourceLocation getEntityType() { return entityType; }
    public LastKnownEvidence getLocation() { return location; }
    public boolean matches(MountRecord record) {
        return record != null && mountId.equals(record.getMountId())
                && ownerId.equals(record.getOwnerId()) && physicalId.equals(record.getPhysicalEntityId())
                && providerId.equals(record.getProviderId()) && entityType.equals(record.getEntityTypeId())
                && record.getRecoveryState() == null;
    }
    public AbandonmentOperation at(LastKnownEvidence evidence) {
        return new AbandonmentOperation(operationId, mountId, ownerId, physicalId, providerId, entityType, evidence);
    }
}
