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
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ActiveTimeResult;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import com.mahghuuuls.mountcollection.provider.vanilla.VanillaMountProvider;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Logger;

public final class CommonBootstrap {

    private final Logger logger;
    private final MountNetwork network;
    private final MountCollectionServices services;
    private ForgeMountConfiguration configuration;

    public CommonBootstrap(Logger logger, MountNetwork network) {
        this.logger = logger;
        this.network = network;
        this.services = new MountCollectionServices(
                new ProviderRegistry(),
                new ActiveServerClock(),
                new MountCollectionDiagnostics(logger),
                new InhibitedIntegration());
    }

    public void preInitialize(File configurationFile) {
        configuration = new ForgeMountConfiguration(configurationFile);
        configuration.ensureGenerated();
        MinecraftForge.EVENT_BUS.register(this);
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
        services.clearActiveConfig();
    }

    public MountCollectionServices getServices() {
        return services;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && services.getActiveConfig().isPresent()) {
            ActiveTimeResult advanced = services.getActiveServerClock().advance();
            services.getActiveRepository().ifPresent(
                    repository -> repository.updateActiveTick(advanced.getValue()));
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof WorldServer)
                || event.getWorld().isRemote
                || event.getWorld().provider.getDimension() != 0) {
            return;
        }
        MountRepository repository = MountSavedData.get((WorldServer) event.getWorld()).getRepository();
        repository.reconcileProviderPayloads(
                services.getProviderRegistry()::validatePersistedPayload);
        services.activateRepository(repository);
        ActiveTimeResult restored = services.getActiveServerClock().restore(repository.getActiveTick(), 0L);
        logger.info(
                "Activated {} repository: records={}, readOnly={}, clock={}",
                Tags.MOD_NAME,
                repository.getTotalRecordCount(),
                repository.isReadOnly(),
                restored.getStatus());
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote) {
            reconcileEntity(event.getEntity());
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getWorld().isRemote) {
            return;
        }
        for (ClassInheritanceMultiMap<Entity> entities : event.getChunk().getEntityLists()) {
            for (Entity entity : entities) {
                reconcileEntity(entity);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            network.playerLoggedOut((EntityPlayerMP) event.player);
        }
    }

    private void reconcileEntity(Entity entity) {
        services.getActiveRepository().ifPresent(repository -> {
            EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
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
            if (status == MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("outcome", status.name());
                services.getDiagnostics().detail(
                        com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.PROVIDER,
                        "entity_reconciliation",
                        fields);
            }
        });
    }
}
