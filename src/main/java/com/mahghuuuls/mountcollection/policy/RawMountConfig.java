package com.mahghuuuls.mountcollection.policy;

public final class RawMountConfig {

    final String registrationMode;
    final String[] registrationEntities;
    final String summoningMode;
    final String[] summoningEntities;
    final String destinationMode;
    final String[] destinationDimensions;
    final String cooldownSeconds;
    final String normalRadius;
    final String fallbackRadius;
    final boolean recoveryEnabled;
    final String recoveryDurationSeconds;
    final boolean inhibitedBlockingEnabled;
    final boolean detailedDiagnosticsEnabled;

    public RawMountConfig(
            String registrationMode,
            String[] registrationEntities,
            String summoningMode,
            String[] summoningEntities,
            String destinationMode,
            String[] destinationDimensions,
            String cooldownSeconds,
            String normalRadius,
            String fallbackRadius,
            boolean recoveryEnabled,
            String recoveryDurationSeconds,
            boolean inhibitedBlockingEnabled,
            boolean detailedDiagnosticsEnabled) {
        this.registrationMode = registrationMode;
        this.registrationEntities = registrationEntities;
        this.summoningMode = summoningMode;
        this.summoningEntities = summoningEntities;
        this.destinationMode = destinationMode;
        this.destinationDimensions = destinationDimensions;
        this.cooldownSeconds = cooldownSeconds;
        this.normalRadius = normalRadius;
        this.fallbackRadius = fallbackRadius;
        this.recoveryEnabled = recoveryEnabled;
        this.recoveryDurationSeconds = recoveryDurationSeconds;
        this.inhibitedBlockingEnabled = inhibitedBlockingEnabled;
        this.detailedDiagnosticsEnabled = detailedDiagnosticsEnabled;
    }
}
