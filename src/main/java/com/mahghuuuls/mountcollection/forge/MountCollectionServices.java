package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.diagnostics.MountCollectionDiagnostics;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Optional;

/**
 * Composition root for independently owned common services.
 */
public final class MountCollectionServices {

    private final ProviderRegistry providerRegistry;
    private final ActiveServerClock activeServerClock;
    private final MountCollectionDiagnostics diagnostics;
    private final InhibitedIntegration inhibitedIntegration;
    private final LifecycleMutationExecutor lifecycleMutations;
    private final TransferDevelopmentControls developmentControls =
            new TransferDevelopmentControls();
    private ValidatedMountConfig activeConfig;
    private MountRepository activeRepository;
    private MountLifecycleService lifecycleService;
    private ForgeRecallWorldGateway worldGateway;
    private final com.mahghuuuls.mountcollection.lifecycle.ExperienceCoordinator experience;

    MountCollectionServices(
            ProviderRegistry providerRegistry,
            ActiveServerClock activeServerClock,
            MountCollectionDiagnostics diagnostics,
            InhibitedIntegration inhibitedIntegration) {
        this.providerRegistry = providerRegistry;
        this.activeServerClock = activeServerClock;
        this.diagnostics = diagnostics;
        this.inhibitedIntegration = inhibitedIntegration;
        this.lifecycleMutations = new LifecycleMutationExecutor(diagnostics);
        experience = new com.mahghuuuls.mountcollection.lifecycle.ExperienceCoordinator(
                new ForgeExperienceDelivery()::deliver,
                outcome -> diagnostics.detail(
                        com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.LIFECYCLE,
                        "experience_completion", java.util.Collections.singletonMap("outcome", outcome)));
    }

    public com.mahghuuuls.mountcollection.lifecycle.ExperienceCoordinator getExperience() { return experience; }

    public boolean boardArrived(net.minecraft.entity.player.EntityPlayerMP player,
            com.mahghuuuls.mountcollection.lifecycle.ExperienceCompletion completion) {
        boolean boarded = false;
        try {
            com.mahghuuuls.mountcollection.persistence.MountRecord record = activeRepository == null ? null
                    : activeRepository.find(completion.getMountId()).orElse(null);
            com.mahghuuuls.mountcollection.api.MountProvider provider = record == null ? null
                    : providerRegistry.find(record.getProviderId()).orElse(null);
            boarded = record != null && provider != null && worldGateway != null
                    && record.getCondition() == com.mahghuuuls.mountcollection.persistence.MountCondition.LIVING
                    && completion.getEntityId().equals(record.getPhysicalEntityId())
                    && completion.getOwnerId().equals(player.getUniqueID())
                    && worldGateway.boardArrived(player, record, provider);
        } catch (com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal) {
            throw fatal;
        } catch (RuntimeException | LinkageError rejected) { boarded = false; }
        try {
            diagnostics.detail(com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.LIFECYCLE,
                "boarding_outcome", java.util.Collections.singletonMap("outcome",
                        (boarded ? "BOARDED" : "NOT_BOARDED") + " request=" + completion.getRequestId()));
        } catch (com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal) {
            throw fatal;
        } catch (RuntimeException | LinkageError unavailable) {
            // Diagnostics must not replace the actual boarding result or suppress player feedback.
        }
        return boarded;
    }

    public ProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    public ActiveServerClock getActiveServerClock() {
        return activeServerClock;
    }

    public MountCollectionDiagnostics getDiagnostics() {
        return diagnostics;
    }

    public InhibitedIntegration getInhibitedIntegration() {
        return inhibitedIntegration;
    }

    TransferDevelopmentControls getDevelopmentControls() {
        return developmentControls;
    }

    public boolean consumeCollectionTimeout(java.util.UUID owner) {
        boolean suppressed = developmentControls.consumeCollectionTimeout(owner);
        if (suppressed) {
            diagnostics.detail(com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.COLLECTION,
                    "development_collection_snapshot_suppressed",
                    java.util.Collections.singletonMap("owner", owner.toString()));
        }
        return suppressed;
    }

    public void clearCollectionTimeout(java.util.UUID owner) {
        developmentControls.clearCollectionTimeout(owner);
    }

    public boolean submitLifecycleMutation(Runnable mutation) {
        return lifecycleMutations.enqueue(mutation);
    }

    LifecycleMutationExecutor getLifecycleMutationExecutor() {
        return lifecycleMutations;
    }

    public synchronized Optional<ValidatedMountConfig> getActiveConfig() {
        return Optional.ofNullable(activeConfig);
    }

    public synchronized Optional<MountRepository> getActiveRepository() {
        return Optional.ofNullable(activeRepository);
    }

    public synchronized Optional<MountLifecycleService> getLifecycleService() {
        return Optional.ofNullable(lifecycleService);
    }

    synchronized void activateConfig(ValidatedMountConfig config) {
        activeConfig = config;
        diagnostics.setDetailedEnabled(config.isDetailedDiagnosticsEnabled());
    }

    synchronized void activateRepository(MountRepository repository) {
        activeRepository = repository;
        worldGateway = new ForgeRecallWorldGateway(diagnostics,
                developmentControls::consumePhysicalFencePostDrainFault, developmentControls::shouldPause,
                developmentControls::phaseAcknowledged, developmentControls::shouldPauseRestoration,
                developmentControls::restorationPhaseAcknowledged);
        worldGateway.setRecoveryAdmission(experience::recoveryAdmission);
        lifecycleService = new MountLifecycleService(
                repository,
                providerRegistry,
                () -> getActiveConfig().orElseThrow(
                        () -> new IllegalStateException("server configuration is not active")),
                diagnostics,
                activeServerClock,
                inhibitedIntegration,
                worldGateway,
                developmentControls::consumeRecoveryProviderUnavailable);
        lifecycleService.setCompletionSink(experience::complete);
    }

    synchronized void clearActiveConfig() {
        activeConfig = null;
        activeRepository = null;
        lifecycleService = null;
        worldGateway = null;
        experience.clear();
        lifecycleMutations.reset();
        developmentControls.clear();
        diagnostics.setDetailedEnabled(false);
    }
}
