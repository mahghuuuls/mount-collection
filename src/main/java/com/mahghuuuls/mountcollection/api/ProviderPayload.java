package com.mahghuuuls.mountcollection.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Versioned opaque provider data. Core code must enforce serialized size bounds.
 */
public final class ProviderPayload {

    private static final int MAX_SERIALIZED_BYTES = 1_048_576;

    private final int version;
    private final NBTTagCompound data;

    public ProviderPayload(int version, NBTTagCompound data) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.data = requireBounded(Objects.requireNonNull(data, "data"));
    }

    public int getVersion() {
        return version;
    }

    public NBTTagCompound copyData() {
        return data.copy();
    }

    private static NBTTagCompound requireBounded(NBTTagCompound data) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(data, output);
            if (output.size() > MAX_SERIALIZED_BYTES) {
                throw new IllegalArgumentException("provider payload is too large");
            }
            return data.copy();
        } catch (IOException exception) {
            throw new IllegalArgumentException("provider payload could not be measured", exception);
        }
    }
}
