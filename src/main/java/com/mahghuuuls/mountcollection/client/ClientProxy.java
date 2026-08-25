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
    private MountNetwork network;

    @Override
    public void preInitialize(MountNetwork network) {
        this.network = network;
        contextualAction = new KeyBinding(
                "key.mountcollection.contextual",
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                Keyboard.KEY_H,
                "key.categories.mountcollection");
        ClientRegistry.registerKeyBinding(contextualAction);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        while (contextualAction.isPressed()) {
            network.sendContextualIntent();
        }
    }
}
