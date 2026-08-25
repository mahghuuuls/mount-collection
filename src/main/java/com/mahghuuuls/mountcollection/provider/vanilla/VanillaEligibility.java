package com.mahghuuuls.mountcollection.provider.vanilla;

import com.mahghuuuls.mountcollection.api.ProviderFailure;
import java.util.UUID;

final class VanillaEligibility {

    enum Kind {
        OWNED_HORSE,
        UNDEAD_HORSE,
        RIDDEN_PIG,
        UNSUPPORTED
    }

    private VanillaEligibility() {}

    static ProviderFailure validate(
            Kind kind, boolean tame, UUID nativeOwnerId, UUID playerId, boolean riddenByPlayer) {
        if (kind == Kind.UNSUPPORTED) {
            return ProviderFailure.UNSUPPORTED;
        }
        if (kind == Kind.RIDDEN_PIG) {
            return riddenByPlayer ? null : ProviderFailure.NOT_OWNED;
        }
        if (!tame) {
            return ProviderFailure.NOT_TAMED;
        }
        if (kind == Kind.OWNED_HORSE) {
            return playerId.equals(nativeOwnerId) ? null : ProviderFailure.NOT_OWNED;
        }
        return playerId.equals(nativeOwnerId) || nativeOwnerId == null && riddenByPlayer
                ? null
                : ProviderFailure.NOT_OWNED;
    }
}
