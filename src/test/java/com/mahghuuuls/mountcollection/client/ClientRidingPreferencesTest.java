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
        net.minecraftforge.client.event.ClientChatReceivedEvent shown = chat("mountcollection.message.boarding_failed");
        first.onChat(shown); assertFalse(shown.isCanceled());
        Configuration config = new Configuration(file);
        config.load();
        config.get("recall", "automatic_riding", true).set(false);
        config.get("recall", "show_boarding_failure_message", true).set(false);
        config.save();
        assertTrue(first.isAutomaticRiding());
        assertFalse(ClientRidingPreferences.load(file).isAutomaticRiding());
        ClientRidingPreferences muted = ClientRidingPreferences.load(file);
        net.minecraftforge.client.event.ClientChatReceivedEvent hidden = chat("mountcollection.message.boarding_failed");
        muted.onChat(hidden); assertTrue(hidden.isCanceled());
        first.onChat(shown); assertFalse(shown.isCanceled(), "existing snapshot is unchanged");
        for (String key : new String[]{"mountcollection.message.no_safe_destination", "mountcollection.message.recalled",
                "mountcollection.message.persistence_failure", "mountcollection.message.boarding_failed.extra"}) {
            net.minecraftforge.client.event.ClientChatReceivedEvent other = chat(key);
            muted.onChat(other); assertFalse(other.isCanceled());
        }
        net.minecraftforge.client.event.ClientChatReceivedEvent plain = new net.minecraftforge.client.event.ClientChatReceivedEvent(
                net.minecraft.util.text.ChatType.SYSTEM, new net.minecraft.util.text.TextComponentString("mountcollection.message.boarding_failed"));
        muted.onChat(plain); assertFalse(plain.isCanceled());
        net.minecraftforge.client.event.ClientChatReceivedEvent composite = chat("mountcollection.message.boarding_failed");
        composite.getMessage().appendText("unrelated"); muted.onChat(composite); assertFalse(composite.isCanceled());
        } finally {
            home.set(null, previous);
        }
    }

    private static net.minecraftforge.client.event.ClientChatReceivedEvent chat(String key) {
        // Plain JUnit does not run Forge's transformer that implements @Cancelable.
        return new net.minecraftforge.client.event.ClientChatReceivedEvent(net.minecraft.util.text.ChatType.SYSTEM,
                new net.minecraft.util.text.TextComponentTranslation(key)) {
            @Override public boolean isCancelable() { return true; }
        };
    }
}
