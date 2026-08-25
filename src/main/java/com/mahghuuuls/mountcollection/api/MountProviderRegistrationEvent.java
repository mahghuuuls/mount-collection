package com.mahghuuuls.mountcollection.api;

import java.util.Objects;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Fired during initialization after every mod has completed pre-initialization.
 */
public final class MountProviderRegistrationEvent extends Event {

    private final MountProviderRegistrar registrar;

    public MountProviderRegistrationEvent(MountProviderRegistrar registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    public void register(MountProvider provider) {
        registrar.register(provider);
    }
}
