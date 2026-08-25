package com.mahghuuuls.mountcollection.provider;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.MountProviderRegistrar;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

public final class ProviderRegistry implements MountProviderRegistrar {

    private final Map<ResourceLocation, RegisteredProvider> providers = new LinkedHashMap<>();
    private boolean frozen;

    @Override
    public synchronized void register(MountProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (frozen) {
            throw new IllegalStateException("provider registry is frozen");
        }
        ResourceLocation providerId = Objects.requireNonNull(provider.getProviderId(), "providerId");
        if (providers.containsKey(providerId)) {
            throw new IllegalArgumentException("duplicate provider id: " + providerId);
        }
        providers.put(providerId, new RegisteredProvider(providerId, provider));
    }

    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized int size() {
        return providers.size();
    }

    public synchronized Optional<MountProvider> find(ResourceLocation providerId) {
        RegisteredProvider registered = providers.get(providerId);
        return registered == null
                ? Optional.empty()
                : Optional.of(registered.provider);
    }

    public ProviderResult<ProviderPayload> validatePersistedPayload(
            ResourceLocation providerId, ProviderPayload payload) {
        MountProvider provider;
        synchronized (this) {
            if (!frozen) {
                throw new IllegalStateException(
                        "provider registry must be frozen before persisted payload validation");
            }
            RegisteredProvider registered = providers.get(providerId);
            if (registered == null) {
                return ProviderResult.failure(ProviderFailure.UNSUPPORTED);
            }
            provider = registered.provider;
        }
        try {
            ProviderResult<ProviderPayload> result = provider.migratePayload(payload);
            if (result == null) {
                return ProviderResult.failure(ProviderFailure.INTERNAL_ERROR);
            }
            if (result.isSuccess() && !result.getValue().isPresent()) {
                return ProviderResult.failure(ProviderFailure.INTERNAL_ERROR);
            }
            return result;
        } catch (RuntimeException exception) {
            return ProviderResult.failure(ProviderFailure.INTERNAL_ERROR);
        }
    }

    public synchronized List<ResourceLocation> getProviderIds() {
        return Collections.unmodifiableList(new ArrayList<>(providers.keySet()));
    }

    public ProviderResolution resolve(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return resolveWith(provider -> provider.supports(entity));
    }

    ProviderResolution resolveWith(ProviderMatcher matcher) {
        Objects.requireNonNull(matcher, "matcher");
        List<RegisteredProvider> snapshot;
        synchronized (this) {
            if (!frozen) {
                throw new IllegalStateException("provider registry must be frozen before resolution");
            }
            snapshot = new ArrayList<>(providers.values());
        }

        List<RegisteredProvider> matches = new ArrayList<>();
        for (RegisteredProvider registered : snapshot) {
            try {
                if (matcher.matches(registered.provider)) {
                    matches.add(registered);
                }
            } catch (RuntimeException exception) {
                return ProviderResolution.providerFailure(registered.providerId);
            }
        }
        if (matches.isEmpty()) {
            return ProviderResolution.unsupported();
        }
        if (matches.size() == 1) {
            RegisteredProvider match = matches.get(0);
            return ProviderResolution.resolved(match.provider, match.providerId);
        }
        List<ResourceLocation> ids = new ArrayList<>();
        for (RegisteredProvider match : matches) {
            ids.add(match.providerId);
        }
        return ProviderResolution.ambiguous(ids);
    }

    private static final class RegisteredProvider {
        private final ResourceLocation providerId;
        private final MountProvider provider;

        private RegisteredProvider(ResourceLocation providerId, MountProvider provider) {
            this.providerId = providerId;
            this.provider = provider;
        }
    }

    interface ProviderMatcher {
        boolean matches(MountProvider provider);
    }
}
