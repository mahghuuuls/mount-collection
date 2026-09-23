package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.forge.CommonProxy;
import com.mahghuuuls.mountcollection.network.MountNetwork;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public final class ClientProxy extends CommonProxy {

    private KeyBinding contextualAction;
    private KeyBinding openCollection;
    private MountNetwork network;
    private ClientRidingPreferences ridingPreferences;
    private final com.mahghuuuls.mountcollection.client.preview.ClientPreviewRegistry previews =
            new com.mahghuuuls.mountcollection.client.preview.ClientPreviewRegistry();

    @Override public void initialize() {
        previews.register(new net.minecraft.util.ResourceLocation("mountcollection:vanilla"),
                new com.mahghuuuls.mountcollection.client.preview.VanillaPreviewProvider());
        MinecraftForge.EVENT_BUS.post(new com.mahghuuuls.mountcollection.client.preview.ClientPreviewRegistrationEvent(previews));
        previews.freeze();
    }

    @Override
    public void preInitialize(MountNetwork network) {
        this.network = network;
        ridingPreferences = ClientRidingPreferences.load(new java.io.File(
                net.minecraft.client.Minecraft.getMinecraft().gameDir, "config/mountcollection-client.cfg"));
        network.setClientProtocolReceiver((message, connection) -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getMinecraft();
            client.addScheduledTask(() -> {
                if (client.getConnection() == connection) { network.acceptClientProtocol(message); }
            });
        });
        contextualAction = new KeyBinding(
                "key.mountcollection.contextual",
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                Keyboard.KEY_H,
                "key.categories.mountcollection");
        ClientRegistry.registerKeyBinding(contextualAction);
        openCollection = new KeyBinding("key.mountcollection.open", KeyConflictContext.IN_GAME,
                KeyModifier.NONE, Keyboard.KEY_NONE, "key.categories.mountcollection");
        ClientRegistry.registerKeyBinding(openCollection);
        network.setClientCollectionReceiver(message -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getMinecraft();
            client.addScheduledTask(() -> {
                if (client.world != null && client.currentScreen instanceof CollectionScreen) {
                    ((CollectionScreen) client.currentScreen).receive(message);
                }
            });
        });
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onDisconnect(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(network::clearClientProtocol);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        while (openCollection.isPressed()) {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getMinecraft();
            if (client.player != null && client.currentScreen == null) {
                client.displayGuiScreen(new CollectionScreen(network, previews));
            }
        }
        while (contextualAction.isPressed()) {
            network.sendContextualIntent(ridingPreferences.isAutomaticRiding());
        }
    }
}
