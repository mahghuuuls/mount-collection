package com.mahghuuuls.mountcollection.persistence;

import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;

public final class EntityMountEvidence {

    private static final String ROOT_KEY = "MountCollection";
    private static final String VERSION_KEY = "Version";
    private static final String MOUNT_ID_KEY = "MountId";
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

    private EntityMountEvidence() {}

    public static ReadResult read(Entity entity) {
        return readFrom(entity.getEntityData());
    }

    static ReadResult readFrom(NBTTagCompound entityData) {
        if (!entityData.hasKey(ROOT_KEY, 10)) {
            return new ReadResult(Status.NONE, null);
        }
        NBTTagCompound root = entityData.getCompoundTag(ROOT_KEY);
        if (root.getInteger(VERSION_KEY) != CURRENT_VERSION || !root.hasKey(MOUNT_ID_KEY)) {
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

    static void attachTo(NBTTagCompound entityData, MountId mountId) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(VERSION_KEY, CURRENT_VERSION);
        root.setString(MOUNT_ID_KEY, mountId.toString());
        entityData.setTag(ROOT_KEY, root);
    }
}
