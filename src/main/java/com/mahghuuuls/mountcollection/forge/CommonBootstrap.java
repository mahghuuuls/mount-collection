package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.Tags;
import com.mahghuuuls.mountcollection.api.MountProviderRegistrationEvent;
import com.mahghuuuls.mountcollection.diagnostics.MountCollectionDiagnostics;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.network.MountNetwork;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.MountSavedData;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mahghuuuls.mountcollection.lifecycle.RecoveryDeathOutcome;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ActiveTimeResult;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import com.mahghuuuls.mountcollection.provider.vanilla.VanillaMountProvider;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityList;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;

public final class CommonBootstrap {

    private final Logger logger;
    private final MountNetwork network;
    private final MountCollectionServices services;
    private final PendingTransferRecoveryScheduler transferRecovery =
            new PendingTransferRecoveryScheduler();
    private ForgeMountConfiguration configuration;
    private final ForgeMountNaming naming;

    public CommonBootstrap(Logger logger, MountNetwork network) {
        this.logger = logger;
        this.network = network;
        this.services = new MountCollectionServices(
                new ProviderRegistry(),
                new ActiveServerClock(),
                new MountCollectionDiagnostics(logger),
                new InhibitedIntegration());
        naming = new ForgeMountNaming(services);
    }

    public void preInitialize(File configurationFile) {
        configuration = new ForgeMountConfiguration(configurationFile);
        configuration.ensureGenerated();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(naming);
        network.preInitialize(services);
        logger.info("Prepared {} configuration at {}", Tags.MOD_NAME, configurationFile.getName());
    }

    public void initialize() {
        ProviderRegistry providers = services.getProviderRegistry();
        providers.register(new VanillaMountProvider());
        MinecraftForge.EVENT_BUS.post(new MountProviderRegistrationEvent(providers));
        providers.freeze();
        logger.info("Initialized {} provider registry with {} provider(s)", Tags.MOD_NAME, providers.size());
    }

    public void serverAboutToStart() {
        if (configuration == null) {
            throw new IllegalStateException("pre-initialization did not prepare configuration");
        }
        ValidatedMountConfig activeConfig = configuration.loadValidated(
                new ForgeRegistryResolver(), services.getDiagnostics());
        services.activateConfig(activeConfig);
        logger.info(
                "Activated {} server configuration: cooldown={} ticks, placement={}/{}, recovery={} ({} ticks), diagnostics={}",
                Tags.MOD_NAME,
                activeConfig.getSummonCooldownTicks(),
                activeConfig.getNormalPlacementRadius(),
                activeConfig.getFallbackPlacementRadius(),
                activeConfig.isRecoveryEnabled(),
                activeConfig.getRecoveryDurationTicks(),
                activeConfig.isDetailedDiagnosticsEnabled());
    }

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new MountCollectionCommand(services));
    }

    public void serverStopped() {
        network.clearCollectionSessions();
        naming.reset();
        transferRecovery.reset();
        services.clearActiveConfig();
    }

    public MountCollectionServices getServices() {
        return services;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && services.getActiveConfig().isPresent()) {
            network.collectionTick();
            ActiveTimeResult advanced = services.getActiveServerClock().advance();
            services.getActiveRepository().ifPresent(repository -> {
                if (advanced.getStatus() == ActiveTimeResult.Status.OVERFLOW_REBASED) {
                    repository.rebaseActiveTime(advanced.getValue());
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("status", advanced.getStatus().name());
                    fields.put("active_tick", Long.toString(advanced.getValue()));
                    services.getDiagnostics().detail(
                            com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.LIFECYCLE,
                            "active_time_anomaly",
                            fields);
                } else {
                    repository.updateActiveTick(advanced.getValue());
                }
            });
            java.util.Optional<com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService>
                    lifecycle = services.getLifecycleService();
            java.util.Optional<MountRepository> activeRepository = services.getActiveRepository();
            if (lifecycle.isPresent()) {
                if (advanced.getStatus() == ActiveTimeResult.Status.OVERFLOW_REBASED) {
                    lifecycle.get().rebuildRecoveryDeadlines();
                    activeRepository.ifPresent(repository ->
                            repository.getPendingNotificationOwners()
                                    .forEach(this::deliverPendingNotification));
                } else {
                    lifecycle.get().advanceRecoveryDeadlines()
                            .forEach(this::deliverPendingNotification);
                }
            }
            PendingTransferRecoveryScheduler.Request request;
            while (lifecycle.isPresent()
                    && activeRepository.isPresent()
                    && (request = transferRecovery.poll()) != null) {
                PendingTransferRecoveryScheduler.Request queuedRequest = request;
                UUID operationId = request.getOperationId();
                boolean accepted = services.submitLifecycleMutation(() -> {
                    lifecycle.get().reconcilePendingTransfer(
                            operationId,
                            queuedRequest.isSourceObserved(),
                            queuedRequest.isCandidateObserved());
                    java.util.Optional<TransferOperation> remaining =
                            activeRepository.get().findTransfer(operationId);
                    if (!remaining.isPresent()
                            || remaining.get().getPhase() == TransferPhase.INTEGRITY_BLOCKED) {
                        transferRecovery.operationFinished(operationId);
                    }
                });
                if (!accepted) {
                    transferRecovery.defer(request);
                    break;
                }
            }
            services.getLifecycleMutationExecutor().drainAtServerTickEnd();
            naming.tick();
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof WorldServer) || event.getWorld().isRemote) {
            return;
        }
        if (event.getWorld().provider.getDimension() == 0) {
            MountRepository repository = MountSavedData.get(
                    (WorldServer) event.getWorld(),
                    services.getDevelopmentControls()::consumeJournalAcknowledgementFault,
                    (stage, cause) -> services.getDiagnostics().essentialLifecycleWarning(
                            "acknowledged_store_failure",
                            "stage=" + stage + " cause=" + cause))
                    .getRepository();
            repository.reconcileProviderPayloads(
                    services.getProviderRegistry()::validatePersistedPayload);
            services.activateRepository(repository);
            transferRecovery.repositoryActivated();
            ActiveTimeResult restored = services.getActiveServerClock().restore(repository.getActiveTick(), 0L);
            logger.info(
                    "Activated {} repository: records={}, readOnly={}, clock={}",
                    Tags.MOD_NAME,
                    repository.getTotalRecordCount(),
                    repository.isReadOnly(),
                    restored.getStatus());
        }
        transferRecovery.worldLoaded();
        services.getLifecycleService().ifPresent(lifecycle ->
                services.submitLifecycleMutation(lifecycle::reconcilePendingRestorations));
        services.getLifecycleService().ifPresent(lifecycle ->
                services.submitLifecycleMutation(lifecycle::reconcilePendingAbandonments));
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote) {
            if (discardControlledRecoverySource(event.getEntity())) {
                event.setCanceled(true);
                event.getEntity().setDead();
                return;
            }
            if (routeRestorationCandidate(event.getEntity(), true)) {
                return;
            }
            TransferOperation controlled = reconcileEntity(event.getEntity());
            if (controlled != null) {
                transferRecovery.controlledEntityJoined(
                        controlled.getOperationId(),
                        controlled.getCandidateEntityId().equals(event.getEntity().getUniqueID()));
            }
        }
    }

    private boolean discardControlledRecoverySource(Entity entity) {
        java.util.Optional<MountRepository> activeRepository = services.getActiveRepository();
        if (!activeRepository.isPresent()) {
            return false;
        }
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
        if (!evidence.getMountId().isPresent()
                || !activeRepository.get().isControlledRecoverySource(
                        evidence.getMountId().get(), entity.getUniqueID())) {
            return false;
        }
        com.mahghuuuls.mountcollection.persistence.MountRecord record =
                activeRepository.get().find(evidence.getMountId().get()).orElse(null);
        if (record == null || !record.getEntityTypeId().equals(EntityList.getKey(entity))
                || EntityMountEvidence.readTransfer(entity).getStatus() != EntityMountEvidence.Status.NONE) {
            return false;
        }
        try {
            services.getLifecycleService().get().acknowledgeCapturedSourceLocation(record, entity);
            completeProtectedDeath(entity);
        } catch (com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal) {
            services.getLifecycleMutationExecutor().latchCallbackFailure(fatal);
            throw fatal;
        }
        return true;
    }

    private boolean routeRestorationCandidate(Entity entity, boolean joined) {
        java.util.Optional<com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService> lifecycle =
                services.getLifecycleService();
        if (!lifecycle.isPresent()) { return false; }
        java.util.Optional<UUID> operationId = lifecycle.get().observeRestorationCandidate(entity);
        if (!operationId.isPresent()) { return false; }
        if (joined) {
            // Retain only operation identity, never an entity/world reference across ticks.
            services.submitLifecycleMutation(() -> lifecycle.get().reconcilePendingRestoration(operationId.get()));
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntityLiving().world.isRemote || event.isCanceled()) {
            return;
        }
        java.util.Optional<com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService>
                lifecycle = services.getLifecycleService();
        if (!lifecycle.isPresent()) {
            return;
        }
        Entity mount = event.getEntityLiving();
        RecoveryDeathOutcome outcome;
        try {
            outcome = lifecycle.get().handleLethalDamage(mount);
        } catch (RuntimeException failure) {
            EntityMountEvidence.ReadResult identity = EntityMountEvidence.read(mount);
            MountRepository repository = services.getActiveRepository().orElse(null);
            if (repository != null && identity.getMountId().isPresent()
                    && repository.isControlledRecoverySource(identity.getMountId().get(), mount.getUniqueID())) {
                event.setCanceled(true);
                mount.captureDrops = true;
                mount.isDead = true;
                com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal =
                        com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException
                                .capturedSourceFailure(mount.getUniqueID(), failure);
                services.getLifecycleMutationExecutor().latchCallbackFailure(fatal);
                throw fatal;
            }
            throw failure;
        }
        if (outcome.shouldSuppressDeath()) {
            event.setCanceled(true);
            try {
                completeProtectedDeath(mount);
            } catch (com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal) {
                services.getLifecycleMutationExecutor().latchCallbackFailure(fatal);
                throw fatal;
            }
        }
        if (outcome.getOwnerId().isPresent()) {
            deliverPendingNotification(outcome.getOwnerId().get());
        }
    }

    static void completeProtectedDeath(Entity mount) {
        try {
            containProtectedSource(mount);
            if (!mount.isDead) {
                throw new IllegalStateException("captured source remains alive");
            }
        } catch (RuntimeException failure) {
            throw com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException
                    .capturedSourceFailure(mount.getUniqueID(), failure);
        }
    }

    private static void containProtectedSource(Entity mount) {
        // LivingDeathEvent is fired from EntityLivingBase.onDeath. Some 1.12.2
        // subclasses, notably AbstractHorse, keep executing after super.onDeath
        // returns and emit inventory through Entity.entityDropItem. Keep Forge's
        // per-entity capture active while that subclass frame unwinds so the
        // captured Recovery state cannot also escape as physical drops.
        mount.captureDrops = true;
        // Contain the physical source even if a subclass relationship callback throws.
        mount.isDead = true;
        mount.capturedDrops.clear();
        mount.removePassengers();
        if (mount.isRiding()) {
            mount.dismountRidingEntity();
        }
        if (mount instanceof EntityLiving && ((EntityLiving) mount).getLeashed()) {
            ((EntityLiving) mount).clearLeashed(false, false);
        }
        mount.setDead();
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getWorld().isRemote) {
            return;
        }
        for (ClassInheritanceMultiMap<Entity> entities : event.getChunk().getEntityLists()) {
            for (Entity entity : entities) {
                if (!entity.isDead && !routeRestorationCandidate(entity, false)) {
                    reconcileEntity(entity);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            transferRecovery.playerJoined();
            deliverPendingNotification(event.player.getUniqueID());
        }
    }

    private void deliverPendingNotification(UUID ownerId) {
        java.util.Optional<MountRepository> activeRepository = services.getActiveRepository();
        if (!activeRepository.isPresent()) {
            return;
        }
        EntityPlayerMP owner = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance().getPlayerList().getPlayerByUUID(ownerId);
        if (owner == null) {
            return;
        }
        activeRepository.get().consumePendingNotification(ownerId)
                .ifPresent(key -> owner.sendMessage(new TextComponentTranslation(key)));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            network.playerLoggedOut((EntityPlayerMP) event.player);
        }
    }

    private TransferOperation reconcileEntity(Entity entity) {
        java.util.Optional<MountRepository> activeRepository = services.getActiveRepository();
        if (!activeRepository.isPresent()) {
            return null;
        }
        MountRepository repository = activeRepository.get();
        com.mahghuuuls.mountcollection.persistence.MountRecord abandoning =
                repository.findByPhysicalEntity(entity.getUniqueID()).orElse(null);
        if (abandoning != null && repository.findAbandonment(abandoning.getMountId()).isPresent()) {
            com.mahghuuuls.mountcollection.persistence.MountId id = abandoning.getMountId();
            LastKnownEvidence location = new LastKnownEvidence(entity.dimension, entity.posX, entity.posY, entity.posZ);
            services.getLifecycleService().ifPresent(lifecycle ->
                    services.submitLifecycleMutation(() -> lifecycle.reconcileAbandonment(id, location)));
            return null;
        }
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
        if (evidence.getMountId().isPresent()) {
            java.util.Optional<TransferOperation> pending =
                    repository.findTransferByMount(evidence.getMountId().get());
            if (pending.isPresent()
                    && pending.get().getPhase() != TransferPhase.INTEGRITY_BLOCKED
                    && (pending.get().getCandidateEntityId().equals(entity.getUniqueID())
                            || pending.get().getSourceEntityId().equals(entity.getUniqueID()))) {
                return pending.get();
            }
        }
        java.util.Optional<java.util.UUID> transfer =
                EntityMountEvidence.readTransferOperation(entity);
        if (transfer.isPresent() && evidence.getMountId().isPresent()) {
            if (repository.isControlledTransferCandidate(
                    transfer.get(), evidence.getMountId().get(), entity.getUniqueID())) {
                return repository.findTransfer(transfer.get()).orElse(null);
            }
            java.util.Optional<com.mahghuuuls.mountcollection.persistence.MountRecord> authoritative =
                    repository.findByPhysicalEntity(entity.getUniqueID());
            if (authoritative.isPresent()
                    && authoritative.get().getMountId().equals(evidence.getMountId().get())) {
                EntityMountEvidence.clearTransferOperation(entity);
            }
        }
        if (evidence.getMountId().isPresent()
                && repository.isControlledTransferSource(
                        evidence.getMountId().get(), entity.getUniqueID())) {
            return repository.findTransferByMount(evidence.getMountId().get()).orElse(null);
        }
        MountRepository.ReconciliationStatus status;
        if (evidence.getStatus() == EntityMountEvidence.Status.MALFORMED) {
            status = repository.reportMalformedEvidence(entity.getUniqueID());
        } else {
            status = repository.reconcile(
                    entity.getUniqueID(),
                    evidence.getMountId().orElse(null),
                    new LastKnownEvidence(entity.dimension, entity.posX, entity.posY, entity.posZ));
        }
        if (status == MountRepository.ReconciliationStatus.REATTACH_REQUIRED) {
            repository.findByPhysicalEntity(entity.getUniqueID())
                    .ifPresent(record -> EntityMountEvidence.attach(entity, record.getMountId()));
        }
        naming.reconcile(entity);
        if (status == MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("outcome", status.name());
            services.getDiagnostics().detail(
                    com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.PROVIDER,
                    "entity_reconciliation",
                    fields);
        }
        return null;
    }
}
