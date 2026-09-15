package com.mahghuuuls.mountcollection.api;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import java.util.Objects;
import java.util.UUID;

/**
 * Describes mount-specific meaning without granting lifecycle or persistence authority.
 */
public interface MountProvider {

    ResourceLocation getProviderId();

    boolean supports(Entity entity);

    ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID playerId);

    /** Optional type-based presentation. Must not load entities or access authoritative Recovery state. */
    default MountPreview describePreview(ResourceLocation entityType) { return null; }

    default ProviderResult<ProviderPayload> migratePayload(ProviderPayload payload) {
        ProviderPayload checked = Objects.requireNonNull(payload, "payload");
        return checked.getVersion() == 0
                ? ProviderResult.success(checked)
                : ProviderResult.failure(ProviderFailure.INVALID_STATE);
    }
}
