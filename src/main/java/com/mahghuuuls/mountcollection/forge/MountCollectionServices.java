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
        lifecycleService = new MountLifecycleService(
                repository,
                providerRegistry,
                () -> getActiveConfig().orElseThrow(
                        () -> new IllegalStateException("server configuration is not active")),
                diagnostics,
                activeServerClock,
                inhibitedIntegration,
                new ForgeRecallWorldGateway(
                        diagnostics,
                        developmentControls::consumePhysicalFencePostDrainFault,
                        developmentControls::shouldPause,
                        developmentControls::phaseAcknowledged));
    }

    synchronized void clearActiveConfig() {
        activeConfig = null;
        activeRepository = null;
        lifecycleService = null;
        lifecycleMutations.reset();
        developmentControls.clear();
        diagnostics.setDetailedEnabled(false);
    }
}
