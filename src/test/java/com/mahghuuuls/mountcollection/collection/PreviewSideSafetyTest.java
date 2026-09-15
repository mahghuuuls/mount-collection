package com.mahghuuuls.mountcollection.collection;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

final class PreviewSideSafetyTest {
    @Test void commonPresentationContractsResolveWithClientClassesDenied() throws Exception {
        String[] types = {"api.MountPreview", "api.MountProvider", "api.MountProviderRegistrationEvent",
                "forge.CommonProxy", "collection.CollectionService", "provider.ProviderRegistry", "network.PreviewCodec",
                "network.CollectionPage", "network.MountNetwork"};
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        ClassLoader current = getClass().getClassLoader();
        while (current != null) {
            if (current instanceof URLClassLoader) { java.util.Collections.addAll(urls, ((URLClassLoader) current).getURLs()); }
            current = current.getParent();
        }
        assertFalse(urls.isEmpty(), "isolated loading needs the actual runtime classpath");
        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(new java.net.URL[0]), null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("com.mahghuuuls.mountcollection.client.")) {
                    throw new ClassNotFoundException("client class denied: " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            for (String type : types) {
                Class<?> common = Class.forName("com.mahghuuuls.mountcollection." + type, false, isolated);
                common.getDeclaredMethods(); common.getDeclaredConstructors(); common.getDeclaredFields();
            }
        }
    }
}
