package com.mahghuuuls.mountcollection.provider.vanilla;

import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.SeatEnvelope;

/** Native species policy only; universal player/world safety belongs to the gateway. */
final class VanillaBoarding {
    private VanillaBoarding() { }

    static ProviderResult<SeatEnvelope> describe(boolean pig, boolean llama, boolean alive,
            boolean child, boolean occupied, boolean tame, boolean saddled, double mountedOffset) {
        if (!alive || (!pig && child) || occupied || (pig && !saddled)) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }
        if (!pig && !tame) { return ProviderResult.failure(ProviderFailure.NOT_TAMED); }
        // Horse seating is allowed without a saddle; steering is a separate native concern.
        // Render-body yaw can differ from entity yaw. Cover the whole horizontal sweep rather
        // than guessing that renderYawOffset will already match the planned destination yaw.
        double reach = pig ? 0.0D : llama ? 0.300001D : 0.700001D;
        double rise = pig || llama ? 0.0D : 0.150001D;
        try {
            return ProviderResult.success(new SeatEnvelope(-reach, mountedOffset, -reach,
                    reach, mountedOffset + rise, reach));
        } catch (IllegalArgumentException invalid) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }
    }
}
