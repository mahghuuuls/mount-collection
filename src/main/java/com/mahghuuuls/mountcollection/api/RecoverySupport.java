package com.mahghuuuls.mountcollection.api;

import net.minecraft.entity.Entity;

/**
 * Optional provider capability for bounded persistent-state capture and application.
 */
public interface RecoverySupport {

    ProviderResult<ProviderPayload> capturePersistentState(Entity mount);

    ProviderResult<Void> applyPersistentState(Entity mount, ProviderPayload payload);
}
