package com.mahghuuuls.mountcollection.persistence;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public final class MountSavedData extends WorldSavedData {

    public static final String DATA_NAME = "mountcollection_mounts";

    private final MountRepository repository = new MountRepository(this::markDirty);
    private NBTTagCompound preservedFutureRoot;

    public MountSavedData() {
        this(DATA_NAME);
    }

    public MountSavedData(String name) {
        super(name);
    }

    public static MountSavedData get(WorldServer overworld) {
        return get(overworld, () -> false);
    }

    public static MountSavedData get(
            WorldServer overworld, BooleanSupplier acknowledgementFault) {
        return get(overworld, acknowledgementFault, (stage, cause) -> { });
    }

    public static MountSavedData get(
            WorldServer overworld,
            BooleanSupplier acknowledgementFault,
            BiConsumer<String, String> failureReporter) {
        Objects.requireNonNull(acknowledgementFault, "acknowledgementFault");
        Objects.requireNonNull(failureReporter, "failureReporter");
        MapStorage storage = overworld.getPerWorldStorage();
        MountSavedData data = (MountSavedData) storage.getOrLoadData(MountSavedData.class, DATA_NAME);
        if (data == null) {
            data = new MountSavedData();
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }
        java.io.File dataFile = overworld.getSaveHandler().getMapFileFromName(DATA_NAME);
        if (dataFile != null) {
            AcknowledgedMountStore acknowledgedStore = new AcknowledgedMountStore(
                    dataFile,
                    data,
                    acknowledgementFault,
                    (stage, cause) -> failureReporter.accept(stage.name(), cause));
            data.repository.setAcknowledgedPersistence(acknowledgedStore::commit);
        } else {
            data.repository.setAcknowledgedPersistence(ignored -> false);
        }
        return data;
    }

    public MountRepository getRepository() {
        return repository;
    }

    @Override
    public void readFromNBT(NBTTagCompound root) {
        int version = MountStoreCodec.readRootVersion(root);
        if (version > MountStoreCodec.CURRENT_ROOT_VERSION
                || !MountStoreCodec.hasValidRootShape(root)) {
            preservedFutureRoot = root.copy();
            repository.load(new MountRepository.RepositorySnapshot(
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    1L,
                    0L,
                    0L,
                    true));
            return;
        }
        preservedFutureRoot = null;
        repository.load(MountStoreCodec.decodeKnown(root));
        if (version < MountStoreCodec.CURRENT_ROOT_VERSION) {
            markDirty();
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound root) {
        if (preservedFutureRoot != null) {
            return preservedFutureRoot.copy();
        }
        return MountStoreCodec.encode(repository.snapshot(), root);
    }
}
