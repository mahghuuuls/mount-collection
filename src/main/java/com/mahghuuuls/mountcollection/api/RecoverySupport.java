package com.mahghuuuls.mountcollection.api;

import net.minecraft.entity.Entity;

/**
 * Optional, experimental provider capability used to preserve a mount across Recovery.
 *
 * <p>Implementations must keep capture side-effect free: the mount is still following
 * Minecraft's normal death path while this method runs. Returning a failure leaves that
 * death untouched. Application is performed on a newly-created compatible entity before
 * it is admitted to the world. Providers own only their bounded persistent payload; Mount
 * Collection owns identity, placement, lifecycle fencing, and normalization of transient
 * entity state.</p>
 *
 * <p>This API is not yet stable and may change before a compatibility contract is declared
 * stable.</p>
 */
public interface RecoverySupport {

    /** Captures the provider-owned state needed to reconstruct this mount. */
    ProviderResult<ProviderPayload> capturePersistentState(Entity mount);

    /** Applies previously captured state to a fresh, compatible entity instance. */
    ProviderResult<Void> applyPersistentState(Entity mount, ProviderPayload payload);
}
