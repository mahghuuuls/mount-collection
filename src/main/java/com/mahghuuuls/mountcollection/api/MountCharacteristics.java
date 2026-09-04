package com.mahghuuuls.mountcollection.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable provider declaration persisted by core at registration.
 *
 * <p>Only core-known enum values can cross this contract. Providers cannot attach arbitrary
 * placement callbacks or presentation data.</p>
 */
public final class MountCharacteristics {

    private static final MountCharacteristics SOLID_GROUND_DEFAULT =
            new MountCharacteristics(PlacementProfile.SOLID_GROUND, Collections.emptySet());

    private final PlacementProfile placementProfile;
    private final Set<MountTrait> traits;

    public MountCharacteristics(PlacementProfile placementProfile, Set<MountTrait> traits) {
        this.placementProfile = Objects.requireNonNull(placementProfile, "placementProfile");
        Objects.requireNonNull(traits, "traits");
        EnumSet<MountTrait> checked = EnumSet.noneOf(MountTrait.class);
        for (MountTrait trait : traits) {
            checked.add(Objects.requireNonNull(trait, "trait"));
        }
        this.traits = Collections.unmodifiableSet(checked);
    }

    public static MountCharacteristics solidGround() {
        return SOLID_GROUND_DEFAULT;
    }

    public PlacementProfile getPlacementProfile() {
        return placementProfile;
    }

    public Set<MountTrait> getTraits() {
        return traits;
    }

    public boolean hasTrait(MountTrait trait) {
        return traits.contains(Objects.requireNonNull(trait, "trait"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MountCharacteristics)) {
            return false;
        }
        MountCharacteristics that = (MountCharacteristics) other;
        return placementProfile == that.placementProfile && traits.equals(that.traits);
    }

    @Override
    public int hashCode() {
        return 31 * placementProfile.hashCode() + traits.hashCode();
    }
}
