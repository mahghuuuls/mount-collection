package com.mahghuuuls.mountcollection.client.preview;

import com.mahghuuuls.mountcollection.api.MountPreview;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.*;
import net.minecraft.world.World;

/** Deliberately type-based, not a reconstruction of an authoritative mount. */
public final class VanillaPreviewProvider implements ClientPreviewProvider {
    @Override public EntityLivingBase create(MountPreview data, World world) {
        if (data.getVersion() != 1 || data.getPayloadSize() != 0) { return null; }
        switch (data.getEntityType().toString()) {
            case "minecraft:horse": return new EntityHorse(world);
            case "minecraft:donkey": return new EntityDonkey(world);
            case "minecraft:mule": return new EntityMule(world);
            case "minecraft:llama": return new EntityLlama(world);
            case "minecraft:skeleton_horse": return new EntitySkeletonHorse(world);
            case "minecraft:zombie_horse": return new EntityZombieHorse(world);
            case "minecraft:pig": return new EntityPig(world);
            default: return null;
        }
    }
}
