package com.mahghuuuls.mountcollection.api;

import java.util.Objects;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Versioned opaque provider data. Core code must enforce serialized size bounds.
 */
public final class ProviderPayload {

    private final int version;
    private final NBTTagCompound data;

    public ProviderPayload(int version, NBTTagCompound data) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.data = Objects.requireNonNull(data, "data").copy();
    }

    public int getVersion() {
        return version;
    }

    public NBTTagCompound copyData() {
        return data.copy();
    }
}
