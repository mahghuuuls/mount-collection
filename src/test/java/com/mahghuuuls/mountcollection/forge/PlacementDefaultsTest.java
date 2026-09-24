package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.policy.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class PlacementDefaultsTest {
    @TempDir Path directory;
    @Test void generatedDefaultsRetainOverridesAndRepairInvalidValuesWithWarnings() throws Exception {
        Field home = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); Object previous = home.get(null); home.set(null, directory.toFile());
        try {
            File file = directory.resolve("server.cfg").toFile();
            ForgeMountConfiguration loader = new ForgeMountConfiguration(file);
            RegistryResolver registry = new RegistryResolver() {
                public boolean isKnownEntity(ResourceLocation id) { return true; }
                public boolean isKnownDimension(int id) { return true; }
            };
            List<String> warnings = new ArrayList<>();
            ConfigWarningSink sink = (key, value, fallback) -> warnings.add(key);
            ValidatedMountConfig fresh = loader.loadValidated(registry, sink);
            assertEquals(4, fresh.getNormalPlacementRadius()); assertEquals(8, fresh.getFallbackPlacementRadius());
            assertTrue(warnings.isEmpty());
            Configuration config = new Configuration(file); config.load();
            config.get("recall", "fallback_placement_radius", "8").set("16"); config.save();
            assertEquals(16, loader.loadValidated(registry, sink).getFallbackPlacementRadius());
            assertTrue(warnings.isEmpty());
            config.get("recall", "fallback_placement_radius", "8").set("2"); config.save();
            assertEquals(8, loader.loadValidated(registry, sink).getFallbackPlacementRadius());
            assertFalse(warnings.isEmpty());
        } finally { home.set(null, previous); }
    }
}
