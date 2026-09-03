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
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
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
    private boolean reconcilingTransfers;

    private enum TransferAdvanceOutcome {
        COMPLETE,
        PENDING,
        TEMPORARILY_UNAVAILABLE,
        PERSISTENCE_FAILURE,
        INTEGRITY_CONFLICT
    }

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
        if (record.getCondition() == MountCondition.INTEGRITY_BLOCKED) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTEGRITY_CONFLICT));
        }
        if (record.getCondition() == MountCondition.OPERATION_IN_PROGRESS) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.OPERATION_IN_PROGRESS));
        }
        if (record.getCondition() != MountCondition.LIVING
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
            ContextualOutcome.Status unavailable = ContextualOutcome.Status.MOUNT_MISSING;
            if (located != null
                    && located.getStatus() == RecallWorldGateway.LocateResult.Status.INTEGRITY_CONFLICT) {
                unavailable = ContextualOutcome.Status.INTEGRITY_CONFLICT;
            } else if (located != null
                    && located.getStatus() == RecallWorldGateway.LocateResult.Status.UNAVAILABLE) {
                unavailable = ContextualOutcome.Status.INTERNAL_FAILURE;
            }
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(unavailable));
        }
        RecallWorldGateway.Source source = located.getSource().get();
        if (!record.getPhysicalEntityId().equals(source.getPhysicalEntityId())) {
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
        if (source.getDimensionId() != destinationDimension) {
            return transferAcrossDimensions(
                    correlationId, player, ownerId, record, source, destination.get(),
                    provider, deadline.getValue(), config.getSummonCooldownTicks());
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

    public void reconcilePendingTransfers() {
        if (worldGateway == null || reconcilingTransfers) {
            return;
        }
        reconcilingTransfers = true;
        try {
            for (TransferOperation operation : repository.getPendingTransfers()) {
                if (operation.getPhase() == TransferPhase.INTEGRITY_BLOCKED) {
                    continue;
                }
                advanceTransfer(operation.getOperationId(), true, true);
            }
        } finally {
            reconcilingTransfers = false;
        }
    }

    public void reconcilePendingTransfer(UUID operationId) {
        reconcilePendingTransfer(operationId, true, true);
    }

    public void reconcilePendingTransfer(
            UUID operationId, boolean sourceObserved, boolean candidateObserved) {
        if (worldGateway == null || operationId == null || reconcilingTransfers) {
            return;
        }
        reconcilingTransfers = true;
        try {
            TransferOperation operation = repository.findTransfer(operationId).orElse(null);
            if (operation != null && operation.getPhase() != TransferPhase.INTEGRITY_BLOCKED) {
                advanceTransfer(operationId, sourceObserved, candidateObserved);
            }
        } finally {
            reconcilingTransfers = false;
        }
    }

    private ContextualOutcome transferAcrossDimensions(
            UUID correlationId,
            EntityPlayerMP player,
            UUID ownerId,
            MountRecord record,
            RecallWorldGateway.Source source,
            RecallWorldGateway.Destination destination,
            MountProvider provider,
            long cooldownDeadline,
            long cooldownDuration) {
        java.util.Optional<RecallWorldGateway.TransferPlan> planned =
                worldGateway.captureTransfer(player, source, destination, provider);
        if (!planned.isPresent()) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        RecallWorldGateway.TransferPlan transferPlan = planned.get();
        TransferOperation operation = new TransferOperation(
                correlationId,
                record.getMountId(),
                ownerId,
                source.getPhysicalEntityId(),
                transferPlan.getCandidateEntityId(),
                transferPlan.getSourceEvidence(),
                destination.getEvidence(),
                transferPlan.copySourceSnapshot(),
                cooldownDeadline,
                cooldownDuration,
                TransferPhase.PREPARED,
                null);
        MountRepository.TransferStatus began = repository.beginTransfer(operation);
        if (began != MountRepository.TransferStatus.SUCCESS) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(fromTransferStatus(began)));
        }
        recordTransferPhase(operation, TransferPhase.PREPARED);
        if (worldGateway.pauseAfterPhase(TransferPhase.PREPARED)) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        MountRepository.TransferStatus intentRecorded =
                repository.markCandidateSpawnIntent(operation.getOperationId());
        if (intentRecorded != MountRepository.TransferStatus.SUCCESS) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(fromTransferStatus(intentRecorded)));
        }
        recordTransferPhase(operation, TransferPhase.CANDIDATE_SPAWN_INTENT);
        operation = repository.findTransfer(operation.getOperationId()).orElse(operation);
        worldGateway.transferPhaseAcknowledged(TransferPhase.CANDIDATE_SPAWN_INTENT);
        RecallWorldGateway.CandidateAction spawned = worldGateway.spawnCandidate(operation);
        if (spawned != RecallWorldGateway.CandidateAction.SUCCESS) {
            TransferAdvanceOutcome rollback = rollbackSpawnFailure(operation, spawned);
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(fromTransferOutcome(rollback)));
        }
        RecallWorldGateway.CheckpointStatus candidateCheckpoint =
                worldGateway.checkpointCandidate(operation, true);
        if (candidateCheckpoint != RecallWorldGateway.CheckpointStatus.VERIFIED) {
            TransferAdvanceOutcome outcome = fromCheckpoint(
                    operation, candidateCheckpoint, "candidate checkpoint contradicted identity");
            TransferAdvanceOutcome containment = containCandidateIntent(operation);
            if (containment == TransferAdvanceOutcome.INTEGRITY_CONFLICT
                    || containment == TransferAdvanceOutcome.PERSISTENCE_FAILURE) {
                outcome = containment;
            }
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(fromTransferOutcome(outcome)));
        }
        MountRepository.TransferStatus candidateRecorded =
                repository.markCandidateSpawned(operation.getOperationId());
        if (candidateRecorded != MountRepository.TransferStatus.SUCCESS) {
            TransferAdvanceOutcome containment = containCandidateIntent(operation);
            ContextualOutcome.Status status = containment == TransferAdvanceOutcome.INTEGRITY_CONFLICT
                    ? ContextualOutcome.Status.INTEGRITY_CONFLICT
                    : fromTransferStatus(candidateRecorded);
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(status));
        }
        recordTransferPhase(operation, TransferPhase.CANDIDATE_SPAWNED);
        if (worldGateway.pauseAfterPhase(TransferPhase.CANDIDATE_SPAWNED)) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE));
        }
        TransferAdvanceOutcome advanced = advanceTransfer(operation.getOperationId(), true, true);
        if (advanced != TransferAdvanceOutcome.COMPLETE) {
            return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                    ContextualOutcome.failure(fromTransferOutcome(advanced)));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", correlationId.toString());
        fields.put("mount", record.getMountId().toString());
        fields.put("source_dimension", Integer.toString(source.getDimensionId()));
        fields.put("destination_dimension", Integer.toString(destination.getEvidence().getDimensionId()));
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, "transfer_commit", fields);
        return contextualFinish(correlationId, "recall", record.getProviderId().toString(),
                ContextualOutcome.success(ContextualOutcome.Status.RECALLED, record.getMountId()));
    }

    private TransferAdvanceOutcome rollbackSpawnFailure(
            TransferOperation operation, RecallWorldGateway.CandidateAction action) {
        if (action == RecallWorldGateway.CandidateAction.CONFLICT) {
            return blockTransfer(operation.getOperationId(), "candidate UUID conflict during spawn");
        }
        if (action == RecallWorldGateway.CandidateAction.UNAVAILABLE) {
            TransferAdvanceOutcome cancelled = fromRepositoryProgress(
                    repository.cancelTransfer(operation.getOperationId()));
            if (cancelled == TransferAdvanceOutcome.PENDING) {
                return TransferAdvanceOutcome.TEMPORARILY_UNAVAILABLE;
            }
            return blockTransfer(
                    operation.getOperationId(), "candidate intent could not be rolled back");
        }
        TransferAdvanceOutcome containment = containCandidateIntent(operation);
        if (containment == TransferAdvanceOutcome.COMPLETE) {
            return TransferAdvanceOutcome.PENDING;
        }
        return containment;
    }

    private TransferAdvanceOutcome containCandidateIntent(TransferOperation operation) {
        RecallWorldGateway.TransferEvidence evidence = worldGateway.inspectTransfer(operation);
        if (evidence.hasConflict()
                || evidence.getSource() != RecallWorldGateway.TransferEvidence.Presence.EXACT
                || evidence.getCandidate() == RecallWorldGateway.TransferEvidence.Presence.FINALIZED
                || evidence.getCandidate() == RecallWorldGateway.TransferEvidence.Presence.UNAVAILABLE) {
            return blockTransfer(
                    operation.getOperationId(), "candidate intent could not be contained safely");
        }
        if (evidence.getCandidate() == RecallWorldGateway.TransferEvidence.Presence.EXACT) {
            RecallWorldGateway.PhysicalAction removal = worldGateway.removeCandidate(operation);
            if (removal != RecallWorldGateway.PhysicalAction.SUCCESS) {
                return blockTransfer(
                        operation.getOperationId(), "candidate intent cleanup did not complete");
            }
        } else if (evidence.getCandidate()
                != RecallWorldGateway.TransferEvidence.Presence.MISSING) {
            return blockTransfer(
                    operation.getOperationId(), "candidate intent evidence is invalid");
        }
        RecallWorldGateway.CheckpointStatus absent = worldGateway.checkpointCandidateAbsent(operation);
        if (absent != RecallWorldGateway.CheckpointStatus.VERIFIED) {
            return blockTransfer(
                    operation.getOperationId(), "candidate intent absence could not be fenced");
        }
        MountRepository.TransferStatus cancelled =
                repository.cancelTransfer(operation.getOperationId());
        if (cancelled == MountRepository.TransferStatus.SUCCESS) {
            return TransferAdvanceOutcome.COMPLETE;
        }
        return blockTransfer(
                operation.getOperationId(), "candidate intent rollback was not acknowledged");
    }

    private TransferAdvanceOutcome advanceTransfer(
            UUID operationId, boolean sourceObserved, boolean candidateObserved) {
        for (int guard = 0; guard < 12; guard++) {
            TransferOperation operation = repository.findTransfer(operationId).orElse(null);
            if (operation == null) {
                return TransferAdvanceOutcome.COMPLETE;
            }
            if (!operation.getPhase().isActionIntent()
                    && worldGateway.pauseAfterPhase(operation.getPhase())) {
                return TransferAdvanceOutcome.PENDING;
            }
            if (!isRecoveryReady(operation.getPhase(), sourceObserved, candidateObserved)) {
                return TransferAdvanceOutcome.PENDING;
            }
            boolean inspectSource = operation.getPhase() != TransferPhase.SOURCE_REMOVED;
            boolean inspectCandidate = operation.getPhase() != TransferPhase.PREPARED;
            RecallWorldGateway.TransferEvidence evidence =
                    worldGateway.inspectTransfer(operation, inspectSource, inspectCandidate);
            if (evidence.hasConflict()) {
                return blockTransfer(operationId, "conflicting physical transfer evidence");
            }
            if ((inspectSource
                            && evidence.getSource()
                                    == RecallWorldGateway.TransferEvidence.Presence.UNAVAILABLE)
                    || (inspectCandidate
                            && evidence.getCandidate()
                                    == RecallWorldGateway.TransferEvidence.Presence.UNAVAILABLE)) {
                return TransferAdvanceOutcome.TEMPORARILY_UNAVAILABLE;
            }
            MountRepository.TransferStatus evidenceUpdate = updateRelocatedEvidence(operation, evidence);
            if (evidenceUpdate != null) {
                if (evidenceUpdate == MountRepository.TransferStatus.SUCCESS) {
                    continue;
                }
                if (operation.getPhase().isActionIntent()) {
                    return blockTransfer(
                            operationId,
                            "action-intent evidence correction was not acknowledged");
                }
                return fromRepositoryProgress(evidenceUpdate);
            }
            switch (operation.getPhase()) {
                case PREPARED:
                    if (evidence.getSource() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    return fromRepositoryProgress(repository.cancelTransfer(operationId));
                case CANDIDATE_SPAWN_INTENT:
                    if (evidence.getSource() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    if (evidence.getCandidate() == RecallWorldGateway.TransferEvidence.Presence.MISSING) {
                        TransferAdvanceOutcome candidateAbsent = fromCheckpoint(
                                operation,
                                worldGateway.checkpointCandidateAbsent(operation),
                                "candidate intent absence checkpoint contradicted identity");
                        if (candidateAbsent != TransferAdvanceOutcome.PENDING) {
                            TransferAdvanceOutcome containment = containCandidateIntent(operation);
                            return containment == TransferAdvanceOutcome.COMPLETE
                                    ? candidateAbsent
                                    : containment;
                        }
                        MountRepository.TransferStatus cancelled =
                                repository.cancelTransfer(operationId);
                        if (cancelled == MountRepository.TransferStatus.SUCCESS) {
                            return TransferAdvanceOutcome.PENDING;
                        }
                        return blockTransfer(
                                operationId,
                                "candidate intent cancellation was not acknowledged");
                    }
                    if (evidence.getCandidate() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return blockTransfer(operationId, "candidate intent evidence is invalid");
                    }
                    TransferAdvanceOutcome intentCandidateCheckpoint = fromCheckpoint(
                            operation,
                            worldGateway.checkpointCandidate(operation, true),
                            "candidate intent checkpoint contradicted identity");
                    if (intentCandidateCheckpoint != TransferAdvanceOutcome.PENDING) {
                        TransferAdvanceOutcome containment = containCandidateIntent(operation);
                        return containment == TransferAdvanceOutcome.COMPLETE
                                ? intentCandidateCheckpoint
                                : containment;
                    }
                    TransferAdvanceOutcome spawnedRecorded = fromRepositoryProgress(
                            repository.markCandidateSpawned(operationId));
                    if (spawnedRecorded != TransferAdvanceOutcome.PENDING) {
                        TransferAdvanceOutcome containment = containCandidateIntent(operation);
                        return containment == TransferAdvanceOutcome.COMPLETE
                                ? spawnedRecorded
                                : containment;
                    }
                    recordTransferPhase(operation, TransferPhase.CANDIDATE_SPAWNED);
                    break;
                case CANDIDATE_SPAWNED:
                    if (evidence.getCandidate() != RecallWorldGateway.TransferEvidence.Presence.EXACT
                            || evidence.getSource() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    TransferAdvanceOutcome candidateCheckpoint = fromCheckpoint(
                            operation,
                            worldGateway.checkpointCandidate(operation, true),
                            "candidate checkpoint contradicted identity");
                    if (candidateCheckpoint != TransferAdvanceOutcome.PENDING) {
                        return candidateCheckpoint;
                    }
                    TransferAdvanceOutcome associated = fromRepositoryProgress(
                            repository.associateTransferCandidate(operationId));
                    if (associated != TransferAdvanceOutcome.PENDING) {
                        return associated;
                    }
                    recordTransferPhase(operation, TransferPhase.ASSOCIATED);
                    if (worldGateway.pauseAfterPhase(TransferPhase.ASSOCIATED)) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    break;
                case ASSOCIATED:
                    if (evidence.getCandidate() != RecallWorldGateway.TransferEvidence.Presence.EXACT
                            || evidence.getSource() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    TransferAdvanceOutcome validatedRemoval = fromPhysicalAction(
                            operation,
                            worldGateway.validateSourceRemoval(operation),
                            "source removal preparation contradicted identity");
                    if (validatedRemoval != TransferAdvanceOutcome.PENDING) {
                        return validatedRemoval;
                    }
                    TransferAdvanceOutcome removalIntent = fromRepositoryProgress(
                            repository.markSourceRemovalIntent(operationId));
                    if (removalIntent != TransferAdvanceOutcome.PENDING) {
                        return removalIntent;
                    }
                    recordTransferPhase(operation, TransferPhase.SOURCE_REMOVAL_INTENT);
                    worldGateway.transferPhaseAcknowledged(TransferPhase.SOURCE_REMOVAL_INTENT);
                    break;
                case SOURCE_REMOVAL_INTENT:
                    if (evidence.getCandidate() != RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    if (evidence.getSource() == RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        RecallWorldGateway.PhysicalAction removalAction =
                                worldGateway.removeSource(operation);
                        if (removalAction == RecallWorldGateway.PhysicalAction.FAILED) {
                            return blockTransfer(
                                    operationId,
                                    "source removal could not prove original relationship restoration");
                        }
                        TransferAdvanceOutcome removal = fromPhysicalAction(
                                operation, removalAction, "source removal contradicted identity");
                        if (removal != TransferAdvanceOutcome.PENDING) {
                            MountRepository.TransferStatus rolledBack =
                                    repository.rollbackSourceRemovalIntent(operationId);
                            if (rolledBack == MountRepository.TransferStatus.SUCCESS) {
                                return removal;
                            }
                            return blockTransfer(
                                    operationId, "source removal intent rollback was not acknowledged");
                        }
                    } else if (evidence.getSource()
                            != RecallWorldGateway.TransferEvidence.Presence.MISSING) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    TransferAdvanceOutcome sourceCheckpoint = fromCheckpoint(
                            operation,
                            worldGateway.checkpointSourceAbsent(operation),
                            "source absence checkpoint contradicted identity");
                    if (sourceCheckpoint != TransferAdvanceOutcome.PENDING) {
                        return blockTransfer(
                                operationId, "source removal intent absence could not be fenced");
                    }
                    TransferAdvanceOutcome sourceRecorded = fromRepositoryProgress(
                            repository.markTransferSourceRemoved(operationId));
                    if (sourceRecorded != TransferAdvanceOutcome.PENDING) {
                        return blockTransfer(
                                operationId, "source removal acknowledgement failed after absence fence");
                    }
                    recordTransferPhase(operation, TransferPhase.SOURCE_REMOVED);
                    if (worldGateway.pauseAfterPhase(TransferPhase.SOURCE_REMOVED)) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    break;
                case SOURCE_REMOVED:
                    if (evidence.getCandidate() != RecallWorldGateway.TransferEvidence.Presence.EXACT
                                    && evidence.getCandidate()
                                            != RecallWorldGateway.TransferEvidence.Presence.FINALIZED) {
                        return TransferAdvanceOutcome.PENDING;
                    }
                    if (evidence.getCandidate() == RecallWorldGateway.TransferEvidence.Presence.EXACT) {
                        TransferAdvanceOutcome markerClear = fromPhysicalAction(
                                operation,
                                worldGateway.clearCandidateOperationMarker(operation),
                                "candidate marker cleanup contradicted identity");
                        if (markerClear != TransferAdvanceOutcome.PENDING) {
                            return markerClear;
                        }
                    }
                    TransferAdvanceOutcome finalCandidateCheckpoint = fromCheckpoint(
                            operation,
                            worldGateway.checkpointCandidate(operation, false),
                            "final candidate checkpoint contradicted identity");
                    if (finalCandidateCheckpoint != TransferAdvanceOutcome.PENDING) {
                        return finalCandidateCheckpoint;
                    }
                    TransferAdvanceOutcome finished = fromRepositoryProgress(
                            repository.finishTransfer(operationId));
                    if (finished != TransferAdvanceOutcome.PENDING) {
                        return finished;
                    }
                    recordTransferPhase(operation, null);
                    return TransferAdvanceOutcome.COMPLETE;
                case INTEGRITY_BLOCKED:
                default:
                    return TransferAdvanceOutcome.INTEGRITY_CONFLICT;
            }
        }
        return TransferAdvanceOutcome.PENDING;
    }

    private static boolean isRecoveryReady(
            TransferPhase phase, boolean sourceObserved, boolean candidateObserved) {
        switch (phase) {
            case PREPARED:
            case CANDIDATE_SPAWN_INTENT:
                return sourceObserved;
            case CANDIDATE_SPAWNED:
            case ASSOCIATED:
                return sourceObserved && candidateObserved;
            case SOURCE_REMOVAL_INTENT:
            case SOURCE_REMOVED:
                return candidateObserved;
            case INTEGRITY_BLOCKED:
            default:
                return false;
        }
    }

    private TransferAdvanceOutcome fromCheckpoint(
            TransferOperation operation,
            RecallWorldGateway.CheckpointStatus status,
            String conflictReason) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", operation.getOperationId().toString());
        fields.put("mount", operation.getMountId().toString());
        fields.put("phase", operation.getPhase().name());
        fields.put("checkpoint", conflictReason);
        fields.put("result", status.name());
        fields.put("store_revision", Long.toString(repository.getStoreRevision()));
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, "transfer_checkpoint", fields);
        switch (status) {
            case VERIFIED:
                return TransferAdvanceOutcome.PENDING;
            case UNAVAILABLE:
                diagnostics.essentialLifecycleWarning(
                        "transfer_evidence_unavailable", conflictReason);
                return TransferAdvanceOutcome.TEMPORARILY_UNAVAILABLE;
            case INTEGRITY_CONFLICT:
                return blockTransfer(operation.getOperationId(), conflictReason);
            case FAILED:
            default:
                diagnostics.essentialLifecycleWarning(
                        "transfer_physical_fence_failed", conflictReason);
                return TransferAdvanceOutcome.PERSISTENCE_FAILURE;
        }
    }

    private TransferAdvanceOutcome fromPhysicalAction(
            TransferOperation operation,
            RecallWorldGateway.PhysicalAction action,
            String conflictReason) {
        switch (action) {
            case SUCCESS:
                return TransferAdvanceOutcome.PENDING;
            case FAILED_RESTORED:
                diagnostics.essentialLifecycleWarning(
                        "transfer_physical_action_failed", conflictReason);
                return TransferAdvanceOutcome.PERSISTENCE_FAILURE;
            case UNAVAILABLE:
                return TransferAdvanceOutcome.TEMPORARILY_UNAVAILABLE;
            case CONFLICT:
                return blockTransfer(operation.getOperationId(), conflictReason);
            case FAILED:
            default:
                diagnostics.essentialLifecycleWarning(
                        "transfer_physical_action_failed", conflictReason);
                return TransferAdvanceOutcome.PERSISTENCE_FAILURE;
        }
    }

    private TransferAdvanceOutcome fromRepositoryProgress(MountRepository.TransferStatus status) {
        switch (status) {
            case SUCCESS:
                return TransferAdvanceOutcome.PENDING;
            case INTEGRITY_CONFLICT:
                return TransferAdvanceOutcome.INTEGRITY_CONFLICT;
            case PERSISTENCE_FAILURE:
                diagnostics.essentialLifecycleWarning(
                        "transfer_journal_acknowledgement_failed",
                        "authorizing transfer transition was not acknowledged");
                return TransferAdvanceOutcome.PERSISTENCE_FAILURE;
            case REJECTED:
            default:
                return TransferAdvanceOutcome.PERSISTENCE_FAILURE;
        }
    }

    private TransferAdvanceOutcome blockTransfer(UUID operationId, String reason) {
        TransferOperation unresolved = repository.findTransfer(operationId).orElse(null);
        MountRepository.TransferStatus status = repository.blockTransfer(operationId, reason);
        if (status == MountRepository.TransferStatus.PERSISTENCE_FAILURE
                && unresolved != null
                && unresolved.getPhase().isActionIntent()) {
            throw new FatalTransferSafetyException(
                    operationId, unresolved.getPhase(), reason);
        }
        return status == MountRepository.TransferStatus.PERSISTENCE_FAILURE
                ? TransferAdvanceOutcome.PERSISTENCE_FAILURE
                : TransferAdvanceOutcome.INTEGRITY_CONFLICT;
    }

    private static ContextualOutcome.Status fromTransferStatus(
            MountRepository.TransferStatus status) {
        if (status == MountRepository.TransferStatus.INTEGRITY_CONFLICT) {
            return ContextualOutcome.Status.INTEGRITY_CONFLICT;
        }
        if (status == MountRepository.TransferStatus.PERSISTENCE_FAILURE) {
            return ContextualOutcome.Status.PERSISTENCE_FAILURE;
        }
        return ContextualOutcome.Status.INTERNAL_FAILURE;
    }

    private static ContextualOutcome.Status fromTransferOutcome(
            TransferAdvanceOutcome outcome) {
        switch (outcome) {
            case INTEGRITY_CONFLICT:
                return ContextualOutcome.Status.INTEGRITY_CONFLICT;
            case TEMPORARILY_UNAVAILABLE:
                return ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE;
            case PERSISTENCE_FAILURE:
                return ContextualOutcome.Status.PERSISTENCE_FAILURE;
            default:
                return ContextualOutcome.Status.INTERNAL_FAILURE;
        }
    }

    private MountRepository.TransferStatus updateRelocatedEvidence(
            TransferOperation operation, RecallWorldGateway.TransferEvidence evidence) {
        LastKnownEvidence source = evidence.getActualSourceEvidence()
                .orElse(operation.getSourceEvidence());
        LastKnownEvidence destination = evidence.getActualCandidateEvidence()
                .orElse(operation.getDestinationEvidence());
        if (source.equals(operation.getSourceEvidence())
                && destination.equals(operation.getDestinationEvidence())) {
            return null;
        }
        return repository.updateTransferEvidence(
                operation.getOperationId(), source, destination);
    }

    private void recordTransferPhase(TransferOperation operation, TransferPhase phase) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", operation.getOperationId().toString());
        fields.put("mount", operation.getMountId().toString());
        fields.put("phase", phase == null ? "FINALIZED" : phase.name());
        fields.put("source_entity", operation.getSourceEntityId().toString());
        fields.put("candidate_entity", operation.getCandidateEntityId().toString());
        fields.put("source_dimension", Integer.toString(
                operation.getSourceEvidence().getDimensionId()));
        fields.put("destination_dimension", Integer.toString(
                operation.getDestinationEvidence().getDimensionId()));
        fields.put("store_revision", Long.toString(repository.getStoreRevision()));
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, "transfer_phase", fields);
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
