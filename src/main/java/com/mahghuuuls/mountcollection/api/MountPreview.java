package com.mahghuuuls.mountcollection.api;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/** Immutable presentation only. Payload versions belong to the named provider, never Recovery. */
public final class MountPreview {
    public static final int MAX_PAYLOAD_BYTES = 1024;
    public static final int MAX_ID_BYTES = 128;
    private final ResourceLocation providerId;
    private final ResourceLocation entityType;
    private final int version;
    private final byte[] payload;

    public MountPreview(ResourceLocation providerId, ResourceLocation entityType, int version, byte[] payload) {
        this.providerId = bounded(providerId);
        this.entityType = bounded(entityType);
        Objects.requireNonNull(payload, "payload");
        if (version < 0 || payload.length > MAX_PAYLOAD_BYTES) { throw new IllegalArgumentException("invalid preview bounds"); }
        this.version = version;
        this.payload = payload.clone();
    }
    private static ResourceLocation bounded(ResourceLocation id) {
        String value = Objects.requireNonNull(id, "id").toString();
        if (value.length() > MAX_ID_BYTES || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid preview identifier");
        }
        return id;
    }
    public ResourceLocation getProviderId() { return providerId; }
    public ResourceLocation getEntityType() { return entityType; }
    public int getVersion() { return version; }
    public byte[] copyPayload() { return payload.clone(); }
    public int getPayloadSize() { return payload.length; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MountPreview)) { return false; }
        MountPreview that = (MountPreview) other;
        return providerId.equals(that.providerId) && entityType.equals(that.entityType)
                && version == that.version && Arrays.equals(payload, that.payload);
    }
    @Override public int hashCode() { return Objects.hash(providerId, entityType, version, Arrays.hashCode(payload)); }
}
