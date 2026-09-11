package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.MountSavedData;
import com.mahghuuuls.mountcollection.persistence.RecoveryState;
import com.mahghuuuls.mountcollection.persistence.RepositoryTestAccess;
import com.mahghuuuls.mountcollection.persistence.RestorationOperation;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import com.mahghuuuls.mountcollection.provider.vanilla.VanillaMountProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.ChunkEvent;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class RestorationEventRoutingTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @EnumSource(value = RestorationPhase.class, names = {"CANDIDATE_SPAWNED", "ASSOCIATED"})
    void unloadingMovedCandidatePreservesJournalAuthorityThroughCodec(RestorationPhase phase) throws Exception {
        Fixture f = new Fixture(phase);
        f.candidate.setPosition(40, 64, 40);
        Chunk chunk = new Chunk(f.world, 2, 2);
        chunk.addEntity(f.candidate);
        f.bootstrap.onChunkUnload(new ChunkEvent.Unload(chunk));
        assertEquals(f.operation.getOperationId(), EntityMountEvidence.readTransferOperation(f.candidate).get());
        MountRepository loaded = f.reload();
        assertEquals(phase, loaded.findRestoration(f.operation.getOperationId()).get().getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS, loaded.find(f.record.getMountId()).get().getCondition());
        assertEquals(f.location(), loaded.findRestoration(f.operation.getOperationId()).get().getDestinationEvidence());
    }

    @Test
    void clearedMarkerJoinQueuesCompletionAndFenceFailureCanRetry() throws Exception {
        Fixture f = new Fixture(RestorationPhase.ASSOCIATED);
        EntityMountEvidence.clearTransferOperation(f.candidate);
        f.candidate.setPosition(40, 64, 40);
        f.restart();
        f.gateway.available = false;
        f.bootstrap.getServices().getLifecycleService().get().reconcilePendingRestorations();
        assertEquals(RestorationPhase.ASSOCIATED, f.repository.findRestoration(f.operation.getOperationId()).get().getPhase());
        f.gateway.available = true;
        f.gateway.fence = RecallWorldGateway.CheckpointStatus.FAILED;
        f.bootstrap.onEntityJoin(new EntityJoinWorldEvent(f.candidate, f.world));
        assertEquals(0, f.gateway.fences);
        f.bootstrap.getServices().getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals(1, f.gateway.fences);
        assertEquals(RestorationPhase.ASSOCIATED, f.reload().findRestoration(f.operation.getOperationId()).get().getPhase());
        assertEquals(0, f.repository.getRecallCooldown(f.record.getOwnerId()).getDeadline());
        f.gateway.fence = RecallWorldGateway.CheckpointStatus.VERIFIED;
        f.bootstrap.onEntityJoin(new EntityJoinWorldEvent(f.candidate, f.world));
        f.bootstrap.getServices().getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals(MountCondition.LIVING, f.repository.find(f.record.getMountId()).get().getCondition());
        assertTrue(f.repository.getPendingRestorations().isEmpty());
        assertEquals(f.location(), f.repository.find(f.record.getMountId()).get().getLastKnown());
    }

    @ParameterizedTest
    @EnumSource(value = RestorationPhase.class, names = {"CANDIDATE_SPAWNED", "ASSOCIATED"})
    void rejectedUnloadRelocationDoesNotFallThroughToOrdinaryReconciliation(RestorationPhase phase) throws Exception {
        Fixture f = new Fixture(phase);
        RepositoryTestAccess.setAcknowledgement(f.repository, () -> false);
        f.candidate.setPosition(40, 64, 40);
        Chunk chunk = new Chunk(f.world, 2, 2);
        chunk.addEntity(f.candidate);
        f.bootstrap.onChunkUnload(new ChunkEvent.Unload(chunk));
        assertEquals(phase, f.reload().findRestoration(f.operation.getOperationId()).get().getPhase());
        assertEquals(f.operation.getDestinationEvidence(), f.repository.findRestoration(f.operation.getOperationId()).get().getDestinationEvidence());
        assertEquals(f.operation.getOperationId(), EntityMountEvidence.readTransferOperation(f.candidate).get());
        assertFalse(f.candidate.isDead);
    }

    @Test
    void wrongMarkerIsNotErasedOrAcceptedOnJoin() throws Exception {
        Fixture f = new Fixture(RestorationPhase.ASSOCIATED);
        UUID wrong = UUID.randomUUID();
        EntityMountEvidence.attachTransferCandidate(f.candidate, f.record.getMountId(), wrong);
        f.bootstrap.onEntityJoin(new EntityJoinWorldEvent(f.candidate, f.world));
        f.bootstrap.getServices().getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals(wrong, EntityMountEvidence.readTransferOperation(f.candidate).get());
        assertEquals(MountCondition.INTEGRITY_BLOCKED, f.repository.find(f.record.getMountId()).get().getCondition());
        assertFalse(f.candidate.isDead);
        assertEquals(0, f.gateway.fences);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedSourceJoinAcknowledgesMovedEvidenceBeforeSuppression(boolean prepared) throws Exception {
        Fixture f = new Fixture(RestorationPhase.PREPARED, prepared);
        EntityBoat source = f.source();
        // Forge normally generates this override through its event transformer, absent in plain JUnit.
        EntityJoinWorldEvent event = new EntityJoinWorldEvent(source, f.world) {
            @Override public boolean isCancelable() { return true; }
        };
        f.bootstrap.onEntityJoin(event);
        assertTrue(event.isCanceled());
        assertTrue(source.isDead);
        assertTrue(source.captureDrops);
        assertEquals(new LastKnownEvidence(0, 40, 64, 40),
                f.reload().find(f.record.getMountId()).get().getRecoveryState().getSourceEvidence());
        assertTrue(f.repository.isControlledRecoverySource(f.record.getMountId(), source.getUniqueID()));
    }

    @Test
    void failedSourceRelocationAcknowledgementLatchesFatalWithoutLosingSnapshot() throws Exception {
        Fixture f = new Fixture(RestorationPhase.PREPARED);
        EntityBoat source = f.source();
        RepositoryTestAccess.setAcknowledgement(f.repository, () -> false);
        assertThrows(FatalTransferSafetyException.class,
                () -> f.bootstrap.onEntityJoin(new EntityJoinWorldEvent(source, f.world)));
        assertTrue(source.isDead);
        assertTrue(source.captureDrops);
        assertEquals(f.record.getLastKnown(), f.reload().find(f.record.getMountId()).get().getRecoveryState().getSourceEvidence());
        assertThrows(FatalTransferSafetyException.class,
                () -> f.bootstrap.getServices().getLifecycleMutationExecutor().drainAtServerTickEnd());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void contradictorySourceJoinIsNeverDeleted(boolean wrongType) throws Exception {
        Fixture f = new Fixture(RestorationPhase.PREPARED);
        Entity source = wrongType ? new net.minecraft.entity.passive.EntityPig(f.world) : f.source();
        source.setUniqueId(f.record.getPhysicalEntityId());
        EntityMountEvidence.attach(source, f.record.getMountId());
        if (!wrongType) EntityMountEvidence.attachTransferCandidate(source, f.record.getMountId(), UUID.randomUUID());
        EntityJoinWorldEvent event = new EntityJoinWorldEvent(source, f.world);
        f.bootstrap.onEntityJoin(event);
        assertFalse(event.isCanceled());
        assertFalse(source.isDead);
        assertFalse(source.captureDrops);
        assertEquals(MountCondition.INTEGRITY_BLOCKED, f.repository.find(f.record.getMountId()).get().getCondition());
        assertNotNull(f.repository.find(f.record.getMountId()).get().getRecoveryState());
    }

    private enum SourceFault { NONE, UUID, TYPE, MARKER, LOCATION, STALE_DISK, MISSING_DISK, REMOVE_EXCEPTION, SAVE_EXCEPTION }

    @ParameterizedTest
    @EnumSource(SourceFault.class)
    void loadedSourceRetirementChecksIdentityAndSavedAbsenceAndAlwaysReleases(SourceFault fault) throws Exception {
        Fixture f = new Fixture(RestorationPhase.PREPARED);
        Entity source = fault == SourceFault.TYPE ? new net.minecraft.entity.passive.EntityPig(f.world) : f.source();
        source.setUniqueId(f.record.getPhysicalEntityId());
        source.setPosition(1, 64, 1);
        EntityMountEvidence.attach(source, f.record.getMountId());
        if (fault == SourceFault.UUID) source.setUniqueId(UUID.randomUUID());
        if (fault == SourceFault.MARKER) EntityMountEvidence.attachTransferCandidate(source, f.record.getMountId(), UUID.randomUUID());
        if (fault == SourceFault.LOCATION) source.setPosition(40, 64, 40);
        List<String> calls = new ArrayList<>();
        ForgeRecallWorldGateway.RecoverySourceAccess access = new ForgeRecallWorldGateway.RecoverySourceAccess() {
            public Entity source() { calls.add("lookup"); return source; }
            public void remove(Entity entity) {
                calls.add("remove");
                assertSame(source, entity);
                assertTrue(entity.isDead);
                assertTrue(entity.captureDrops);
                if (fault == SourceFault.REMOVE_EXCEPTION) throw new IllegalStateException("remove");
            }
            public NBTTagCompound saveAndRead() {
                calls.add("save/read");
                if (fault == SourceFault.SAVE_EXCEPTION) throw new IllegalStateException("save");
                if (fault == SourceFault.MISSING_DISK) return null;
                NBTTagCompound disk = new NBTTagCompound();
                net.minecraft.nbt.NBTTagList entities = new net.minecraft.nbt.NBTTagList();
                if (fault == SourceFault.STALE_DISK) {
                    NBTTagCompound stale = new NBTTagCompound();
                    stale.setUniqueId("UUID", f.record.getPhysicalEntityId());
                    entities.appendTag(stale);
                }
                disk.setTag("Entities", entities);
                return disk;
            }
            public void release() { calls.add("release"); }
        };
        MountRecord captured = f.repository.find(f.record.getMountId()).get();
        if (fault == SourceFault.REMOVE_EXCEPTION || fault == SourceFault.SAVE_EXCEPTION) {
            assertThrows(IllegalStateException.class, () -> ForgeRecallWorldGateway.retireLoadedRecoverySource(captured, access));
            assertEquals("release", calls.get(calls.size() - 1));
            assertEquals(fault == SourceFault.REMOVE_EXCEPTION
                    ? Arrays.asList("lookup", "remove", "release")
                    : Arrays.asList("lookup", "remove", "save/read", "release"), calls);
        } else if (fault == SourceFault.UUID || fault == SourceFault.TYPE || fault == SourceFault.MARKER || fault == SourceFault.LOCATION) {
            assertEquals(RecallWorldGateway.CheckpointStatus.INTEGRITY_CONFLICT,
                    ForgeRecallWorldGateway.retireLoadedRecoverySource(captured, access));
            assertEquals(Arrays.asList("lookup", "release"), calls);
            assertFalse(source.isDead);
            assertFalse(source.captureDrops);
        } else {
            assertEquals(fault == SourceFault.NONE ? RecallWorldGateway.CheckpointStatus.VERIFIED
                    : RecallWorldGateway.CheckpointStatus.FAILED, ForgeRecallWorldGateway.retireLoadedRecoverySource(captured, access));
            assertEquals(Arrays.asList("lookup", "remove", "save/read", "release"), calls);
        }
        assertNotNull(f.repository.find(f.record.getMountId()).get().getRecoveryState());
    }

    private static final class Fixture {
        final TestWorld world = new TestWorld();
        final CommonBootstrap bootstrap = new CommonBootstrap(LogManager.getLogger("routing-test"), null);
        MountSavedData saved = new MountSavedData("test");
        MountRepository repository = saved.getRepository();
        final EntityBoat candidate = new EntityBoat(world);
        final MountRecord record;
        final RestorationOperation operation;
        final CandidateGateway gateway = new CandidateGateway(candidate);

        Fixture(RestorationPhase phase) throws Exception { this(phase, true); }
        Fixture(RestorationPhase phase, boolean prepared) throws Exception {
            VanillaMountProvider provider = new VanillaMountProvider();
            record = repository.register(new MountRepository.RegistrationCandidate(UUID.randomUUID(),
                    provider.getProviderId(), new ResourceLocation("minecraft:boat"), "boat", UUID.randomUUID(),
                    new LastKnownEvidence(0, 1, 64, 1), null)).getRecord().get();
            repository.enterRecovery(record.getOwnerId(), record.getMountId(), record.getPhysicalEntityId(),
                    new RecoveryState(record.getPhysicalEntityId(), record.getLastKnown(), new ProviderPayload(1, new NBTTagCompound()), 0, 0));
            candidate.setPosition(4, 64, 4);
            operation = new RestorationOperation(UUID.randomUUID(), record.getMountId(), record.getOwnerId(),
                    candidate.getUniqueID(), location(), 0, 0, RestorationPhase.PREPARED, null);
            if (prepared) repository.beginRestoration(operation);
            if (phase != RestorationPhase.PREPARED) {
                repository.markRestorationSpawnIntent(operation.getOperationId());
                repository.markRestorationCandidateSpawned(operation.getOperationId());
            }
            if (phase == RestorationPhase.ASSOCIATED) repository.associateRestorationCandidate(operation.getOperationId());
            EntityMountEvidence.attachTransferCandidate(candidate, record.getMountId(), operation.getOperationId());
            MountCollectionServices services = bootstrap.getServices();
            services.getProviderRegistry().register(provider);
            services.getProviderRegistry().freeze();
            installLifecycle();
        }
        void installLifecycle() throws Exception {
            MountCollectionServices services = bootstrap.getServices();
            services.activateRepository(repository);
            MountLifecycleService lifecycle = new MountLifecycleService(repository, services.getProviderRegistry(),
                    () -> null, services.getDiagnostics(), services.getActiveServerClock(), services.getInhibitedIntegration(), gateway);
            java.lang.reflect.Field field = MountCollectionServices.class.getDeclaredField("lifecycleService");
            field.setAccessible(true);
            field.set(services, lifecycle);
        }
        void restart() throws Exception {
            MountSavedData loaded = new MountSavedData("test");
            loaded.readFromNBT(saved.writeToNBT(new NBTTagCompound()));
            saved = loaded;
            repository = loaded.getRepository();
            installLifecycle();
        }
        LastKnownEvidence location() { return new LastKnownEvidence(0, candidate.posX, candidate.posY, candidate.posZ); }
        EntityBoat source() {
            EntityBoat source = new EntityBoat(world);
            source.setUniqueId(record.getPhysicalEntityId());
            source.setPosition(40, 64, 40);
            EntityMountEvidence.attach(source, record.getMountId());
            return source;
        }
        MountRepository reload() {
            MountSavedData loaded = new MountSavedData("test");
            loaded.readFromNBT(saved.writeToNBT(new NBTTagCompound()));
            return loaded.getRepository();
        }
    }

    private static final class CandidateGateway implements RecallWorldGateway {
        final Entity candidate;
        int fences;
        boolean available = true;
        CheckpointStatus fence = CheckpointStatus.VERIFIED;
        CandidateGateway(Entity candidate) { this.candidate = candidate; }
        public LocateResult locate(EntityPlayerMP player, MountRecord record) { return LocateResult.missing(); }
        public boolean providerSupports(Source source, MountProvider provider) { return true; }
        public Optional<Destination> plan(EntityPlayerMP p, Source s, MountCharacteristics c, int n, int f) { return Optional.empty(); }
        public boolean commit(EntityPlayerMP p, Source s, Destination d, MountProvider provider) { return false; }
        public RecoveryEvidence recoveryCandidateEvidence(RestorationOperation op, MountRecord record) {
            return new RecoveryEvidence(inspectRecoveryCandidate(op, record), new LastKnownEvidence(0, candidate.posX, candidate.posY, candidate.posZ));
        }
        public TransferEvidence.Presence inspectRecoveryCandidate(RestorationOperation op, MountRecord record) {
            if (!available) return TransferEvidence.Presence.MISSING;
            return EntityMountEvidence.readTransfer(candidate).getStatus() == EntityMountEvidence.Status.NONE
                    ? TransferEvidence.Presence.FINALIZED : TransferEvidence.Presence.EXACT;
        }
        public CheckpointStatus checkpointRecoveryCandidate(RestorationOperation op, MountRecord record, boolean marker) {
            fences++;
            assertEquals(new LastKnownEvidence(0, candidate.posX, candidate.posY, candidate.posZ), op.getDestinationEvidence());
            return fence;
        }
    }

    private static final class TestWorld extends World {
        TestWorld() { super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), "test"),
                new WorldProviderSurface(), new Profiler(), false); }
        protected IChunkProvider createChunkProvider() { return null; }
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return true; }
    }
}
