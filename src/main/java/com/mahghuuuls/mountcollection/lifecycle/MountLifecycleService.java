package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ActiveTimeResult;
import com.mahghuuuls.mountcollection.policy.RecallPolicy;
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
    private final ActiveServerClock clock;
    private final InhibitedIntegration inhibitedIntegration;
    private final RecallWorldGateway worldGateway;
    private final RecallPolicy recallPolicy = new RecallPolicy();

    MountLifecycleService(
            MountRepository repository,
            ProviderRegistry providers,
            Supplier<ValidatedMountConfig> configSupplier,
            DiagnosticSink diagnostics) {
        this(repository, providers, configSupplier, diagnostics, new ActiveServerClock(),
                new InhibitedIntegration(), null);
    }

    public MountLifecycleService(
            MountRepository repository,
            ProviderRegistry providers,
            Supplier<ValidatedMountConfig> configSupplier,
            DiagnosticSink diagnostics,
            ActiveServerClock clock,
            InhibitedIntegration inhibitedIntegration,
            RecallWorldGateway worldGateway) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.inhibitedIntegration = Objects.requireNonNull(inhibitedIntegration, "inhibitedIntegration");
        this.worldGateway = worldGateway;
    }

    public ContextualOutcome handleContextualIntent(EntityPlayerMP player) {
        UUID correlationId = UUID.randomUUID();
        Entity ridden = player.getRidingEntity();
        if (ridden == null) {
            return recall(correlationId, player);
        }

        ProviderResolution resolution = providers.resolve(ridden);
        if (resolution.getStatus() != ProviderResolution.Status.RESOLVED) {
            return contextualFinish(correlationId, "registration", null,
                    fromRegistration(fromResolutionFailure(resolution.getStatus())));
        }
        MountProvider provider = resolution.getProvider().get();
        String providerId = resolution.getInvolvedProviderIds().get(0).toString();
        ProviderResult<RegistrationProfile> providerResult;
        try {
            providerResult = provider.validateRegistration(ridden, player.getUniqueID());
        } catch (RuntimeException exception) {
            return contextualFinish(correlationId, "registration", providerId,
                    ContextualOutcome.failure(ContextualOutcome.Status.PROVIDER_FAILURE));
        }
        if (providerResult == null || !providerResult.isSuccess() || !providerResult.getValue().isPresent()) {
            ProviderFailure failure = providerResult == null || !providerResult.getFailure().isPresent()
                    ? ProviderFailure.INTERNAL_ERROR
                    : providerResult.getFailure().get();
            return contextualFinish(correlationId, "registration", providerId,
                    fromRegistration(fromProviderFailure(failure)));
        }

        RegistrationProfile profile = providerResult.getValue().get();
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(ridden);
        if (evidence.getStatus() == EntityMountEvidence.Status.MALFORMED) {
            return contextualFinish(correlationId, "registration", providerId,
                    ContextualOutcome.failure(ContextualOutcome.Status.INTEGRITY_CONFLICT));
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
        return fromRegistration(outcome);
    }

    private ContextualOutcome recall(UUID correlationId, EntityPlayerMP player) {
        ValidatedMountConfig config = configSupplier.get();
        InhibitedStatus inhibited = inhibitedIntegration.getStatus(
                player, config.isInhibitedRecallBlockingEnabled());
        return recallVerified(
                correlationId, player, player.getUniqueID(), player.dimension, inhibited, config);
    }

    ContextualOutcome recallVerified(
            UUID correlationId,
            EntityPlayerMP player,
            UUID ownerId,
            int destinationDimension,
            InhibitedStatus inhibited,
            ValidatedMountConfig config) {
        MountRepository.CollectionInspection collection =
                repository.inspectCollection(ownerId);
        if (!collection.getSelectedMountId().isPresent()) {
            return contextualFinish(correlationId, "recall", null,
                    ContextualOutcome.failure(ContextualOutcome.Status.NO_SELECTION));
        }
        MountId mountId = collection.getSelectedMountId().get();
        MountRecord record = repository.find(mountId).orElse(null);
        if (record == null || !ownerId.equals(record.getOwnerId())) {
            return contextualFinish(correlationId, "recall", null,
                    ContextualOutcome.failure(ContextualOutcome.Status.INTEGRITY_CONFLICT));
        }
        MountProvider provider = providers.find(record.getProviderId()).orElse(null);
        if (record.getCondition()
                == com.mahghuuuls.mountcollection.persistence.MountCondition.INTEGRITY_BLOCKED) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTEGRITY_CONFLICT));
        }
        if (record.getCondition()
                        != com.mahghuuuls.mountcollection.persistence.MountCondition.LIVING
                || provider == null) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.PROVIDER_UNAVAILABLE));
        }
        if (worldGateway == null) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        RecallWorldGateway.LocateResult located = worldGateway.locate(player, record);
        if (located == null || located.getStatus() != RecallWorldGateway.LocateResult.Status.FOUND) {
            ContextualOutcome.Status unavailable = located != null
                            && located.getStatus()
                                    == RecallWorldGateway.LocateResult.Status.INTEGRITY_CONFLICT
                    ? ContextualOutcome.Status.INTEGRITY_CONFLICT
                    : ContextualOutcome.Status.MOUNT_MISSING;
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(unavailable));
        }
        RecallWorldGateway.Source source = located.getSource().get();
        if (!record.getPhysicalEntityId().equals(source.getPhysicalEntityId())
                || source.getDimensionId() != destinationDimension) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.MOUNT_MISSING));
        }
        MountRepository.CooldownState cooldown = repository.getRecallCooldown(ownerId);
        long cooldownBound = cooldown.getDuration() > 0L
                ? cooldown.getDuration()
                : config.getSummonCooldownTicks();
        ActiveTimeResult remaining = clock.remainingUntil(cooldown.getDeadline(), cooldownBound);
        if (remaining.getStatus() != ActiveTimeResult.Status.VALID) {
            Map<String, String> clockFields = new LinkedHashMap<>();
            clockFields.put("correlation", correlationId.toString());
            clockFields.put("status", remaining.getStatus().name());
            clockFields.put("bounded_remaining", Long.toString(remaining.getValue()));
            diagnostics.detail(DiagnosticCategory.LIFECYCLE, "active_time_anomaly", clockFields);
            if (remaining.getStatus() == ActiveTimeResult.Status.BOUNDED_CLAMP
                    && repository.normalizeRecallCooldown(
                            ownerId, clock.now(), config.getSummonCooldownTicks())) {
                cooldown = repository.getRecallCooldown(ownerId);
                remaining = clock.remainingUntil(cooldown.getDeadline(), cooldown.getDuration());
            }
        }
        ContextualOutcome.Status denial = recallPolicy.evaluate(
                record,
                worldGateway.providerSupports(source, provider),
                source.hasPassengers(),
                destinationDimension,
                inhibited,
                remaining.getValue(),
                config);
        if (denial != null) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(denial));
        }
        java.util.Optional<RecallWorldGateway.Destination> destination = worldGateway.plan(
                player, source, provider, config.getNormalPlacementRadius(),
                config.getFallbackPlacementRadius());
        if (!destination.isPresent()) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.NO_SAFE_DESTINATION));
        }
        ActiveTimeResult deadline = clock.deadlineAfter(config.getSummonCooldownTicks());
        if (!deadline.isValid()) {
            Map<String, String> clockFields = new LinkedHashMap<>();
            clockFields.put("correlation", correlationId.toString());
            clockFields.put("status", deadline.getStatus().name());
            diagnostics.detail(DiagnosticCategory.LIFECYCLE, "active_time_anomaly", clockFields);
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        MountRepository.RecallCommit recallCommit = repository.prepareRecall(
                ownerId, mountId, source.getPhysicalEntityId()).orElse(null);
        if (recallCommit == null) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        if (!worldGateway.commit(player, source, destination.get(), provider)) {
            recallCommit.cancel();
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        recallCommit.complete(
                destination.get().getEvidence(),
                deadline.getValue(),
                config.getSummonCooldownTicks());
        Map<String, String> commitFields = new LinkedHashMap<>();
        commitFields.put("correlation", correlationId.toString());
        commitFields.put("mount", mountId.toString());
        commitFields.put("cooldown_deadline", Long.toString(deadline.getValue()));
        commitFields.put("dimension", Integer.toString(destinationDimension));
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, "recall_commit", commitFields);
        return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                ContextualOutcome.success(ContextualOutcome.Status.RECALLED, mountId));
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

    private ContextualOutcome contextualFinish(
            UUID correlationId, String operation, String providerId, ContextualOutcome outcome) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", correlationId.toString());
        fields.put("outcome", outcome.getStatus().name());
        if (providerId != null) {
            fields.put("provider", providerId);
        }
        if (outcome.getMountId().isPresent()) {
            fields.put("mount", outcome.getMountId().get().toString());
        }
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, operation, fields);
        return outcome;
    }

    private static ContextualOutcome fromRegistration(RegistrationOutcome outcome) {
        switch (outcome.getStatus()) {
            case SUCCESS:
                return ContextualOutcome.success(
                        ContextualOutcome.Status.REGISTERED, outcome.getMountId().get());
            case UNSUPPORTED:
                return ContextualOutcome.failure(ContextualOutcome.Status.UNSUPPORTED);
            case NOT_TAMED:
                return ContextualOutcome.failure(ContextualOutcome.Status.NOT_TAMED);
            case NOT_OWNED:
                return ContextualOutcome.failure(ContextualOutcome.Status.NOT_OWNED);
            case DISALLOWED:
                return ContextualOutcome.failure(ContextualOutcome.Status.REGISTRATION_DISALLOWED);
            case ALREADY_REGISTERED:
                return ContextualOutcome.failure(ContextualOutcome.Status.ALREADY_REGISTERED);
            case OWNED_BY_OTHER:
                return ContextualOutcome.failure(ContextualOutcome.Status.OWNED_BY_OTHER);
            case INTEGRITY_CONFLICT:
                return ContextualOutcome.failure(ContextualOutcome.Status.INTEGRITY_CONFLICT);
            case PROVIDER_FAILURE:
                return ContextualOutcome.failure(ContextualOutcome.Status.PROVIDER_FAILURE);
            case READ_ONLY:
                return ContextualOutcome.failure(ContextualOutcome.Status.READ_ONLY);
            default:
                return ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE);
        }
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
