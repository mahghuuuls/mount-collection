package com.mahghuuuls.mountcollection.client;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/** Immutable client-start snapshot; never loaded on a dedicated server. */
public final class ClientRidingPreferences {
    private final boolean automaticRiding;
    private final boolean showBoardingFailureMessage;
    private ClientRidingPreferences(boolean enabled, boolean showMessage) {
        automaticRiding = enabled;
        showBoardingFailureMessage = showMessage;
    }
    public boolean isAutomaticRiding() { return automaticRiding; }
    public static ClientRidingPreferences load(File file) {
        Configuration config = new Configuration(file);
        config.load();
        net.minecraftforge.common.config.Property property = config.get("recall", "automatic_riding", true,
                "Automatically ride after a safe summon. False leaves your mount nearby. Restart the client to apply.");
        property.setRequiresMcRestart(true);
        boolean enabled = property.getBoolean(true);
        net.minecraftforge.common.config.Property notice = config.get("recall", "show_boarding_failure_message", true,
                "Show a message when your mount was summoned but you could not automatically ride it. Restart the client to apply.");
        notice.setRequiresMcRestart(true);
        boolean showMessage = notice.getBoolean(true);
        if (config.hasChanged()) { config.save(); }
        return new ClientRidingPreferences(enabled, showMessage);
    }

    /** Suppresses only our standalone partial-success notice, never generic chat or failures. */
    public void onChat(net.minecraftforge.client.event.ClientChatReceivedEvent event) {
        net.minecraft.util.text.ITextComponent message = event.getMessage();
        if (!showBoardingFailureMessage && message instanceof net.minecraft.util.text.TextComponentTranslation
                && message.getSiblings().isEmpty()
                && "mountcollection.message.boarding_failed".equals(
                        ((net.minecraft.util.text.TextComponentTranslation) message).getKey())) {
            event.setCanceled(true);
        }
    }
}
