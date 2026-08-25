package com.mahghuuuls.mountcollection.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountConfigValidatorTest {

    private static final RegistryResolver KNOWN_VANILLA = new RegistryResolver() {
        @Override
        public boolean isKnownEntity(ResourceLocation entityId) {
            return "minecraft".equals(entityId.getNamespace());
        }

        @Override
        public boolean isKnownDimension(int dimensionId) {
            return dimensionId == -1 || dimensionId == 0 || dimensionId == 1;
        }
    };

    @Test
    void shippedDefaultsRemainIndependentAndPermissive() {
        RecordingWarnings warnings = new RecordingWarnings();

        ValidatedMountConfig config = MountConfigValidator.validate(defaultRaw(), KNOWN_VANILLA, warnings);

        assertEquals(FilterMode.BLACKLIST, config.getRegistrationEntities().getMode());
        assertEquals(FilterMode.BLACKLIST, config.getSummoningEntities().getMode());
        assertEquals(FilterMode.BLACKLIST, config.getDestinationDimensions().getMode());
        assertTrue(config.getRegistrationEntities().getEntries().isEmpty());
        assertTrue(config.getSummoningEntities().getEntries().isEmpty());
        assertTrue(config.getDestinationDimensions().getEntries().isEmpty());
        assertTrue(config.getRegistrationEntities().allows(new ResourceLocation("minecraft:horse")));
        assertEquals(200L, config.getSummonCooldownTicks());
        assertEquals(4, config.getNormalPlacementRadius());
        assertEquals(16, config.getFallbackPlacementRadius());
        assertTrue(config.isRecoveryEnabled());
        assertEquals(6000L, config.getRecoveryDurationTicks());
        assertTrue(config.isInhibitedRecallBlockingEnabled());
        assertFalse(config.isDetailedDiagnosticsEnabled());
        assertTrue(warnings.values.isEmpty());
    }

    @Test
    void invalidValuesWarnAndUseDocumentedDefaults() {
        RecordingWarnings warnings = new RecordingWarnings();
        RawMountConfig raw = new RawMountConfig(
                "invalid",
                new String[] {"not valid", "missing:entity"},
                "allow",
                new String[0],
                "anything",
                new String[] {"not-a-number", "44"},
                "-1",
                "20",
                "3",
                true,
                "-8",
                true,
                false);

        ValidatedMountConfig config = MountConfigValidator.validate(raw, KNOWN_VANILLA, warnings);

        assertEquals(FilterMode.BLACKLIST, config.getRegistrationEntities().getMode());
        assertEquals(FilterMode.BLACKLIST, config.getSummoningEntities().getMode());
        assertEquals(FilterMode.BLACKLIST, config.getDestinationDimensions().getMode());
        assertEquals(200L, config.getSummonCooldownTicks());
        assertEquals(4, config.getNormalPlacementRadius());
        assertEquals(16, config.getFallbackPlacementRadius());
        assertEquals(6000L, config.getRecoveryDurationTicks());
        assertTrue(warnings.values.size() >= 9, "each invalid category should produce a warning");
    }

    @Test
    void zeroDurationsAndIndependentWhitelistFiltersRemainValid() {
        RecordingWarnings warnings = new RecordingWarnings();
        RawMountConfig raw = new RawMountConfig(
                "whitelist",
                new String[] {"minecraft:horse"},
                "blacklist",
                new String[] {"minecraft:pig"},
                "whitelist",
                new String[] {"-1"},
                "0",
                "0",
                "0",
                true,
                "0",
                false,
                true);

        ValidatedMountConfig config = MountConfigValidator.validate(raw, KNOWN_VANILLA, warnings);

        assertTrue(config.getRegistrationEntities().allows(new ResourceLocation("minecraft:horse")));
        assertFalse(config.getRegistrationEntities().allows(new ResourceLocation("minecraft:pig")));
        assertFalse(config.getSummoningEntities().allows(new ResourceLocation("minecraft:pig")));
        assertTrue(config.getSummoningEntities().allows(new ResourceLocation("minecraft:horse")));
        assertTrue(config.getDestinationDimensions().allows(-1));
        assertFalse(config.getDestinationDimensions().allows(0));
        assertEquals(0L, config.getSummonCooldownTicks());
        assertEquals(0L, config.getRecoveryDurationTicks());
        assertEquals(0, config.getNormalPlacementRadius());
        assertEquals(0, config.getFallbackPlacementRadius());
        assertFalse(config.isInhibitedRecallBlockingEnabled());
        assertTrue(config.isDetailedDiagnosticsEnabled());
        assertTrue(warnings.values.isEmpty());
    }

    @Test
    void secondsOverflowWarnsInsteadOfWrapping() {
        RecordingWarnings warnings = new RecordingWarnings();
        RawMountConfig raw = new RawMountConfig(
                "blacklist",
                new String[0],
                "blacklist",
                new String[0],
                "blacklist",
                new String[0],
                Long.toString(Long.MAX_VALUE),
                "4",
                "16",
                true,
                Long.toString(Long.MAX_VALUE),
                true,
                false);

        ValidatedMountConfig config = MountConfigValidator.validate(raw, KNOWN_VANILLA, warnings);

        assertEquals(200L, config.getSummonCooldownTicks());
        assertEquals(6000L, config.getRecoveryDurationTicks());
        assertEquals(2, warnings.values.size());
    }

    private static RawMountConfig defaultRaw() {
        return new RawMountConfig(
                "blacklist",
                new String[0],
                "blacklist",
                new String[0],
                "blacklist",
                new String[0],
                "10",
                "4",
                "16",
                true,
                "300",
                true,
                false);
    }

    private static final class RecordingWarnings implements ConfigWarningSink {
        private final List<String> values = new ArrayList<>();

        @Override
        public void warn(String key, String rejectedValue, String fallback) {
            values.add(key + "=" + rejectedValue + "->" + fallback);
        }
    }
}
