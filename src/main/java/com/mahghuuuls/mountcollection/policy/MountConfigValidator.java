package com.mahghuuuls.mountcollection.policy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.ResourceLocation;

public final class MountConfigValidator {

    public static final FilterMode DEFAULT_FILTER_MODE = FilterMode.BLACKLIST;
    public static final long DEFAULT_COOLDOWN_SECONDS = 10L;
    public static final int DEFAULT_NORMAL_RADIUS = 4;
    public static final int DEFAULT_FALLBACK_RADIUS = 16;
    public static final boolean DEFAULT_RECOVERY_ENABLED = true;
    public static final long DEFAULT_RECOVERY_SECONDS = 300L;
    public static final boolean DEFAULT_INHIBITED_BLOCKING_ENABLED = true;
    public static final boolean DEFAULT_DETAILED_DIAGNOSTICS_ENABLED = false;

    private MountConfigValidator() {}

    public static ValidatedMountConfig validate(
            RawMountConfig raw, RegistryResolver registryResolver, ConfigWarningSink warningSink) {
        FilterMode registrationMode = parseMode("registration.mode", raw.registrationMode, warningSink);
        FilterMode summoningMode = parseMode("summoning.mode", raw.summoningMode, warningSink);
        FilterMode destinationMode = parseMode("destination_dimensions.mode", raw.destinationMode, warningSink);

        Set<ResourceLocation> registrationEntries = parseEntities(
                "registration.entities", raw.registrationEntities, registryResolver, warningSink);
        Set<ResourceLocation> summoningEntries = parseEntities(
                "summoning.entities", raw.summoningEntities, registryResolver, warningSink);
        Set<Integer> dimensionEntries = parseDimensions(
                raw.destinationDimensions, registryResolver, warningSink);

        long cooldownSeconds = parseNonNegativeLong(
                "recall.cooldown_seconds", raw.cooldownSeconds, DEFAULT_COOLDOWN_SECONDS, warningSink);
        long recoverySeconds = parseNonNegativeLong(
                "recovery.duration_seconds", raw.recoveryDurationSeconds, DEFAULT_RECOVERY_SECONDS, warningSink);
        int normalRadius = parseNonNegativeInt(
                "recall.normal_placement_radius", raw.normalRadius, DEFAULT_NORMAL_RADIUS, warningSink);
        int fallbackRadius = parseNonNegativeInt(
                "recall.fallback_placement_radius", raw.fallbackRadius, DEFAULT_FALLBACK_RADIUS, warningSink);
        if (fallbackRadius < normalRadius) {
            warningSink.warn(
                    "recall.placement_radii",
                    normalRadius + "/" + fallbackRadius,
                    DEFAULT_NORMAL_RADIUS + "/" + DEFAULT_FALLBACK_RADIUS);
            normalRadius = DEFAULT_NORMAL_RADIUS;
            fallbackRadius = DEFAULT_FALLBACK_RADIUS;
        }

        long cooldownTicks = secondsToTicks(
                "recall.cooldown_seconds", cooldownSeconds, DEFAULT_COOLDOWN_SECONDS, warningSink);
        long recoveryTicks = secondsToTicks(
                "recovery.duration_seconds", recoverySeconds, DEFAULT_RECOVERY_SECONDS, warningSink);

        return new ValidatedMountConfig(
                new ConfiguredFilter<>(registrationMode, registrationEntries),
                new ConfiguredFilter<>(summoningMode, summoningEntries),
                new ConfiguredFilter<>(destinationMode, dimensionEntries),
                cooldownTicks,
                normalRadius,
                fallbackRadius,
                raw.recoveryEnabled,
                recoveryTicks,
                raw.inhibitedBlockingEnabled,
                raw.detailedDiagnosticsEnabled);
    }

    private static FilterMode parseMode(String key, String rawValue, ConfigWarningSink warningSink) {
        String normalized = rawValue == null ? "" : rawValue.trim().toUpperCase(Locale.ROOT);
        try {
            return FilterMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            warningSink.warn(
                    key,
                    String.valueOf(rawValue),
                    DEFAULT_FILTER_MODE.name().toLowerCase(Locale.ROOT));
            return DEFAULT_FILTER_MODE;
        }
    }

    private static Set<ResourceLocation> parseEntities(
            String key,
            String[] values,
            RegistryResolver registryResolver,
            ConfigWarningSink warningSink) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (String value : values) {
            ResourceLocation entityId;
            try {
                entityId = new ResourceLocation(value.trim());
            } catch (RuntimeException exception) {
                warningSink.warn(key, value, "entry ignored");
                continue;
            }
            if (!registryResolver.isKnownEntity(entityId)) {
                warningSink.warn(key, value, "entry ignored");
                continue;
            }
            result.add(entityId);
        }
        return result;
    }

    private static Set<Integer> parseDimensions(
            String[] values,
            RegistryResolver registryResolver,
            ConfigWarningSink warningSink) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String value : values) {
            int dimensionId;
            try {
                dimensionId = Integer.parseInt(value.trim());
            } catch (NumberFormatException exception) {
                warningSink.warn("destination_dimensions.ids", value, "entry ignored");
                continue;
            }
            if (!registryResolver.isKnownDimension(dimensionId)) {
                warningSink.warn("destination_dimensions.ids", value, "entry ignored");
                continue;
            }
            result.add(dimensionId);
        }
        return result;
    }

    private static long parseNonNegativeLong(
            String key, String rawValue, long defaultValue, ConfigWarningSink warningSink) {
        try {
            long value = Long.parseLong(rawValue.trim());
            if (value >= 0L) {
                return value;
            }
        } catch (RuntimeException ignored) {
            // The warning below owns invalid-value reporting.
        }
        warningSink.warn(key, rawValue, Long.toString(defaultValue));
        return defaultValue;
    }

    private static int parseNonNegativeInt(
            String key, String rawValue, int defaultValue, ConfigWarningSink warningSink) {
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value >= 0) {
                return value;
            }
        } catch (RuntimeException ignored) {
            // The warning below owns invalid-value reporting.
        }
        warningSink.warn(key, rawValue, Integer.toString(defaultValue));
        return defaultValue;
    }

    private static long secondsToTicks(
            String key, long seconds, long defaultSeconds, ConfigWarningSink warningSink) {
        if (seconds > Long.MAX_VALUE / ValidatedMountConfig.TICKS_PER_SECOND) {
            warningSink.warn(key, Long.toString(seconds), Long.toString(defaultSeconds));
            return defaultSeconds * ValidatedMountConfig.TICKS_PER_SECOND;
        }
        return seconds * ValidatedMountConfig.TICKS_PER_SECOND;
    }
}
