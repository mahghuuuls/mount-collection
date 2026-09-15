package com.mahghuuuls.mountcollection.client.preview;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.Event;

/** Client initialization only, after mod pre-initialization. Never subscribe from a common class. */
public final class ClientPreviewRegistrationEvent extends Event {
    private final ClientPreviewRegistry registry;
    public ClientPreviewRegistrationEvent(ClientPreviewRegistry registry) { this.registry = registry; }
    public void register(ResourceLocation providerId, ClientPreviewProvider provider) { registry.register(providerId, provider); }
}
