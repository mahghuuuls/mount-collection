package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.lifecycle.*;
import com.mahghuuuls.mountcollection.persistence.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes production gateway ordering and mode policy; world I/O/collision answers are controlled. */
final class RecoveryAdmissionGatewayTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void transferCaptureRejectsSupportLostDuringPreparationBeforeProducingIntent() throws Exception {
        Fixture f = new Fixture(); f.provider.invalidateOnPrepare = true;
        EntityBoat source = new EntityBoat(f.access.world); source.dimension = 1;
        source.setUniqueId(f.record.getPhysicalEntityId()); source.setPosition(2,64,2);
        assertFalse(f.gateway.captureTransfer(f.player,
                new RecallWorldGateway.Source(source.getUniqueID(), 1, false, source),
                new RecallWorldGateway.Destination(new LastKnownEvidence(0,8,64,8),
                        f.record.getCharacteristics()), f.provider).isPresent());
        assertEquals(1, f.access.created.size()); assertEquals(1, f.provider.preparations);
        assertEquals(0, f.access.spawns); assertEquals(2, source.posX);
    }

    @Test void transferReconstructionIsCheckedBeforeSpawnAndDowngradesLiveCompletion() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false;
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {}, ignored -> {});
        List<ExperienceCompletion> completed = new ArrayList<>();
        coordinator.admit(f.intent.getOperationId(), f.owner, () -> true, completed::add,
                () -> RecoveryAdmission.automatic(f.player));
        f.gateway.setRecoveryAdmission(coordinator::recoveryAdmission);
        TransferOperation operation = f.transfer();
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS,
                f.gateway.spawnCandidate(operation, f.record, f.provider));
        assertEquals(1, f.access.created.size());
        Entity candidate = f.access.created.get(0);
        assertSame(candidate, f.access.spawned);
        assertEquals(Arrays.asList(candidate, candidate), f.access.checked);
        assertEquals(8, candidate.posX); assertEquals(64, candidate.posY); assertEquals(8, candidate.posZ);
        assertEquals(8, operation.getDestinationEvidence().getX());
        assertEquals(operation.getCandidateEntityId(), candidate.getUniqueID());
        f.access.autoSafe = true; // Later seat availability cannot change the recorded decision.
        coordinator.complete(new ExperienceCompletion(operation.getOperationId(), f.owner, f.record.getMountId(),
                candidate.getUniqueID(), operation.getDestinationEvidence(), ExperienceCompletion.Kind.ARRIVED));
        assertEquals(1, completed.size());
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, completed.get(0).getDisposition());
    }

    @Test void transferFinalGuardRejectsUnsafeUnavailableAndUnsupportedButNotExpired() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Fixture f = new Fixture(); f.access.autoSafe = false; f.access.fallbackSafe = false;
            if (mode == 1) { f.context = RecoveryAdmission.unavailable(); }
            if (mode == 2) { f.provider.supported = false; }
            if (mode == 3) { f.context = RecoveryAdmission.expired(); }
            assertEquals(mode == 3 ? RecallWorldGateway.CandidateAction.SUCCESS : RecallWorldGateway.CandidateAction.UNAVAILABLE,
                    f.gateway.spawnCandidate(f.transfer(), f.record, f.provider));
            assertEquals(mode == 3 ? 1 : 0, f.access.spawns);
            assertEquals(mode == 3 ? 1 : 0, f.access.mountChecks);
            assertEquals(1, f.access.created.size());
        }
    }

    @Test void transferCombinedControlChecksTheSameInstanceThatSpawns() throws Exception {
        Fixture f = new Fixture();
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS,
                f.gateway.spawnCandidate(f.transfer(), f.record, f.provider));
        assertEquals(Collections.singletonList(f.access.spawned), f.access.checked);
        assertEquals(1, f.access.autoChecks); assertEquals(0, f.access.fallbackChecks);
    }

    @Test void initialRiderPlanRetainsItsExactCandidateAndDenialProducesNoAttempt() throws Exception {
        Fixture f = new Fixture();
        RecallWorldGateway.RecoveryPlan plan = f.gateway.planRecoveryWithRider(f.player, f.record, f.provider, 2, 4).get();
        RestorationOperation intent = new RestorationOperation(f.intent.getOperationId(), f.record.getMountId(), f.owner,
                plan.getCandidateEntityId(), plan.getDestination().getEvidence(), 20, 20,
                RestorationPhase.CANDIDATE_SPAWN_INTENT, null);
        Entity planned = f.access.created.get(0);
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, plan.getAttempt().admit(intent));
        assertSame(planned, f.access.spawned); assertEquals(1, f.access.created.size());
        assertEquals(Arrays.asList(planned, planned), f.access.checked);
        Fixture g = new Fixture(); g.access.autoSafe = false; g.access.fallbackSafe = false;
        assertFalse(g.gateway.planRecoveryWithRider(g.player, g.record, g.provider, 2, 4).isPresent());
        assertEquals(0, g.access.spawns);
    }

    @Test void exactlyCheckedNativeInstanceIsAdmittedWithoutSecondReconstruction() throws Exception {
        Fixture f = new Fixture();
        RecoveryAttempt attempt = f.prepare().get();
        Entity checked = f.access.created.get(0);
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, attempt.admit(f.intent));
        assertEquals(1, f.access.created.size()); assertSame(checked, f.access.spawned);
        assertEquals(Arrays.asList(checked, checked), f.access.checked);
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(f.intent));
        assertEquals(1, f.access.spawns);
    }

    @Test void finalSeatLossFallsBackAtFixedTargetButUnsafeFallbackCannotSpawn() throws Exception {
        Fixture f = new Fixture(); RecoveryAttempt attempt = f.prepare().get();
        f.access.autoSafe = false;
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, attempt.admit(f.intent));
        assertEquals(f.intent.getDestinationEvidence().getX(), f.access.spawned.posX);
        assertEquals(f.intent.getDestinationEvidence().getZ(), f.access.spawned.posZ);
        assertEquals(1, f.access.created.size()); assertEquals(1, f.access.fallbackChecks);
        Fixture g = new Fixture(); RecoveryAttempt denied = g.prepare().get();
        g.access.autoSafe = false; g.access.fallbackSafe = false;
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, denied.admit(g.intent));
        assertEquals(0, g.access.spawns);
    }

    @Test void plannedFallbackRemainsUnmountedThroughActualRecoveryAdmission() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false;
        ExperienceCoordinator coordinator = new ExperienceCoordinator(event -> {}, ignored -> {});
        coordinator.admit(f.intent.getOperationId(), f.owner, () -> true, event ->
                assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, event.getDisposition()),
                () -> RecoveryAdmission.automatic(f.player));
        f.gateway.setRecoveryAdmission(coordinator::recoveryAdmission);
        RecoveryAttempt attempt = f.prepare().get();
        f.access.autoSafe = true;
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, attempt.admit(f.intent));
        assertEquals(1, f.access.autoChecks); assertEquals(2, f.access.fallbackChecks);
        coordinator.complete(new ExperienceCompletion(f.intent.getOperationId(), f.owner, f.record.getMountId(),
                f.intent.getCandidateEntityId(), f.intent.getDestinationEvidence(), ExperienceCompletion.Kind.ARRIVED));
    }

    @Test void finalGeometryOrEligibilityChangesRejectAfterIntent() throws Exception {
        for (boolean geometry : new boolean[]{false, true}) {
            Fixture f = new Fixture(); RecoveryAttempt attempt = f.prepare().get();
            if (geometry) { f.access.created.get(0).width = 9; } else { f.provider.supported = false; }
            assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(f.intent));
            assertEquals(0, f.access.spawns);
        }
    }

    @Test void initialAutoDenialAndFreshChangedReconstructionAreChecked() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false; f.access.fallbackSafe = false;
        assertFalse(f.prepare().isPresent()); assertEquals(0, f.access.spawns);
        Fixture g = new Fixture(); RecoveryAttempt discarded = g.prepare().get(); discarded.close();
        // Second construction has a different geometry, not a fake preparation-denied switch.
        assertFalse(g.prepare().isPresent());
        assertEquals(2, g.access.created.size());
        assertNotSame(g.access.created.get(0), g.access.created.get(1));
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, discarded.admit(g.intent));
        assertEquals(0, g.access.spawns);
    }

    @Test void currentAutoFallsBackButOptOutAndExpiredUseMountChecks() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false;
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, f.prepare().get().admit(f.intent));
        assertEquals(2, f.access.fallbackChecks);
        for (RecoveryAdmission context : new RecoveryAdmission[]{RecoveryAdmission.optOut(), RecoveryAdmission.expired()}) {
            Fixture g = new Fixture(); g.context = context; g.access.autoSafe = false;
            assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, g.prepare().get().admit(g.intent));
            assertEquals(2, g.access.mountChecks); assertEquals(0, g.access.autoChecks);
        }
    }

    @Test void unavailableNullThrowingAndWrongOwnerFailClosedAtActualGateway() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Fixture f = new Fixture();
            if (mode == 0) { f.context = RecoveryAdmission.unavailable(); }
            if (mode == 1) { f.context = null; }
            if (mode == 2) { f.lookupThrows = true; }
            if (mode == 3) { f.player.setUniqueId(UUID.randomUUID()); }
            assertFalse(f.prepare().isPresent()); assertEquals(0, f.access.mountChecks);
            assertEquals(0, f.access.spawns);
        }
    }

    @Test void contextIsCheckedAgainImmediatelyBeforeWorldAdmission() throws Exception {
        Fixture f = new Fixture(); RecoveryAttempt attempt = f.prepare().get();
        f.context = RecoveryAdmission.unavailable();
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(f.intent));
        assertEquals(0, f.access.spawns);
        Fixture g = new Fixture(); RecoveryAttempt expiring = g.prepare().get();
        g.context = RecoveryAdmission.expired();
        assertEquals(RecallWorldGateway.CandidateAction.SUCCESS, expiring.admit(g.intent));
        assertEquals(1, g.access.autoChecks); assertEquals(1, g.access.mountChecks);
    }

    @Test void conflictingCandidateAndUnacknowledgedIntentCannotSpawn() throws Exception {
        Fixture f = new Fixture(); RecoveryAttempt attempt = f.prepare().get();
        f.access.spawned = new EntityBoat(f.access.world);
        assertEquals(RecallWorldGateway.CandidateAction.CONFLICT, attempt.admit(f.intent));
        assertEquals(0, f.access.spawns);
        Fixture g = new Fixture();
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, g.prepare().get().admit(g.prepared));
        assertEquals(0, g.access.spawns);
    }

    private static final class Fixture {
        final Access access = new Access(); final Provider provider = new Provider();
        final ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway(access);
        final UUID owner = UUID.randomUUID();
        final MountRecord record = new MountRepository().register(new MountRepository.RegistrationCandidate(
                owner, provider.getProviderId(), new ResourceLocation("minecraft:boat"), "boat", UUID.randomUUID(),
                new LastKnownEvidence(0, 2, 64, 2), null)).getRecord().get();
        final RestorationOperation intent = new RestorationOperation(UUID.randomUUID(), record.getMountId(), owner,
                UUID.randomUUID(), new LastKnownEvidence(0, 8, 64, 8), 20, 20,
                RestorationPhase.CANDIDATE_SPAWN_INTENT, null);
        final RestorationOperation prepared = new RestorationOperation(intent.getOperationId(), record.getMountId(), owner,
                intent.getCandidateEntityId(), intent.getDestinationEvidence(), 20, 20, RestorationPhase.PREPARED, null);
        final EntityPlayerMP player;
        RecoveryAdmission context; boolean lookupThrows;
        Fixture() throws Exception {
            // Carrier only: no server/player constructor or native player behavior is claimed here.
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            player = (EntityPlayerMP) ((sun.misc.Unsafe) field.get(null)).allocateInstance(EntityPlayerMP.class);
            player.world = access.world; player.setUniqueId(owner);
            context = RecoveryAdmission.automatic(player);
            gateway.setRecoveryAdmission((request, requestedOwner) -> {
                assertEquals(intent.getOperationId(), request); assertEquals(owner, requestedOwner);
                if (lookupThrows) { throw new IllegalStateException("lookup"); } return context;
            });
        }
        Optional<RecoveryAttempt> prepare() { return gateway.prepareRecoveryAttempt(prepared, record, provider); }
        TransferOperation transfer() {
            net.minecraft.nbt.NBTTagCompound snapshot = new net.minecraft.nbt.NBTTagCompound();
            snapshot.setString("id", "minecraft:boat");
            return new TransferOperation(intent.getOperationId(), record.getMountId(), owner,
                    record.getPhysicalEntityId(), intent.getCandidateEntityId(), new LastKnownEvidence(1,2,64,2),
                    intent.getDestinationEvidence(), snapshot, 20,20,TransferPhase.CANDIDATE_SPAWN_INTENT,null);
        }
    }
    private static final class Access implements ForgeRecallWorldGateway.RecoveryAccess {
        final World world = new BoardingTestWorld();
        final List<Entity> created = new ArrayList<>(), checked = new ArrayList<>();
        Entity spawned; int spawns, autoChecks, mountChecks, fallbackChecks;
        boolean autoSafe = true, fallbackSafe = true;
        public World world(int dimension) { return world; }
        public Entity reconstruct(World world, net.minecraft.nbt.NBTTagCompound snapshot) {
            Entity candidate = ForgeRecallWorldGateway.RecoveryAccess.super.reconstruct(world, snapshot);
            created.add(candidate); return candidate;
        }
        public Entity create(World world, MountRecord record, MountProvider provider, UUID id) {
            EntityBoat candidate = new EntityBoat(world); candidate.setUniqueId(id);
            candidate.width = created.isEmpty() ? 1 : 9;
            created.add(candidate); return candidate;
        }
        public Entity find(World world, UUID id) { return spawned; }
        public boolean spawn(World world, Entity candidate) {
            assertFalse(checked.isEmpty(), "admission must run before spawn");
            assertSame(candidate, checked.get(checked.size()-1));
            spawns++; spawned = candidate; return true;
        }
        public boolean mountSafe(Entity candidate, MountRecord record) {
            mountChecks++; checked.add(candidate); return candidate.width < 2;
        }
        public boolean riderSafe(EntityPlayerMP rider, Entity candidate, MountRecord record, MountProvider provider) {
            autoChecks++; checked.add(candidate); return autoSafe && candidate.width < 2;
        }
        public boolean unmountedSafe(EntityPlayerMP rider, Entity candidate, MountRecord record) {
            fallbackChecks++; checked.add(candidate); return fallbackSafe && candidate.width < 2;
        }
        public Optional<RecallWorldGateway.Destination> plan(EntityPlayerMP rider, Entity candidate, MountRecord record,
                MountProvider provider, int normalRadius, int fallbackRadius) {
            if (riderSafe(rider, candidate, record, provider)) {
                return Optional.of(new RecallWorldGateway.Destination(new LastKnownEvidence(0, 8, 64, 8), record.getCharacteristics()));
            }
            return unmountedSafe(rider, candidate, record) ? Optional.of(new RecallWorldGateway.Destination(
                    new LastKnownEvidence(0, 8, 64, 8), record.getCharacteristics(), ArrivalDisposition.UNMOUNTED_FALLBACK))
                    : Optional.empty();
        }
    }
    private static final class Provider implements MountProvider, PreparationSupport {
        boolean supported = true;
        boolean invalidateOnPrepare; int preparations;
        public ProviderResult<Void> prepareForPlacement(Entity entity) {
            preparations++;
            if (invalidateOnPrepare) { supported = false; }
            return ProviderResult.success();
        }
        public ResourceLocation getProviderId() { return new ResourceLocation("test:admission"); }
        public boolean supports(Entity entity) { return supported; }
        public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) {
            throw new AssertionError("not a registration test");
        }
    }
}
