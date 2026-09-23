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
        Fixture g = new Fixture(); g.access.autoSafe = false;
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

    @Test void finalGeometryOrEligibilityChangesRejectAfterIntent() throws Exception {
        for (boolean geometry : new boolean[]{false, true}) {
            Fixture f = new Fixture(); RecoveryAttempt attempt = f.prepare().get();
            if (geometry) { f.access.created.get(0).width = 9; } else { f.provider.supported = false; }
            assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, attempt.admit(f.intent));
            assertEquals(0, f.access.spawns);
        }
    }

    @Test void initialAutoDenialAndFreshChangedReconstructionAreChecked() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false;
        assertFalse(f.prepare().isPresent()); assertEquals(0, f.access.spawns);
        Fixture g = new Fixture(); RecoveryAttempt discarded = g.prepare().get(); discarded.close();
        // Second construction has a different geometry, not a fake preparation-denied switch.
        assertFalse(g.prepare().isPresent());
        assertEquals(2, g.access.created.size());
        assertNotSame(g.access.created.get(0), g.access.created.get(1));
        assertEquals(RecallWorldGateway.CandidateAction.UNAVAILABLE, discarded.admit(g.intent));
        assertEquals(0, g.access.spawns);
    }

    @Test void currentAutoNeverFallsBackButOptOutAndExpiredUseMountChecks() throws Exception {
        Fixture f = new Fixture(); f.access.autoSafe = false;
        assertFalse(f.prepare().isPresent()); assertEquals(0, f.access.mountChecks);
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
    }
    private static final class Access implements ForgeRecallWorldGateway.RecoveryAccess {
        final World world = new BoardingTestWorld();
        final List<Entity> created = new ArrayList<>(), checked = new ArrayList<>();
        Entity spawned; int spawns, autoChecks, mountChecks; boolean autoSafe = true;
        public World world(int dimension) { return world; }
        public Entity create(World world, MountRecord record, MountProvider provider, UUID id) {
            EntityBoat candidate = new EntityBoat(world); candidate.setUniqueId(id);
            candidate.width = created.isEmpty() ? 1 : 9;
            created.add(candidate); return candidate;
        }
        public Entity find(World world, UUID id) { return spawned; }
        public boolean spawn(World world, Entity candidate) { spawns++; spawned = candidate; return true; }
        public boolean mountSafe(Entity candidate, MountRecord record) {
            mountChecks++; checked.add(candidate); return candidate.width < 2;
        }
        public boolean riderSafe(EntityPlayerMP rider, Entity candidate, MountRecord record, MountProvider provider) {
            autoChecks++; checked.add(candidate); return autoSafe && candidate.width < 2;
        }
        public Optional<RecallWorldGateway.Destination> plan(EntityPlayerMP rider, Entity candidate, MountRecord record,
                MountProvider provider, int normalRadius, int fallbackRadius) {
            return riderSafe(rider, candidate, record, provider)
                    ? Optional.of(new RecallWorldGateway.Destination(new LastKnownEvidence(0, 8, 64, 8),
                            record.getCharacteristics())) : Optional.empty();
        }
    }
    private static final class Provider implements MountProvider {
        boolean supported = true;
        public ResourceLocation getProviderId() { return new ResourceLocation("test:admission"); }
        public boolean supports(Entity entity) { return supported; }
        public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) {
            throw new AssertionError("not a registration test");
        }
    }
}
