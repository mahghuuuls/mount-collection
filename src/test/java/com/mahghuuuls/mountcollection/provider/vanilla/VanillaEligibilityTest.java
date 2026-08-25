package com.mahghuuuls.mountcollection.provider.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mahghuuuls.mountcollection.api.ProviderFailure;
import java.util.UUID;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityDonkey;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.entity.passive.EntityMule;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySkeletonHorse;
import net.minecraft.entity.passive.EntityZombieHorse;
import org.junit.jupiter.api.Test;

final class VanillaEligibilityTest {

    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void ordinaryHorseFamilyRequiresTamingAndNativeOwnership() {
        assertEquals(ProviderFailure.NOT_TAMED, evaluate(VanillaEligibility.Kind.OWNED_HORSE, false, player, true));
        assertEquals(ProviderFailure.NOT_OWNED, evaluate(VanillaEligibility.Kind.OWNED_HORSE, true, other, true));
        assertEquals(ProviderFailure.NOT_OWNED, evaluate(VanillaEligibility.Kind.OWNED_HORSE, true, null, true));
        assertNull(evaluate(VanillaEligibility.Kind.OWNED_HORSE, true, player, true));
    }

    @Test
    void undeadHorseAllowsNativeOwnerOrCurrentRiderWhenOwnerMissing() {
        assertEquals(ProviderFailure.NOT_TAMED, evaluate(VanillaEligibility.Kind.UNDEAD_HORSE, false, player, true));
        assertNull(evaluate(VanillaEligibility.Kind.UNDEAD_HORSE, true, player, false));
        assertNull(evaluate(VanillaEligibility.Kind.UNDEAD_HORSE, true, null, true));
        assertEquals(ProviderFailure.NOT_OWNED, evaluate(VanillaEligibility.Kind.UNDEAD_HORSE, true, null, false));
        assertEquals(ProviderFailure.NOT_OWNED, evaluate(VanillaEligibility.Kind.UNDEAD_HORSE, true, other, true));
    }

    @Test
    void pigRequiresCurrentRiderAndUnsupportedTypesFail() {
        assertNull(evaluate(VanillaEligibility.Kind.RIDDEN_PIG, false, null, true));
        assertEquals(ProviderFailure.NOT_OWNED, evaluate(VanillaEligibility.Kind.RIDDEN_PIG, false, null, false));
        assertEquals(ProviderFailure.UNSUPPORTED, evaluate(VanillaEligibility.Kind.UNSUPPORTED, true, player, true));
    }

    @Test
    void exactVanillaClassMappingContainsOnlyTheSevenApprovedMountTypes() {
        assertEquals(VanillaEligibility.Kind.OWNED_HORSE, VanillaMountProvider.kindOfClass(EntityHorse.class));
        assertEquals(VanillaEligibility.Kind.OWNED_HORSE, VanillaMountProvider.kindOfClass(EntityDonkey.class));
        assertEquals(VanillaEligibility.Kind.OWNED_HORSE, VanillaMountProvider.kindOfClass(EntityMule.class));
        assertEquals(VanillaEligibility.Kind.OWNED_HORSE, VanillaMountProvider.kindOfClass(EntityLlama.class));
        assertEquals(VanillaEligibility.Kind.UNDEAD_HORSE, VanillaMountProvider.kindOfClass(EntitySkeletonHorse.class));
        assertEquals(VanillaEligibility.Kind.UNDEAD_HORSE, VanillaMountProvider.kindOfClass(EntityZombieHorse.class));
        assertEquals(VanillaEligibility.Kind.RIDDEN_PIG, VanillaMountProvider.kindOfClass(EntityPig.class));
        assertEquals(VanillaEligibility.Kind.UNSUPPORTED, VanillaMountProvider.kindOfClass(EntityBoat.class));
        assertEquals(VanillaEligibility.Kind.UNSUPPORTED, VanillaMountProvider.kindOfClass(EntityMinecart.class));
    }

    private ProviderFailure evaluate(
            VanillaEligibility.Kind kind, boolean tame, UUID owner, boolean ridden) {
        return VanillaEligibility.validate(kind, tame, owner, player, ridden);
    }
}
