package com.mahghuuuls.mountcollection.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

final class ProviderPayloadTest {

    @Test
    void rejectsProviderDataBeyondTheCoreOwnedSerializedBound() {
        byte[] incompressible = new byte[1_100_000];
        new Random(7L).nextBytes(incompressible);
        NBTTagCompound data = new NBTTagCompound();
        data.setByteArray("payload", incompressible);

        assertThrows(IllegalArgumentException.class, () -> new ProviderPayload(1, data));
    }
}
