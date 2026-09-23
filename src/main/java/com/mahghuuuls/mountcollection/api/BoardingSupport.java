package com.mahghuuuls.mountcollection.api;

import java.util.UUID;
import net.minecraft.entity.Entity;

/** Optional, experimental read-only boarding declaration; core retains all mutation authority. */
public interface BoardingSupport {
    /**
     * Inspect the exact living or unspawned restored mount. Success must contain a seat envelope
     * covering every native seated position possible during attachment, including pose variation.
     * Coordinates are relative to the mount origin at yaw zero. Do not include rider size or
     * rider Y offset; core adds those. Do not mutate entities, load chunks, or post mounting events.
     * Unsupported/denied/null declarations fail closed for automatic riding only.
     * This declaration cannot waive global ownership, occupancy, collision or hazard checks.
     */
    ProviderResult<SeatEnvelope> describeBoarding(Entity mount, UUID riderId);
}
