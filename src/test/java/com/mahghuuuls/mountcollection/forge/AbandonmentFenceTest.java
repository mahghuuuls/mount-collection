package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway.CheckpointStatus;
import com.mahghuuuls.mountcollection.persistence.*;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class AbandonmentFenceTest {
    @Test void savedFenceRequiresExactUniqueEntityWithNoCorroborationIncludingNestedPassengers() {
        MountRepository repository = new MountRepository();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(UUID.randomUUID(),
                new ResourceLocation("mountcollection:vanilla"), new ResourceLocation("minecraft:horse"),
                "minecraft:horse", UUID.randomUUID(), new LastKnownEvidence(0, 0, 64, 0), null)).getRecord().get();
        NBTTagCompound root = new NBTTagCompound(); NBTTagCompound level = new NBTTagCompound();
        NBTTagList entities = new NBTTagList(); level.setTag("Entities", entities); root.setTag("Level", level);
        assertEquals(CheckpointStatus.FAILED, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
        NBTTagCompound horse = new NBTTagCompound();
        horse.setUniqueId("UUID", record.getPhysicalEntityId()); horse.setString("id", "minecraft:horse");
        NBTTagCompound boat = new NBTTagCompound(); boat.setUniqueId("UUID", UUID.randomUUID());
        NBTTagList passengers = new NBTTagList(); passengers.appendTag(horse); boat.setTag("Passengers", passengers);
        entities.appendTag(boat);
        assertEquals(CheckpointStatus.VERIFIED, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
        NBTTagCompound data = new NBTTagCompound(); NBTTagCompound marker = new NBTTagCompound();
        marker.setInteger("Version", 1); marker.setString("MountId", record.getMountId().toString());
        data.setTag("MountCollection", marker); horse.setTag("ForgeData", data);
        assertEquals(CheckpointStatus.INTEGRITY_CONFLICT, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
        data.setString("MountCollection", "malformed");
        assertEquals(CheckpointStatus.INTEGRITY_CONFLICT, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
        data.removeTag("MountCollection");
        horse.setString("id", "minecraft:pig");
        assertEquals(CheckpointStatus.INTEGRITY_CONFLICT, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
        horse.setString("id", "minecraft:horse"); entities.appendTag(horse.copy());
        assertEquals(CheckpointStatus.INTEGRITY_CONFLICT, ForgeRecallWorldGateway.inspectSavedAbandonment(root, record));
    }
}
