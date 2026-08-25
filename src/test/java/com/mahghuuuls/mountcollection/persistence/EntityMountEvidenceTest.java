package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

final class EntityMountEvidenceTest {

    @Test
    void evidenceRoundTripUsesVersionedMountIdEnvelope() {
        NBTTagCompound entityData = new NBTTagCompound();
        MountId mountId = MountId.create();

        EntityMountEvidence.attachTo(entityData, mountId);
        EntityMountEvidence.ReadResult result = EntityMountEvidence.readFrom(entityData);

        assertEquals(EntityMountEvidence.Status.VALID, result.getStatus());
        assertEquals(mountId, result.getMountId().get());
    }

    @Test
    void absentAndMalformedEvidenceRemainDistinct() {
        NBTTagCompound absent = new NBTTagCompound();
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setTag("MountCollection", new NBTTagCompound());

        assertEquals(EntityMountEvidence.Status.NONE, EntityMountEvidence.readFrom(absent).getStatus());
        assertEquals(EntityMountEvidence.Status.MALFORMED, EntityMountEvidence.readFrom(malformed).getStatus());
        assertFalse(EntityMountEvidence.readFrom(malformed).getMountId().isPresent());
    }
}
