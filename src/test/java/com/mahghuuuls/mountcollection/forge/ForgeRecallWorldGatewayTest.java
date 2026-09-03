package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.PreparationSupport;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway.CheckpointStatus;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ForgeRecallWorldGatewayTest {

    @BeforeAll
    static void initializeMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void capturedRidingEntitySnapshotIncludesItsRegisteredTypeId() throws Exception {
        EntityBoat vehicle = new EntityBoat(null);
        EntityBoat passenger = new EntityBoat(null);
        passenger.setUniqueId(UUID.randomUUID());
        passenger.dimension = 0;
        passenger.setPosition(1.0D, 64.0D, 1.0D);
        java.lang.reflect.Field ridingEntity = Entity.class.getDeclaredField("ridingEntity");
        ridingEntity.setAccessible(true);
        ridingEntity.set(passenger, vehicle);
        assertTrue(passenger.isRiding());
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                new FaultPersistence(Failure.NONE));

        Optional<NBTTagCompound> snapshot = gateway.captureSourceSnapshot(
                new RecallWorldGateway.Source(
                        passenger.getUniqueID(), passenger.dimension,
                        passenger.isBeingRidden(), passenger));

        assertTrue(snapshot.isPresent());
        assertEquals("minecraft:boat", snapshot.get().getString("id"));
        assertEquals(passenger.getUniqueID(),
                snapshot.get().getUniqueId("UUID"));
    }

    @Test
    void providerPreparationMutatesOnlyTheTransientCandidateBeforeSpawning() {
        EntityBoat candidate = new EntityBoat(null);
        PreparingProvider provider = new PreparingProvider();

        assertTrue(ForgeRecallWorldGateway.prepareCandidate(candidate, provider));

        assertTrue(provider.called);
        assertEquals("prepared-candidate", candidate.getCustomNameTag());
    }

    @Test
    void failedSourceRemovalRestoresExactRelationshipsWithoutDroppingLead() {
        FaultSourceRemoval source = new FaultSourceRemoval();

        assertEquals(
                RecallWorldGateway.PhysicalAction.FAILED_RESTORED,
                ForgeRecallWorldGateway.removeSourceWithCompensation(source));

        assertSame(source.originalVehicle, source.currentVehicle);
        assertSame(source.originalLeashHolder, source.currentLeashHolder);
        assertEquals(0, source.leadDrops);
    }

    @Test
    void sourceRemovalExceptionStillRestoresExactRelationships() {
        FaultSourceRemoval source = new FaultSourceRemoval();
        source.removalThrows = true;

        assertEquals(
                RecallWorldGateway.PhysicalAction.FAILED_RESTORED,
                ForgeRecallWorldGateway.removeSourceWithCompensation(source));

        assertSame(source.originalVehicle, source.currentVehicle);
        assertSame(source.originalLeashHolder, source.currentLeashHolder);
        assertEquals(0, source.leadDrops);
    }

    @Test
    void failedRelationshipRestorationReportsUncertainState() {
        FaultSourceRemoval source = new FaultSourceRemoval();
        source.ridingRestoreSucceeds = false;

        assertEquals(
                RecallWorldGateway.PhysicalAction.FAILED,
                ForgeRecallWorldGateway.removeSourceWithCompensation(source));

        assertFalse(source.currentVehicle == source.originalVehicle);
        assertEquals(0, source.leadDrops);
    }

    @Test
    void failedLeashRestorationAlsoReportsUncertainState() {
        FaultSourceRemoval source = new FaultSourceRemoval();
        source.leashRestoreSucceeds = false;

        assertEquals(
                RecallWorldGateway.PhysicalAction.FAILED,
                ForgeRecallWorldGateway.removeSourceWithCompensation(source));

        assertSame(source.originalVehicle, source.currentVehicle);
        assertNull(source.currentLeashHolder);
        assertEquals(0, source.leadDrops);
    }

    @Test
    void successfulSourceRemovalDropsLeadOnlyAfterConfirmedRemoval() {
        FaultSourceRemoval source = new FaultSourceRemoval();
        source.removalSucceeds = true;

        assertEquals(
                RecallWorldGateway.PhysicalAction.SUCCESS,
                ForgeRecallWorldGateway.removeSourceWithCompensation(source));

        assertTrue(source.removed);
        assertEquals(1, source.leadDrops);
        assertNull(source.currentVehicle);
        assertNull(source.currentLeashHolder);
    }

    @Test
    void candidateRemovalExceptionStillReleasesTemporaryChunkAccess() {
        AtomicBoolean released = new AtomicBoolean();
        FaultCandidateRemoval candidate = new FaultCandidateRemoval();

        assertThrows(
                IllegalStateException.class,
                () -> ForgeRecallWorldGateway.removeCandidateWithRelease(
                        candidate, () -> released.set(true)));

        assertTrue(candidate.removeCalled);
        assertTrue(released.get());
    }

    @Test
    void physicalFenceDrainsSavesDrainsAndThenReadsBack() {
        FaultPersistence persistence = new FaultPersistence(Failure.NONE);
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(persistence);

        NBTTagCompound result = gateway.saveDrainAndRead(null, null);

        assertSame(persistence.readback, result);
        assertEquals(Arrays.asList("drain-1", "save", "drain-2", "read"), persistence.calls);
    }

    @Test
    void everyTargetedPersistenceStageFailsClosedWithoutRunningLaterStages() {
        assertFailure(Failure.PRE_DRAIN, "drain-1");
        assertFailure(Failure.SAVE, "drain-1", "save");
        assertFailure(Failure.POST_DRAIN, "drain-1", "save", "drain-2");
        assertFailure(Failure.READ, "drain-1", "save", "drain-2", "read");
    }

    @Test
    void preDrainPreventsAnInFlightSnapshotFromDiscardingTheRequestedFact() throws Exception {
        InFlightPersistence legacySequence = new InFlightPersistence();
        legacySequence.save(null, null);
        legacySequence.drain();
        assertSame(legacySequence.stale, legacySequence.read(null, null));

        InFlightPersistence correctedSequence = new InFlightPersistence();
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(correctedSequence);

        assertSame(correctedSequence.requested, gateway.saveDrainAndRead(null, null));
        assertEquals(Arrays.asList("drain", "save", "drain", "read"),
                correctedSequence.calls);
    }

    @Test
    void successfulFenceReportsSeparateDeterministicDrainDurationsAndIdentity() {
        FaultPersistence persistence = new FaultPersistence(Failure.NONE);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                persistence,
                diagnostics,
                clock(100L, 160L, 200L, 350L));

        ForgeRecallWorldGateway.PhysicalFenceAttempt attempt =
                gateway.attemptSaveDrainAndRead(null, null);
        gateway.recordFenceDiagnostic(
                new ForgeRecallWorldGateway.FenceDiagnosticContext(
                        "operation-1", "mount-1", "SPAWNED", "candidate_present", 7, -3, 9),
                attempt,
                CheckpointStatus.VERIFIED);

        assertEquals(DiagnosticCategory.LIFECYCLE, diagnostics.category);
        assertEquals("transfer_physical_fence", diagnostics.event);
        assertEquals("operation-1", diagnostics.fields.get("correlation"));
        assertEquals("mount-1", diagnostics.fields.get("mount"));
        assertEquals("SPAWNED", diagnostics.fields.get("phase"));
        assertEquals("candidate_present", diagnostics.fields.get("fence"));
        assertEquals("7", diagnostics.fields.get("dimension"));
        assertEquals("-3", diagnostics.fields.get("chunk_x"));
        assertEquals("9", diagnostics.fields.get("chunk_z"));
        assertEquals("60", diagnostics.fields.get("pre_drain_nanos"));
        assertEquals("150", diagnostics.fields.get("post_drain_nanos"));
        assertEquals("complete", diagnostics.fields.get("persistence_stage"));
        assertEquals("VERIFIED", diagnostics.fields.get("result"));
    }

    @Test
    void failedPostDrainReportsBothMeasuredDurationsAndFailureStage() {
        FaultPersistence persistence = new FaultPersistence(Failure.POST_DRAIN);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                persistence,
                diagnostics,
                clock(10L, 20L, 30L, 70L));

        ForgeRecallWorldGateway.PhysicalFenceAttempt attempt =
                gateway.attemptSaveDrainAndRead(null, null);
        gateway.recordFenceDiagnostic(
                new ForgeRecallWorldGateway.FenceDiagnosticContext(
                        "operation-2", "mount-2", "ASSOCIATED", "source_absent", -1, 4, 5),
                attempt,
                CheckpointStatus.FAILED);

        assertEquals("10", diagnostics.fields.get("pre_drain_nanos"));
        assertEquals("40", diagnostics.fields.get("post_drain_nanos"));
        assertEquals("post_drain", diagnostics.fields.get("persistence_stage"));
        assertEquals("FAILED", diagnostics.fields.get("result"));
    }

    @Test
    void oneShotPhysicalFenceFaultStopsBeforePostDrainAndReportsTheInjectedStage() {
        FaultPersistence persistence = new FaultPersistence(Failure.NONE);
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicBoolean armed = new AtomicBoolean(true);
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                persistence,
                diagnostics,
                clock(10L, 30L),
                () -> armed.getAndSet(false),
                ignored -> false);

        ForgeRecallWorldGateway.PhysicalFenceAttempt attempt =
                gateway.attemptSaveDrainAndRead(null, null);
        gateway.recordFenceDiagnostic(
                new ForgeRecallWorldGateway.FenceDiagnosticContext(
                        "operation-3", "mount-3", "CANDIDATE_SPAWNED",
                        "candidate_present", -1, 4, 5),
                attempt,
                CheckpointStatus.FAILED);

        assertEquals(Arrays.asList("drain-1", "save"), persistence.calls);
        assertEquals("20", diagnostics.fields.get("pre_drain_nanos"));
        assertEquals("not_completed", diagnostics.fields.get("post_drain_nanos"));
        assertEquals("injected_post_drain", diagnostics.fields.get("persistence_stage"));
        assertEquals(false, armed.get());
    }

    @Test
    void actionIntentPauseIsRejectedEvenWhenDevelopmentPredicateRequestsIt() {
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                new FaultPersistence(Failure.NONE),
                new RecordingDiagnostics(),
                clock(1L, 2L),
                () -> false,
                ignored -> true);

        assertTrue(gateway.pauseAfterPhase(
                com.mahghuuuls.mountcollection.persistence.TransferPhase.ASSOCIATED));
        assertEquals(false, gateway.pauseAfterPhase(
                com.mahghuuuls.mountcollection.persistence.TransferPhase.CANDIDATE_SPAWN_INTENT));
        assertEquals(false, gateway.pauseAfterPhase(
                com.mahghuuuls.mountcollection.persistence.TransferPhase.SOURCE_REMOVAL_INTENT));
    }

    @Test
    void acknowledgedIntentPhaseIsForwardedToDevelopmentControl() {
        List<com.mahghuuuls.mountcollection.persistence.TransferPhase> phases = new ArrayList<>();
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(
                new FaultPersistence(Failure.NONE),
                new RecordingDiagnostics(),
                clock(1L, 2L),
                () -> false,
                ignored -> false,
                phases::add);

        gateway.transferPhaseAcknowledged(
                com.mahghuuuls.mountcollection.persistence.TransferPhase.SOURCE_REMOVAL_INTENT);

        assertEquals(
                java.util.Collections.singletonList(
                        com.mahghuuuls.mountcollection.persistence.TransferPhase.SOURCE_REMOVAL_INTENT),
                phases);
    }

    private static LongSupplier clock(Long... values) {
        Queue<Long> readings = new ArrayDeque<>(Arrays.asList(values));
        return () -> readings.remove();
    }

    private static void assertFailure(Failure failure, String... expectedCalls) {
        FaultPersistence persistence = new FaultPersistence(failure);
        ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(persistence);

        assertNull(gateway.saveDrainAndRead(null, null));
        assertEquals(Arrays.asList(expectedCalls), persistence.calls);
    }

    private enum Failure {
        NONE,
        PRE_DRAIN,
        SAVE,
        POST_DRAIN,
        READ
    }

    private static final class FaultPersistence
            implements ForgeRecallWorldGateway.ChunkPersistence {
        private final Failure failure;
        private final List<String> calls = new ArrayList<>();
        private final NBTTagCompound readback = new NBTTagCompound();
        private int drainCount;

        private FaultPersistence(Failure failure) {
            this.failure = failure;
        }

        @Override
        public void drain() throws Exception {
            drainCount++;
            calls.add("drain-" + drainCount);
            failAt(drainCount == 1 ? Failure.PRE_DRAIN : Failure.POST_DRAIN);
        }

        @Override
        public void save(WorldServer world, Chunk chunk) throws Exception {
            calls.add("save");
            failAt(Failure.SAVE);
        }

        @Override
        public NBTTagCompound read(WorldServer world, Chunk chunk) throws Exception {
            calls.add("read");
            failAt(Failure.READ);
            return readback;
        }

        private void failAt(Failure stage) throws Exception {
            if (failure == stage) {
                throw new Exception(stage.name());
            }
        }
    }

    private static final class InFlightPersistence
            implements ForgeRecallWorldGateway.ChunkPersistence {
        private final List<String> calls = new ArrayList<>();
        private final NBTTagCompound stale = new NBTTagCompound();
        private final NBTTagCompound requested = new NBTTagCompound();
        private NBTTagCompound visible;
        private NBTTagCompound pending;

        private InFlightPersistence() {
            stale.setString("fact", "stale");
            requested.setString("fact", "requested");
            pending = stale;
        }

        @Override
        public void drain() {
            calls.add("drain");
            if (pending != null) {
                visible = pending;
                pending = null;
            }
        }

        @Override
        public void save(WorldServer world, Chunk chunk) {
            calls.add("save");
            if (pending == null) {
                pending = requested;
            }
        }

        @Override
        public NBTTagCompound read(WorldServer world, Chunk chunk) {
            calls.add("read");
            return visible;
        }
    }

    private static final class RecordingDiagnostics implements DiagnosticSink {
        private DiagnosticCategory category;
        private String event;
        private Map<String, String> fields = new LinkedHashMap<>();

        @Override
        public void detail(
                DiagnosticCategory category,
                String event,
                Map<String, String> fields) {
            this.category = category;
            this.event = event;
            this.fields = new LinkedHashMap<>(fields);
        }

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}

        @Override
        public void essentialLifecycleWarning(String event, String detail) {}
    }

    private static final class PreparingProvider implements MountProvider, PreparationSupport {
        private boolean called;

        @Override
        public ResourceLocation getProviderId() {
            return new ResourceLocation("mountcollection", "preparation-test");
        }

        @Override
        public boolean supports(Entity entity) {
            return true;
        }

        @Override
        public ProviderResult<RegistrationProfile> validateRegistration(
                Entity entity, UUID playerId) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }

        @Override
        public ProviderResult<Void> prepareForPlacement(Entity mount) {
            called = true;
            mount.setCustomNameTag("prepared-candidate");
            return ProviderResult.success();
        }
    }

    private static final class FaultSourceRemoval
            implements ForgeRecallWorldGateway.SourceRemovalAccess {
        private final Object originalVehicle = new Object();
        private final Object originalLeashHolder = new Object();
        private Object currentVehicle = originalVehicle;
        private Object currentLeashHolder = originalLeashHolder;
        private boolean removalSucceeds;
        private boolean removalThrows;
        private boolean ridingRestoreSucceeds = true;
        private boolean leashRestoreSucceeds = true;
        private boolean removed;
        private int leadDrops;

        @Override
        public Object getVehicle() {
            return currentVehicle;
        }

        @Override
        public boolean isRiding() {
            return currentVehicle != null;
        }

        @Override
        public boolean isRiding(Object vehicle) {
            return currentVehicle == vehicle;
        }

        @Override
        public void dismount() {
            currentVehicle = null;
        }

        @Override
        public boolean startRiding(Object vehicle) {
            if (ridingRestoreSucceeds) {
                currentVehicle = vehicle;
            }
            return ridingRestoreSucceeds;
        }

        @Override
        public boolean isLeashed() {
            return currentLeashHolder != null;
        }

        @Override
        public Object getLeashHolder() {
            return currentLeashHolder;
        }

        @Override
        public void clearLeash() {
            currentLeashHolder = null;
        }

        @Override
        public void restoreLeash(Object holder) {
            if (leashRestoreSucceeds) {
                currentLeashHolder = holder;
            }
        }

        @Override
        public boolean isLeashedTo(Object holder) {
            return currentLeashHolder == holder;
        }

        @Override
        public void remove() {
            if (removalThrows) {
                throw new IllegalStateException("injected removal fault");
            }
            removed = removalSucceeds;
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public void dropLead() {
            leadDrops++;
        }
    }

    private static final class FaultCandidateRemoval
            implements ForgeRecallWorldGateway.CandidateRemovalAccess {
        private boolean removeCalled;

        @Override
        public boolean isExact() {
            return true;
        }

        @Override
        public void remove() {
            removeCalled = true;
            throw new IllegalStateException("injected candidate removal fault");
        }

        @Override
        public boolean isRemoved() {
            return false;
        }
    }
}
