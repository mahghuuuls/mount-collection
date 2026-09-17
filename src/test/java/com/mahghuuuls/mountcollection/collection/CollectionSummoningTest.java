package com.mahghuuuls.mountcollection.collection;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.persistence.*;
import com.mahghuuuls.mountcollection.policy.*;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import java.util.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

final class CollectionSummoningTest {
    @Test void serverProjectionAndRecallAgreeForFlyingSwitchAndEntityFilters() {
        ResourceLocation horse = new ResourceLocation("minecraft:horse");
        for (boolean flying : new boolean[] {false, true}) {
            for (boolean disableFlying : new boolean[] {false, true}) {
                for (FilterMode mode : FilterMode.values()) {
                    for (boolean listed : new boolean[] {false, true}) {
                        ValidatedMountConfig config = new ValidatedMountConfig(
                                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                                new ConfiguredFilter<>(mode, listed ? Collections.singleton(horse) : Collections.emptySet()),
                                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                                0, 4, 16, disableFlying, true, 72000, true, false);
                        UUID owner = UUID.randomUUID();
                        MountRepository repository = new MountRepository();
                        MountCharacteristics traits = new MountCharacteristics(PlacementProfile.WATER,
                                flying ? EnumSet.of(MountTrait.FLYING) : Collections.emptySet());
                        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(owner,
                                new ResourceLocation("mountcollection:vanilla"), horse, "horse", UUID.randomUUID(),
                                new LastKnownEvidence(0, 0, 64, 0), null, traits,
                                new ProviderPayload(0, new NBTTagCompound()))).getRecord().get();
                        boolean disabled = (flying && disableFlying)
                                || (mode == FilterMode.BLACKLIST ? listed : !listed);
                        CollectionView.Entry row = new CollectionService(repository, null, config).snapshot(owner, 0).getEntries().get(0);
                        assertEquals(disabled, row.isSummoningDisabled());
                        assertEquals(disabled ? ContextualOutcome.Status.SUMMON_DISALLOWED : null,
                                new RecallPolicy().evaluate(record, true, false, 0, InhibitedStatus.UNAFFECTED, 0, config));
                        // A temporary cooldown must not turn the static capability badge into a restriction.
                        if (!disabled) {
                            assertEquals(ContextualOutcome.Status.COOLDOWN,
                                    new RecallPolicy().evaluate(record, true, false, 0, InhibitedStatus.UNAFFECTED, 20, config));
                            assertFalse(row.isSummoningDisabled());
                        }
                    }
                }
            }
        }
    }
}
