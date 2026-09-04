package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.policy.ConfigWarningSink;
import com.mahghuuuls.mountcollection.policy.MountConfigValidator;
import com.mahghuuuls.mountcollection.policy.RawMountConfig;
import com.mahghuuuls.mountcollection.policy.RegistryResolver;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import java.io.File;
import net.minecraftforge.common.config.Configuration;

public final class ForgeMountConfiguration {

    private static final String FILTER_COMMENT =
            "Mode is blacklist or whitelist. Entity entries use namespaced registry IDs, for example minecraft:horse. Changes require a server restart.";
    private static final String DIMENSION_COMMENT =
            "Mode is blacklist or whitelist. Entries are registered numeric dimension IDs, for example 0, -1, or 1. Changes require a server restart.";

    private final File file;

    public ForgeMountConfiguration(File file) {
        this.file = file;
    }

    public void ensureGenerated() {
        readRaw();
    }

    public ValidatedMountConfig loadValidated(
            RegistryResolver registryResolver, ConfigWarningSink warningSink) {
        return MountConfigValidator.validate(readRaw(), registryResolver, warningSink);
    }

    public File getFile() {
        return file;
    }

    private RawMountConfig readRaw() {
        Configuration configuration = new Configuration(file);
        configuration.load();

        String registrationMode = configuration.getString(
                "mode", "registration", defaultFilterMode(), FILTER_COMMENT);
        String[] registrationEntities = configuration.getStringList(
                "entities", "registration", new String[0], FILTER_COMMENT);
        String summoningMode = configuration.getString(
                "mode", "summoning", defaultFilterMode(), FILTER_COMMENT);
        String[] summoningEntities = configuration.getStringList(
                "entities", "summoning", new String[0], FILTER_COMMENT);
        String destinationMode = configuration.getString(
                "mode", "destination_dimensions", defaultFilterMode(), DIMENSION_COMMENT);
        String[] destinationDimensions = configuration.getStringList(
                "ids", "destination_dimensions", new String[0], DIMENSION_COMMENT);

        String cooldown = configuration.getString(
                "cooldown_seconds", "recall", Long.toString(MountConfigValidator.DEFAULT_COOLDOWN_SECONDS),
                "Successful recall cooldown in active-server seconds. Zero disables waiting. Changes require a server restart.");
        String normalRadius = configuration.getString(
                "normal_placement_radius", "recall", Integer.toString(MountConfigValidator.DEFAULT_NORMAL_RADIUS),
                "First safe-placement search radius in blocks. Must be non-negative. Changes require a server restart.");
        String fallbackRadius = configuration.getString(
                "fallback_placement_radius", "recall", Integer.toString(MountConfigValidator.DEFAULT_FALLBACK_RADIUS),
                "Fallback safe-placement search radius in blocks. Must be at least the normal radius. Changes require a server restart.");
        boolean disableFlyingMountRecall = configuration.getBoolean(
                "disable_flying_mount_recall",
                "summoning",
                MountConfigValidator.DEFAULT_DISABLE_FLYING_MOUNT_RECALL,
                "Prevent mounts with the FLYING trait from being recalled or restored from Recovery. Registration and collection management remain available. Changes require a server restart.");

        boolean recoveryEnabled = configuration.getBoolean(
                "enabled", "recovery", MountConfigValidator.DEFAULT_RECOVERY_ENABLED,
                "Protect eligible registered mounts from lethal damage. Changes affect future lethal events after restart.");
        String recoveryDuration = configuration.getString(
                "duration_seconds", "recovery", Long.toString(MountConfigValidator.DEFAULT_RECOVERY_SECONDS),
                "Recovery wait in active-server seconds. Zero makes the mount immediately available for explicit recall. Changes require a server restart.");
        boolean inhibitedBlocking = configuration.getBoolean(
                "inhibited_recall_blocking", "integrations", MountConfigValidator.DEFAULT_INHIBITED_BLOCKING_ENABLED,
                "Block recall while the player has inhibited:inhibited when that effect exists. Changes require a server restart.");
        boolean diagnostics = configuration.getBoolean(
                "detailed_enabled", "diagnostics", MountConfigValidator.DEFAULT_DETAILED_DIAGNOSTICS_ENABLED,
                "Enable bounded operator-focused detailed diagnostics. Changes require a server restart.");

        if (configuration.hasChanged()) {
            configuration.save();
        }

        return new RawMountConfig(
                registrationMode,
                registrationEntities,
                summoningMode,
                summoningEntities,
                destinationMode,
                destinationDimensions,
                cooldown,
                normalRadius,
                fallbackRadius,
                disableFlyingMountRecall,
                recoveryEnabled,
                recoveryDuration,
                inhibitedBlocking,
                diagnostics);
    }

    private static String defaultFilterMode() {
        return MountConfigValidator.DEFAULT_FILTER_MODE.name().toLowerCase(java.util.Locale.ROOT);
    }
}
