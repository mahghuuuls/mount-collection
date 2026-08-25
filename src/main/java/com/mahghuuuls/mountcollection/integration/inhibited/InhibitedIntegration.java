package com.mahghuuuls.mountcollection.integration.inhibited;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public final class InhibitedIntegration {

    public static final ResourceLocation EFFECT_ID = new ResourceLocation("inhibited", "inhibited");

    private final Supplier<Potion> effectLookup;

    public InhibitedIntegration() {
        this(() -> ForgeRegistries.POTIONS.getValue(EFFECT_ID));
    }

    InhibitedIntegration(Supplier<Potion> effectLookup) {
        this.effectLookup = Objects.requireNonNull(effectLookup, "effectLookup");
    }

    public InhibitedStatus getStatus(EntityLivingBase player, boolean blockingEnabled) {
        Potion effect = effectLookup.get();
        return resolve(
                blockingEnabled,
                effect != null,
                () -> player != null && player.isPotionActive(effect));
    }

    static InhibitedStatus resolve(
            boolean blockingEnabled, boolean effectAvailable, BooleanSupplier affectedCheck) {
        if (!blockingEnabled) {
            return InhibitedStatus.DISABLED;
        }
        if (!effectAvailable) {
            return InhibitedStatus.UNAVAILABLE;
        }
        return Objects.requireNonNull(affectedCheck, "affectedCheck").getAsBoolean()
                ? InhibitedStatus.AFFECTED
                : InhibitedStatus.UNAFFECTED;
    }
}
