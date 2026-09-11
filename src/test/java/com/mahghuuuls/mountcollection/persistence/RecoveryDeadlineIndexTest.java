package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.policy.RecoveryDeadlineIndex;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RecoveryDeadlineIndexTest {

    @Test
    void returnsOnlyDueRecoveringRecordsInDeadlineOrder() {
        MountRecord later = recovering(80L);
        MountRecord first = recovering(40L);
        RecoveryDeadlineIndex index = new RecoveryDeadlineIndex();
        index.rebuild(Arrays.asList(later, first));

        assertFalse(index.peekDue(39L).isPresent());
        assertEquals(first.getMountId(), index.peekDue(40L).get());
        index.remove(first.getMountId());
        assertFalse(index.peekDue(79L).isPresent());
        assertEquals(later.getMountId(), index.peekDue(80L).get());
    }

    @Test
    void reschedulingAndRebuildDiscardStaleDerivedEntries() {
        MountRecord original = recovering(40L);
        MountRecord replacement = recovering(original, 90L);
        RecoveryDeadlineIndex index = new RecoveryDeadlineIndex();
        index.schedule(original);
        index.schedule(replacement);

        assertFalse(index.peekDue(40L).isPresent());
        assertEquals(original.getMountId(), index.peekDue(90L).get());

        index.rebuild(Collections.emptyList());
        assertEquals(0, index.size());
        assertFalse(index.peekDue(Long.MAX_VALUE).isPresent());
    }

    private static MountRecord recovering(long deadline) {
        MountId id = MountId.create();
        return recovering(id, deadline);
    }

    private static MountRecord recovering(MountRecord original, long deadline) {
        return recovering(original.getMountId(), deadline);
    }

    private static MountRecord recovering(MountId id, long deadline) {
        UUID owner = UUID.randomUUID();
        UUID physical = UUID.randomUUID();
        LastKnownEvidence evidence = new LastKnownEvidence(0, 1.0D, 64.0D, 2.0D);
        RecoveryState recovery = new RecoveryState(
                physical, evidence, new ProviderPayload(1, new NBTTagCompound()),
                deadline, deadline);
        return new MountRecord(
                id, owner, new ResourceLocation("mountcollection:vanilla"),
                new ResourceLocation("minecraft:horse"), "minecraft:horse", 1, 1L,
                null, null, MountCondition.RECOVERING, null,
                MountCharacteristics.solidGround(), 0,
                new NBTTagCompound(), recovery, null);
    }
}
