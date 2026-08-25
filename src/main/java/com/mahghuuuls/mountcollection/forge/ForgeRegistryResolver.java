package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.policy.RegistryResolver;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public final class ForgeRegistryResolver implements RegistryResolver {

    @Override
    public boolean isKnownEntity(ResourceLocation entityId) {
        return ForgeRegistries.ENTITIES.containsKey(entityId);
    }

    @Override
    public boolean isKnownDimension(int dimensionId) {
        return DimensionManager.isDimensionRegistered(dimensionId);
    }
}
