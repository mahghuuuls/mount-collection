package com.mahghuuuls.mountcollection.client;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import net.minecraftforge.common.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ClientRidingPreferencesTest {
    @TempDir Path directory;

    @Test void defaultsOnAndOptOutOnlyChangesANewSnapshot() throws Exception {
        Field home = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true);
        Object previous = home.get(null);
        home.set(null, directory.toFile());
        try {
        File file = directory.resolve("client.cfg").toFile();
        ClientRidingPreferences first = ClientRidingPreferences.load(file);
        assertTrue(first.isAutomaticRiding());
        Configuration config = new Configuration(file);
        config.load();
        config.get("recall", "automatic_riding", true).set(false);
        config.save();
        assertTrue(first.isAutomaticRiding());
        assertFalse(ClientRidingPreferences.load(file).isAutomaticRiding());
        } finally {
            home.set(null, previous);
        }
    }
}
