package com.mahghuuuls.mountcollection.api;

/**
 * Initialization-only registration boundary for compatibility providers.
 */
public interface MountProviderRegistrar {

    void register(MountProvider provider);
}
