package com.mahghuuuls.mountcollection.client;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/** Immutable client-start snapshot; never loaded on a dedicated server. */
public final class ClientRidingPreferences {
    private final boolean automaticRiding;
    private ClientRidingPreferences(boolean enabled) { automaticRiding = enabled; }
    public boolean isAutomaticRiding() { return automaticRiding; }
    public static ClientRidingPreferences load(File file) {
        Configuration config = new Configuration(file);
        config.load();
        net.minecraftforge.common.config.Property property = config.get("recall", "automatic_riding", true,
                "Automatically ride after a safe summon. False leaves your mount nearby. Restart the client to apply.");
        property.setRequiresMcRestart(true);
        boolean enabled = property.getBoolean(true);
        if (config.hasChanged()) { config.save(); }
        return new ClientRidingPreferences(enabled);
    }
}
