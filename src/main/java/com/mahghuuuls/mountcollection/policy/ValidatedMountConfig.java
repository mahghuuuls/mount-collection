package com.mahghuuuls.mountcollection.policy;

import net.minecraft.util.ResourceLocation;

public final class ValidatedMountConfig {

    public static final long TICKS_PER_SECOND = 20L;

    private final ConfiguredFilter<ResourceLocation> registrationEntities;
    private final ConfiguredFilter<ResourceLocation> summoningEntities;
    private final ConfiguredFilter<Integer> destinationDimensions;
    private final long summonCooldownTicks;
    private final int normalPlacementRadius;
    private final int fallbackPlacementRadius;
    private final boolean recoveryEnabled;
    private final long recoveryDurationTicks;
    private final boolean inhibitedRecallBlockingEnabled;
    private final boolean detailedDiagnosticsEnabled;

    public ValidatedMountConfig(
            ConfiguredFilter<ResourceLocation> registrationEntities,
            ConfiguredFilter<ResourceLocation> summoningEntities,
            ConfiguredFilter<Integer> destinationDimensions,
            long summonCooldownTicks,
            int normalPlacementRadius,
            int fallbackPlacementRadius,
            boolean recoveryEnabled,
            long recoveryDurationTicks,
            boolean inhibitedRecallBlockingEnabled,
            boolean detailedDiagnosticsEnabled) {
        this.registrationEntities = registrationEntities;
        this.summoningEntities = summoningEntities;
        this.destinationDimensions = destinationDimensions;
        this.summonCooldownTicks = summonCooldownTicks;
        this.normalPlacementRadius = normalPlacementRadius;
        this.fallbackPlacementRadius = fallbackPlacementRadius;
        this.recoveryEnabled = recoveryEnabled;
        this.recoveryDurationTicks = recoveryDurationTicks;
        this.inhibitedRecallBlockingEnabled = inhibitedRecallBlockingEnabled;
        this.detailedDiagnosticsEnabled = detailedDiagnosticsEnabled;
    }

    public ConfiguredFilter<ResourceLocation> getRegistrationEntities() {
        return registrationEntities;
    }

    public ConfiguredFilter<ResourceLocation> getSummoningEntities() {
        return summoningEntities;
    }

    public ConfiguredFilter<Integer> getDestinationDimensions() {
        return destinationDimensions;
    }

    public long getSummonCooldownTicks() {
        return summonCooldownTicks;
    }

    public int getNormalPlacementRadius() {
        return normalPlacementRadius;
    }

    public int getFallbackPlacementRadius() {
        return fallbackPlacementRadius;
    }

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public long getRecoveryDurationTicks() {
        return recoveryDurationTicks;
    }

    public boolean isInhibitedRecallBlockingEnabled() {
        return inhibitedRecallBlockingEnabled;
    }

    public boolean isDetailedDiagnosticsEnabled() {
        return detailedDiagnosticsEnabled;
    }
}
