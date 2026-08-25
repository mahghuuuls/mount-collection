package com.mahghuuuls.mountcollection.api;

import net.minecraft.entity.Entity;

/**
 * Optional provider capability invoked only when the core has authorized a placement commit.
 */
public interface PreparationSupport {

    ProviderResult<Void> prepareForPlacement(Entity mount);
}
