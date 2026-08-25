package com.mahghuuuls.mountcollection.api;

import java.util.Objects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Bounded provider-owned registration identity interpreted by core persistence.
 */
public final class RegistrationProfile {

    private final ResourceLocation entityTypeId;
    private final String fallbackTypeKey;
    private final ProviderPayload providerPayload;

    public RegistrationProfile(ResourceLocation entityTypeId, String fallbackTypeKey) {
        this(entityTypeId, fallbackTypeKey, new ProviderPayload(0, new NBTTagCompound()));
    }

    public RegistrationProfile(
            ResourceLocation entityTypeId,
            String fallbackTypeKey,
            ProviderPayload providerPayload) {
        this.entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
        this.fallbackTypeKey = requireBounded(fallbackTypeKey, "fallbackTypeKey", 128);
        this.providerPayload = Objects.requireNonNull(providerPayload, "providerPayload");
    }

    public ResourceLocation getEntityTypeId() {
        return entityTypeId;
    }

    public String getFallbackTypeKey() {
        return fallbackTypeKey;
    }

    public ProviderPayload getProviderPayload() {
        return new ProviderPayload(providerPayload.getVersion(), providerPayload.copyData());
    }

    private static String requireBounded(String value, String name, int maximumLength) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty() || checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximumLength + " characters");
        }
        return checked;
    }
}
