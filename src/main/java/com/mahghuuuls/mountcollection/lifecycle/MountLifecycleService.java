package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import com.mahghuuuls.mountcollection.provider.ProviderResolution;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

public final class MountLifecycleService {

    private final MountRepository repository;
    private final ProviderRegistry providers;
    private final Supplier<ValidatedMountConfig> configSupplier;
    private final DiagnosticSink diagnostics;

    public MountLifecycleService(
            MountRepository repository,
            ProviderRegistry providers,
            Supplier<ValidatedMountConfig> configSupplier,
            DiagnosticSink diagnostics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public RegistrationOutcome handleContextualIntent(EntityPlayerMP player) {
        UUID correlationId = UUID.randomUUID();
        Entity ridden = player.getRidingEntity();
        if (ridden == null) {
            return finish(correlationId, null, RegistrationOutcome.failure(
                    RegistrationOutcome.Status.NO_RIDDEN_ENTITY));
        }

        ProviderResolution resolution = providers.resolve(ridden);
        if (resolution.getStatus() != ProviderResolution.Status.RESOLVED) {
            return finish(correlationId, null, fromResolutionFailure(resolution.getStatus()));
        }
        MountProvider provider = resolution.getProvider().get();
        String providerId = resolution.getInvolvedProviderIds().get(0).toString();
        ProviderResult<RegistrationProfile> providerResult;
        try {
            providerResult = provider.validateRegistration(ridden, player.getUniqueID());
        } catch (RuntimeException exception) {
            return finish(correlationId, providerId, RegistrationOutcome.failure(
                    RegistrationOutcome.Status.PROVIDER_FAILURE));
        }
        if (providerResult == null || !providerResult.isSuccess() || !providerResult.getValue().isPresent()) {
            ProviderFailure failure = providerResult == null || !providerResult.getFailure().isPresent()
                    ? ProviderFailure.INTERNAL_ERROR
                    : providerResult.getFailure().get();
            return finish(correlationId, providerId, fromProviderFailure(failure));
        }

        RegistrationProfile profile = providerResult.getValue().get();
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(ridden);
        if (evidence.getStatus() == EntityMountEvidence.Status.MALFORMED) {
            return finish(correlationId, providerId, RegistrationOutcome.failure(
                    RegistrationOutcome.Status.INTEGRITY_CONFLICT));
        }
        RegistrationOutcome outcome = commitVerifiedRegistration(
                correlationId,
                player.getUniqueID(),
                resolution.getInvolvedProviderIds().get(0),
                profile,
                ridden.getUniqueID(),
                evidenceFor(ridden),
                evidence.getMountId().orElse(null));
        if (outcome.getStatus() == RegistrationOutcome.Status.SUCCESS) {
            try {
                EntityMountEvidence.attach(ridden, outcome.getMountId().get());
            } catch (RuntimeException exception) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("correlation", correlationId.toString());
                fields.put("outcome", "CORROBORATION_PENDING");
                fields.put("mount", outcome.getMountId().get().toString());
                diagnostics.detail(
                        DiagnosticCategory.PROVIDER,
                        "registration_entity_evidence",
                        fields);
            }
        }
        return outcome;
    }

    RegistrationOutcome commitVerifiedRegistration(
            UUID correlationId,
            UUID ownerId,
            net.minecraft.util.ResourceLocation providerId,
            RegistrationProfile profile,
            UUID physicalEntityId,
            LastKnownEvidence lastKnown,
            MountId claimedMountId) {
        MountRepository.RegistrationCandidate candidate = new MountRepository.RegistrationCandidate(
                ownerId,
                providerId,
                profile.getEntityTypeId(),
                profile.getFallbackTypeKey(),
                physicalEntityId,
                lastKnown,
                claimedMountId,
                profile.getProviderPayload());
        MountRepository.RegistrationStatus preflight = repository.preflightRegistration(candidate);
        if (preflight != MountRepository.RegistrationStatus.SUCCESS) {
            return finish(correlationId, providerId.toString(), fromRepositoryFailure(preflight));
        }
        ValidatedMountConfig config = configSupplier.get();
        if (!config.getRegistrationEntities().allows(profile.getEntityTypeId())) {
            return finish(correlationId, providerId.toString(), RegistrationOutcome.failure(
                    RegistrationOutcome.Status.DISALLOWED));
        }
        MountRepository.RegistrationResult result = repository.register(candidate);
        if (result.getStatus() != MountRepository.RegistrationStatus.SUCCESS) {
            return finish(
                    correlationId,
                    providerId.toString(),
                    fromRepositoryFailure(result.getStatus()));
        }
        MountRecord record = result.getRecord().get();
        return finish(correlationId, providerId.toString(), RegistrationOutcome.success(record.getMountId()));
    }

    private RegistrationOutcome finish(
            UUID correlationId, String providerId, RegistrationOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", correlationId.toString());
        fields.put("outcome", outcome.getStatus().name());
        if (providerId != null) {
            fields.put("provider", providerId);
        }
        if (outcome.getMountId().isPresent()) {
            fields.put("mount", outcome.getMountId().get().toString());
        }
        diagnostics.detail(DiagnosticCategory.PROVIDER, "registration", fields);
        return outcome;
    }

    private static LastKnownEvidence evidenceFor(Entity entity) {
        return new LastKnownEvidence(entity.dimension, entity.posX, entity.posY, entity.posZ);
    }

    private static RegistrationOutcome fromResolutionFailure(ProviderResolution.Status status) {
        if (status == ProviderResolution.Status.UNSUPPORTED) {
            return RegistrationOutcome.failure(RegistrationOutcome.Status.UNSUPPORTED);
        }
        if (status == ProviderResolution.Status.AMBIGUOUS) {
            return RegistrationOutcome.failure(RegistrationOutcome.Status.INTEGRITY_CONFLICT);
        }
        return RegistrationOutcome.failure(RegistrationOutcome.Status.PROVIDER_FAILURE);
    }

    private static RegistrationOutcome fromProviderFailure(ProviderFailure failure) {
        if (failure == ProviderFailure.UNSUPPORTED) {
            return RegistrationOutcome.failure(RegistrationOutcome.Status.UNSUPPORTED);
        }
        if (failure == ProviderFailure.NOT_TAMED) {
            return RegistrationOutcome.failure(RegistrationOutcome.Status.NOT_TAMED);
        }
        if (failure == ProviderFailure.NOT_OWNED) {
            return RegistrationOutcome.failure(RegistrationOutcome.Status.NOT_OWNED);
        }
        return RegistrationOutcome.failure(RegistrationOutcome.Status.PROVIDER_FAILURE);
    }

    private static RegistrationOutcome fromRepositoryFailure(MountRepository.RegistrationStatus status) {
        switch (status) {
            case DUPLICATE_ENTITY:
                return RegistrationOutcome.failure(RegistrationOutcome.Status.ALREADY_REGISTERED);
            case OWNED_BY_OTHER:
                return RegistrationOutcome.failure(RegistrationOutcome.Status.OWNED_BY_OTHER);
            case INTEGRITY_CONFLICT:
                return RegistrationOutcome.failure(RegistrationOutcome.Status.INTEGRITY_CONFLICT);
            case READ_ONLY:
                return RegistrationOutcome.failure(RegistrationOutcome.Status.READ_ONLY);
            default:
                return RegistrationOutcome.failure(RegistrationOutcome.Status.INTERNAL_FAILURE);
        }
    }
}
