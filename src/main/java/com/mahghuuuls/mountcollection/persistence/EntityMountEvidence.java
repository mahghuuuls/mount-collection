package com.mahghuuuls.mountcollection.persistence;

import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;

public final class EntityMountEvidence {

    private static final String ROOT_KEY = "MountCollection";
    private static final String VERSION_KEY = "Version";
    private static final String MOUNT_ID_KEY = "MountId";
    private static final String TRANSFER_OPERATION_ID_KEY = "TransferOperationId";
    private static final int CURRENT_VERSION = 1;

    public enum Status {
        NONE,
        VALID,
        MALFORMED
    }

    public static final class ReadResult {
        private final Status status;
        private final MountId mountId;

        private ReadResult(Status status, MountId mountId) {
            this.status = status;
            this.mountId = mountId;
        }

        public Status getStatus() {
            return status;
        }

        public Optional<MountId> getMountId() {
            return Optional.ofNullable(mountId);
        }
    }

    public static final class TransferReadResult {
        private final Status status;
        private final java.util.UUID operationId;

        private TransferReadResult(Status status, java.util.UUID operationId) {
            this.status = status;
            this.operationId = operationId;
        }

        public Status getStatus() {
            return status;
        }

        public Optional<java.util.UUID> getOperationId() {
            return Optional.ofNullable(operationId);
        }
    }

    private EntityMountEvidence() {}

    public static ReadResult read(Entity entity) {
        return readFrom(entity.getEntityData());
    }

    public static ReadResult readSavedEntity(NBTTagCompound savedEntity) {
        if (!savedEntity.hasKey("ForgeData")) {
            return new ReadResult(Status.NONE, null);
        }
        if (!savedEntity.hasKey("ForgeData", 10)) {
            return new ReadResult(Status.MALFORMED, null);
        }
        return readFrom(savedEntity.getCompoundTag("ForgeData"));
    }

    static ReadResult readFrom(NBTTagCompound entityData) {
        if (!entityData.hasKey(ROOT_KEY)) {
            return new ReadResult(Status.NONE, null);
        }
        if (!entityData.hasKey(ROOT_KEY, 10)) { return new ReadResult(Status.MALFORMED, null); }
        NBTTagCompound root = entityData.getCompoundTag(ROOT_KEY);
        if (!root.hasKey(VERSION_KEY, 3)
                || root.getInteger(VERSION_KEY) != CURRENT_VERSION
                || !root.hasKey(MOUNT_ID_KEY, 8)) {
            return new ReadResult(Status.MALFORMED, null);
        }
        try {
            return new ReadResult(Status.VALID, MountId.parse(root.getString(MOUNT_ID_KEY)));
        } catch (RuntimeException exception) {
            return new ReadResult(Status.MALFORMED, null);
        }
    }

    public static void attach(Entity entity, MountId mountId) {
        attachTo(entity.getEntityData(), mountId);
    }

    public static void attachTransferCandidate(
            Entity entity, MountId mountId, java.util.UUID operationId) {
        attachTo(entity.getEntityData(), mountId);
        NBTTagCompound root = entity.getEntityData().getCompoundTag(ROOT_KEY);
        root.setString(TRANSFER_OPERATION_ID_KEY, operationId.toString());
    }

    public static Optional<java.util.UUID> readTransferOperation(Entity entity) {
        return readTransfer(entity).getOperationId();
    }

    public static TransferReadResult readTransfer(Entity entity) {
        return readTransferFrom(entity.getEntityData());
    }

    public static TransferReadResult readSavedTransfer(NBTTagCompound savedEntity) {
        if (!savedEntity.hasKey("ForgeData")) {
            return new TransferReadResult(Status.NONE, null);
        }
        if (!savedEntity.hasKey("ForgeData", 10)) {
            return new TransferReadResult(Status.MALFORMED, null);
        }
        return readTransferFrom(savedEntity.getCompoundTag("ForgeData"));
    }

    private static TransferReadResult readTransferFrom(NBTTagCompound data) {
        if (!data.hasKey(ROOT_KEY, 10)) {
            return new TransferReadResult(Status.NONE, null);
        }
        NBTTagCompound root = data.getCompoundTag(ROOT_KEY);
        if (!root.hasKey(TRANSFER_OPERATION_ID_KEY)) {
            return new TransferReadResult(Status.NONE, null);
        }
        if (!root.hasKey(TRANSFER_OPERATION_ID_KEY, 8)) {
            return new TransferReadResult(Status.MALFORMED, null);
        }
        try {
            return new TransferReadResult(
                    Status.VALID,
                    java.util.UUID.fromString(root.getString(TRANSFER_OPERATION_ID_KEY)));
        } catch (RuntimeException exception) {
            return new TransferReadResult(Status.MALFORMED, null);
        }
    }

    public static void clearTransferOperation(Entity entity) {
        if (entity.getEntityData().hasKey(ROOT_KEY, 10)) {
            entity.getEntityData().getCompoundTag(ROOT_KEY).removeTag(TRANSFER_OPERATION_ID_KEY);
        }
    }

    /** The caller must first hold acknowledged abandonment authority for this exact entity. */
    public static void clearForAbandonment(Entity entity) {
        entity.getEntityData().removeTag(ROOT_KEY);
    }

    static void attachTo(NBTTagCompound entityData, MountId mountId) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(VERSION_KEY, CURRENT_VERSION);
        root.setString(MOUNT_ID_KEY, mountId.toString());
        entityData.setTag(ROOT_KEY, root);
    }
}
