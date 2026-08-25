package com.mahghuuuls.mountcollection.provider;

import com.mahghuuuls.mountcollection.api.MountProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.ResourceLocation;

public final class ProviderResolution {

    public enum Status {
        RESOLVED,
        UNSUPPORTED,
        AMBIGUOUS,
        PROVIDER_FAILURE
    }

    private final Status status;
    private final MountProvider provider;
    private final List<ResourceLocation> involvedProviderIds;

    private ProviderResolution(Status status, MountProvider provider, List<ResourceLocation> involvedProviderIds) {
        this.status = status;
        this.provider = provider;
        this.involvedProviderIds = Collections.unmodifiableList(new ArrayList<>(involvedProviderIds));
    }

    static ProviderResolution resolved(MountProvider provider, ResourceLocation registeredProviderId) {
        return new ProviderResolution(
                Status.RESOLVED,
                provider,
                Collections.singletonList(registeredProviderId));
    }

    static ProviderResolution unsupported() {
        return new ProviderResolution(Status.UNSUPPORTED, null, Collections.<ResourceLocation>emptyList());
    }

    static ProviderResolution ambiguous(List<ResourceLocation> providerIds) {
        return new ProviderResolution(Status.AMBIGUOUS, null, providerIds);
    }

    static ProviderResolution providerFailure(ResourceLocation providerId) {
        return new ProviderResolution(Status.PROVIDER_FAILURE, null, Collections.singletonList(providerId));
    }

    public Status getStatus() {
        return status;
    }

    public Optional<MountProvider> getProvider() {
        return Optional.ofNullable(provider);
    }

    public List<ResourceLocation> getInvolvedProviderIds() {
        return involvedProviderIds;
    }
}
