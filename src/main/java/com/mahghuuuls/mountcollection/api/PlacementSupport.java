package com.mahghuuuls.mountcollection.api;

import net.minecraft.entity.Entity;

/**
 * Optional provider capability for declarative placement requirements.
 */
public interface PlacementSupport {

    ProviderResult<PlacementProfile> getPlacementProfile(Entity mount);
}
