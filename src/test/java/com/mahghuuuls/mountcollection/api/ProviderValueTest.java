package com.mahghuuuls.mountcollection.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ProviderValueTest {

    @Test
    void payloadDefensivelyCopiesProviderData() {
        NBTTagCompound source = new NBTTagCompound();
        source.setInteger("value", 1);
        ProviderPayload payload = new ProviderPayload(2, source);
        source.setInteger("value", 7);
        NBTTagCompound firstRead = payload.copyData();
        firstRead.setInteger("value", 9);

        assertEquals(2, payload.getVersion());
        assertEquals(1, payload.copyData().getInteger("value"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderPayload(-1, new NBTTagCompound()));
    }

    @Test
    void resultSeparatesSuccessFromBoundedFailure() {
        ProviderResult<String> success = ProviderResult.success("ok");
        ProviderResult<String> failure = ProviderResult.failure(ProviderFailure.INVALID_STATE);

        assertTrue(success.isSuccess());
        assertEquals("ok", success.getValue().get());
        assertFalse(failure.isSuccess());
        assertEquals(ProviderFailure.INVALID_STATE, failure.getFailure().get());
    }

    @Test
    void registrationProfileBoundsProviderTypeIdentity() {
        NBTTagCompound payloadData = new NBTTagCompound();
        payloadData.setString("kind", "horse");
        RegistrationProfile profile = new RegistrationProfile(
                new ResourceLocation("minecraft:horse"),
                "minecraft:horse",
                new ProviderPayload(3, payloadData));
        payloadData.setString("kind", "changed");

        assertEquals(new ResourceLocation("minecraft:horse"), profile.getEntityTypeId());
        assertEquals("minecraft:horse", profile.getFallbackTypeKey());
        assertEquals(3, profile.getProviderPayload().getVersion());
        assertEquals("horse", profile.getProviderPayload().copyData().getString("kind"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegistrationProfile(new ResourceLocation("minecraft:horse"), ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegistrationProfile(
                        new ResourceLocation("minecraft:horse"),
                        new String(new char[129]).replace('\0', 'x')));
    }

    @Test
    void characteristicsAreBoundedImmutableAndDefaultToGround() {
        EnumSet<MountTrait> source = EnumSet.of(MountTrait.FLYING);
        MountCharacteristics characteristics =
                new MountCharacteristics(PlacementProfile.WATER, source);
        source.clear();

        assertEquals(PlacementProfile.WATER, characteristics.getPlacementProfile());
        assertEquals(Collections.singleton(MountTrait.FLYING), characteristics.getTraits());
        assertThrows(UnsupportedOperationException.class,
                () -> characteristics.getTraits().clear());
        assertThrows(NullPointerException.class,
                () -> new MountCharacteristics(null, Collections.emptySet()));
        assertThrows(NullPointerException.class,
                () -> new MountCharacteristics(PlacementProfile.LAVA,
                        Collections.singleton(null)));

        RegistrationProfile defaults = new RegistrationProfile(
                new ResourceLocation("minecraft:horse"), "minecraft:horse");
        assertEquals(MountCharacteristics.solidGround(), defaults.getCharacteristics());
    }
}
