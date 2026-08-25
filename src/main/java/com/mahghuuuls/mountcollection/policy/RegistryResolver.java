package com.mahghuuuls.mountcollection.policy;

import net.minecraft.util.ResourceLocation;

public interface RegistryResolver {

    boolean isKnownEntity(ResourceLocation entityId);

    boolean isKnownDimension(int dimensionId);
}
