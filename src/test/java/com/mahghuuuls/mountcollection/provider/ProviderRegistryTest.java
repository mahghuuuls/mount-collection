package com.mahghuuuls.mountcollection.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class ProviderRegistryTest {

    @Test
    void duplicateIdsAndLateRegistrationAreRejected() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:one", false, false));
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider("test:one", true, false)));
        registry.freeze();
        assertThrows(IllegalStateException.class, () -> registry.register(provider("test:two", true, false)));
    }

    @Test
    void resolutionRequiresFreezeAndRejectsAmbiguity() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:one", true, false));
        assertThrows(IllegalStateException.class, () -> registry.resolveWith(provider -> true));
        registry.register(provider("test:two", true, false));
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(provider -> true);

        assertEquals(ProviderResolution.Status.AMBIGUOUS, resolution.getStatus());
        assertEquals(2, resolution.getInvolvedProviderIds().size());
    }

    @Test
    void providerExceptionBecomesBoundedFailure() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:broken", false, true));
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(provider -> provider.supports(null));

        assertEquals(ProviderResolution.Status.PROVIDER_FAILURE, resolution.getStatus());
        assertEquals(new ResourceLocation("test:broken"), resolution.getInvolvedProviderIds().get(0));
    }

    @Test
    void oneMatchResolvesWithoutRegistrationOrderFallback() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:no", false, false));
        registry.register(provider("test:yes", true, false));
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(
                provider -> new ResourceLocation("test:yes").equals(provider.getProviderId()));

        assertEquals(ProviderResolution.Status.RESOLVED, resolution.getStatus());
        assertEquals(new ResourceLocation("test:yes"), resolution.getProvider().get().getProviderId());
    }

    @Test
    void noMatchProducesUnsupportedOutcome() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:no", false, false));
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(provider -> false);

        assertEquals(ProviderResolution.Status.UNSUPPORTED, resolution.getStatus());
        assertEquals(0, resolution.getInvolvedProviderIds().size());
    }

    @Test
    void resolutionUsesIdCapturedAtRegistration() {
        AtomicReference<ResourceLocation> currentId =
                new AtomicReference<>(new ResourceLocation("test:registered"));
        MountProvider provider = new MountProvider() {
            @Override
            public ResourceLocation getProviderId() {
                return currentId.get();
            }

            @Override
            public boolean supports(Entity entity) {
                return true;
            }

            @Override
            public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, java.util.UUID playerId) {
                return ProviderResult.failure(com.mahghuuuls.mountcollection.api.ProviderFailure.UNSUPPORTED);
            }
        };
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        currentId.set(new ResourceLocation("test:changed"));
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(candidate -> true);

        assertEquals(ProviderResolution.Status.RESOLVED, resolution.getStatus());
        assertEquals(new ResourceLocation("test:registered"), resolution.getInvolvedProviderIds().get(0));
        assertEquals(provider, registry.find(new ResourceLocation("test:registered")).get());
    }

    @Test
    void resolutionDoesNotRecallProviderIdAfterRegistration() {
        MountProvider provider = new MountProvider() {
            private boolean registered;

            @Override
            public ResourceLocation getProviderId() {
                if (registered) {
                    throw new IllegalStateException("provider ID requested after registration");
                }
                registered = true;
                return new ResourceLocation("test:stable");
            }

            @Override
            public boolean supports(Entity entity) {
                return true;
            }

            @Override
            public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, java.util.UUID playerId) {
                return ProviderResult.failure(com.mahghuuuls.mountcollection.api.ProviderFailure.UNSUPPORTED);
            }
        };
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        registry.freeze();

        ProviderResolution resolution = registry.resolveWith(candidate -> true);

        assertEquals(ProviderResolution.Status.RESOLVED, resolution.getStatus());
        assertEquals(new ResourceLocation("test:stable"), resolution.getInvolvedProviderIds().get(0));
    }

    @Test
    void persistedPayloadValidationRequiresFrozenExactProvider() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider("test:present", true, false));
        ProviderPayload current = new ProviderPayload(0, new NBTTagCompound());

        assertThrows(IllegalStateException.class, () -> registry.validatePersistedPayload(
                new ResourceLocation("test:present"), current));
        registry.freeze();

        assertEquals(true, registry.validatePersistedPayload(
                new ResourceLocation("test:present"), current).isSuccess());
        assertEquals(
                ProviderFailure.UNSUPPORTED,
                registry.validatePersistedPayload(
                        new ResourceLocation("test:missing"), current).getFailure().get());
        assertEquals(
                ProviderFailure.INVALID_STATE,
                registry.validatePersistedPayload(
                        new ResourceLocation("test:present"),
                        new ProviderPayload(1, new NBTTagCompound())).getFailure().get());
    }

    @Test
    void providerOwnsPayloadMigrationAndExceptionsAreContained() {
        MountProvider migrating = providerWithPayloadMigration(false);
        MountProvider broken = providerWithPayloadMigration(true);
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(migrating);
        registry.register(broken);
        registry.freeze();
        NBTTagCompound oldData = new NBTTagCompound();
        oldData.setString("old", "preserve");

        ProviderResult<ProviderPayload> migrated = registry.validatePersistedPayload(
                new ResourceLocation("test:migrating"), new ProviderPayload(4, oldData));

        assertEquals(true, migrated.isSuccess());
        assertEquals(5, migrated.getValue().get().getVersion());
        assertEquals("preserve", migrated.getValue().get().copyData().getString("old"));
        assertEquals(
                ProviderFailure.INTERNAL_ERROR,
                registry.validatePersistedPayload(
                        new ResourceLocation("test:broken-payload"),
                        new ProviderPayload(4, oldData)).getFailure().get());
    }

    private static MountProvider providerWithPayloadMigration(boolean broken) {
        return new MountProvider() {
            @Override
            public ResourceLocation getProviderId() {
                return new ResourceLocation(broken ? "test:broken-payload" : "test:migrating");
            }

            @Override
            public boolean supports(Entity entity) {
                return false;
            }

            @Override
            public ProviderResult<RegistrationProfile> validateRegistration(
                    Entity entity, java.util.UUID playerId) {
                return ProviderResult.failure(ProviderFailure.UNSUPPORTED);
            }

            @Override
            public ProviderResult<ProviderPayload> migratePayload(ProviderPayload payload) {
                if (broken) {
                    throw new IllegalStateException("controlled payload failure");
                }
                return ProviderResult.success(
                        new ProviderPayload(payload.getVersion() + 1, payload.copyData()));
            }
        };
    }

    private static MountProvider provider(String id, boolean supported, boolean throwsException) {
        return new MountProvider() {
            @Override
            public ResourceLocation getProviderId() {
                return new ResourceLocation(id);
            }

            @Override
            public boolean supports(Entity entity) {
                if (throwsException) {
                    throw new IllegalStateException("controlled provider failure");
                }
                return supported;
            }

            @Override
            public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, java.util.UUID playerId) {
                return ProviderResult.failure(com.mahghuuuls.mountcollection.api.ProviderFailure.UNSUPPORTED);
            }
        };
    }

}
