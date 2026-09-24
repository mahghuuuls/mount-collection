package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.persistence.*;
import com.mahghuuuls.mountcollection.lifecycle.ArrivalDisposition;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.*;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real gateway readiness/return search with controlled world queries, not a running Forge server. */
final class BoardingReadinessGatewayTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void preparationCannotTurnUnsupportedMountIntoFallback() throws Exception {
        for (ArrivalDisposition disposition : new ArrivalDisposition[]{ArrivalDisposition.COMBINED,
                ArrivalDisposition.UNMOUNTED_FALLBACK}) {
            Fixture f = new Fixture(); f.mount.setPosition(8.5,64,8.5);
            f.provider.invalidateOnPrepare = true;
            RecallWorldGateway.Destination target = new RecallWorldGateway.Destination(
                    new LastKnownEvidence(0,3.5,64,3.5), f.record.getCharacteristics(), disposition);
            assertFalse(f.gateway.commit(f.player, f.source(), target, f.provider));
            assertEquals(8.5, f.mount.posX); assertEquals(8.5, f.mount.posZ);
            assertEquals(0, f.player.attempts);
        }
    }

    @Test void preparationFatalEscapesOrdinaryAndCandidatePathsUnchanged() throws Exception {
        Fixture f = new Fixture();
        f.provider.fatal = com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException
                .abandonmentFailure(UUID.randomUUID());
        RecallWorldGateway.Destination target = new RecallWorldGateway.Destination(
                new LastKnownEvidence(0,3.5,64,3.5), f.record.getCharacteristics());
        assertSame(f.provider.fatal, assertThrows(com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException.class,
                () -> f.gateway.commit(f.player, f.source(), target, f.provider)));
        assertSame(f.provider.fatal, assertThrows(com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException.class,
                () -> ForgeRecallWorldGateway.prepareCandidate(f.mount, f.provider)));
    }

    @Test void serviceSuppressesFallbackBoardingEvenWhenSeatIsAvailable() throws Exception {
        Fixture f = new Fixture();
        com.mahghuuuls.mountcollection.provider.ProviderRegistry registry =
                new com.mahghuuuls.mountcollection.provider.ProviderRegistry();
        registry.register(f.provider);
        MountCollectionServices services = new MountCollectionServices(registry, null,
                new com.mahghuuuls.mountcollection.diagnostics.MountCollectionDiagnostics(
                        org.apache.logging.log4j.LogManager.getLogger("boarding-test")), null);
        Field repository = MountCollectionServices.class.getDeclaredField("activeRepository");
        repository.setAccessible(true); repository.set(services, f.repository);
        Field gateway = MountCollectionServices.class.getDeclaredField("worldGateway");
        gateway.setAccessible(true); gateway.set(services, f.gateway);
        com.mahghuuuls.mountcollection.lifecycle.ExperienceCompletion completion =
                new com.mahghuuuls.mountcollection.lifecycle.ExperienceCompletion(UUID.randomUUID(),
                        f.player.getUniqueID(), f.record.getMountId(), f.mount.getUniqueID(),
                        f.record.getLastKnown(), com.mahghuuuls.mountcollection.lifecycle.ExperienceCompletion.Kind.ARRIVED);
        assertFalse(services.boardArrived(f.player, completion.withDisposition(ArrivalDisposition.UNMOUNTED_FALLBACK)));
        assertEquals(0, f.player.attempts);
        // A native veto still proves that the otherwise-identical combined control reaches boarding.
        assertFalse(services.boardArrived(f.player, completion.withDisposition(ArrivalDisposition.COMBINED)));
        assertEquals(1, f.player.attempts);
    }

    @Test void missingSeatStillPlansSafeUnmountedArrival() throws Exception {
        Fixture f = new Fixture(); f.provider.seatAvailable = false;
        f.mount.setPosition(8.5, 64, 8.5);
        RecallWorldGateway.Destination destination = f.plan().get();
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, destination.getDisposition());
        double playerX = f.player.posX, playerZ = f.player.posZ;
        assertTrue(f.gateway.commit(f.player, f.source(), destination, f.provider));
        assertEquals(playerX, f.player.posX); assertEquals(playerZ, f.player.posZ);
        assertFalse(f.mount.getEntityBoundingBox().intersects(f.player.getEntityBoundingBox()));
        assertEquals(0, f.player.attempts);
    }

    @Test void riderClearanceFallsBackButUnsafeStandingPlayerDoesNot() throws Exception {
        Fixture f = new Fixture(); f.mount.setPosition(8.5,64,8.5); f.world.lowCeiling = true;
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, f.plan().get().getDisposition());
        f.world.onlySeatedSpace = true;
        assertFalse(f.plan().isPresent());
    }

    @Test void absentBoardingCapabilityUsesFallbackButBorderStillApplies() throws Exception {
        Fixture f = new Fixture(); f.mount.setPosition(8.5,64,8.5);
        MountProvider provider = new MountProvider() {
            public ResourceLocation getProviderId() { return new ResourceLocation("test:no_seat"); }
            public boolean supports(Entity entity) { return true; }
            public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) {
                throw new AssertionError();
            }
        };
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, f.gateway.planWithRider(f.player, f.source(),
                provider, f.record.getCharacteristics(), 2,4).get().getDisposition());
        f.world.border.setCenter(.5,.5); f.world.border.setTransition(1);
        assertFalse(f.gateway.planWithRider(f.player, f.source(), provider,
                f.record.getCharacteristics(), 2,4).isPresent());
    }

    @Test void finalBoardingLossDegradesOnlyAtSafeFixedDestination() throws Exception {
        Fixture f = new Fixture(); f.mount.setPosition(8.5,64,8.5);
        RecallWorldGateway.Destination target = new RecallWorldGateway.Destination(
                new LastKnownEvidence(0,3.5,64,3.5), f.record.getCharacteristics());
        f.provider.seatAvailable = false;
        assertTrue(f.gateway.commit(f.player, f.source(), target, f.provider));
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, target.getDisposition());
        assertEquals(3.5, f.mount.posX);
        RecallWorldGateway.Destination overlapping = new RecallWorldGateway.Destination(
                new LastKnownEvidence(0,.5,64,.5), f.record.getCharacteristics());
        assertFalse(f.gateway.commit(f.player, f.source(), overlapping, f.provider));
        assertEquals(3.5, f.mount.posX);
    }

    @Test void plannedFallbackNeverUpgradesAndUnsupportedMountStillRejects() throws Exception {
        Fixture f = new Fixture(); f.mount.setPosition(8.5,64,8.5); f.provider.seatAvailable = false;
        RecallWorldGateway.Destination destination = f.plan().get();
        f.provider.seatAvailable = true;
        assertTrue(f.gateway.commit(f.player, f.source(), destination, f.provider));
        assertEquals(ArrivalDisposition.UNMOUNTED_FALLBACK, destination.getDisposition());
        f.provider.supported = false;
        assertFalse(f.plan().isPresent());
        assertFalse(f.gateway.commit(f.player, f.source(), destination, f.provider));
    }

    @Test void arrivedMountAtPlayerPoseStillReachesNativeAttemptWithSafeFallback() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.mount.getEntityBoundingBox().intersects(f.player.getEntityBoundingBox()));
        // Native call deliberately vetoes: count distinguishes admission from the old early rejection.
        assertFalse(f.gateway.boardArrived(f.player, f.record, f.provider));
        assertEquals(1, f.player.attempts);
        assertFalse(f.mount.getEntityBoundingBox().intersects(f.player.getEntityBoundingBox()));
        assertFalse(f.player.isRiding());
        assertEquals(1, f.world.tracker.sends);
    }

    @Test void noSafeUnmountedFallbackRejectsBeforeNativeAttempt() throws Exception {
        Fixture f = new Fixture(); f.world.onlySeatedSpace = true;
        assertFalse(f.gateway.boardArrived(f.player, f.record, f.provider));
        assertEquals(0, f.player.attempts);
    }

    @Test void returnSearchKeepsMountAsObstacleAndRechecksChangedWorld() throws Exception {
        Fixture f = new Fixture();
        java.lang.reflect.Method search = ForgeRecallWorldGateway.class.getDeclaredMethod("unmountedReturn",
                EntityPlayerMP.class, WorldServer.class, double.class, double.class, double.class);
        search.setAccessible(true);
        Optional<?> pose = (Optional<?>) search.invoke(null, f.player, f.world, .5D, 64D, .5D);
        assertTrue(pose.isPresent());
        Vec3d point = (Vec3d) pose.get();
        assertFalse(f.mount.getEntityBoundingBox().intersects(new AxisAlignedBB(point.x-.3, point.y, point.z-.3,
                point.x+.3, point.y+1.8, point.z+.3)));
        f.world.onlySeatedSpace = true;
        assertFalse(((Optional<?>) search.invoke(null, f.player, f.world, .5D, 64D, .5D)).isPresent());
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class Fixture {
        final ProbeWorld world = allocate(ProbeWorld.class);
        final Player player = allocate(Player.class);
        final EntityBoat mount = new EntityBoat(new BoardingTestWorld());
        final Provider provider = new Provider();
        final MountRepository repository = new MountRepository();
        final ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway((com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink)null);
        final MountRecord record;
        RecallWorldGateway.Source source() {
            return new RecallWorldGateway.Source(mount.getUniqueID(), 0, false, mount);
        }
        Optional<RecallWorldGateway.Destination> plan() {
            return gateway.planWithRider(player, source(), provider, record.getCharacteristics(), 2, 4);
        }
        Fixture() throws Exception {
            world.border = new WorldBorder(); world.mount = mount;
            world.tracker = allocate(Tracker.class);
            player.world = world; player.width = .6F; player.height = 1.8F;
            player.setUniqueId(UUID.randomUUID()); player.setPosition(.5,64,.5);
            mount.world = world; mount.setPosition(.5,64,.5);
            record = repository.register(new MountRepository.RegistrationCandidate(player.getUniqueID(),
                    provider.getProviderId(), new ResourceLocation("minecraft:boat"), "boat", mount.getUniqueID(),
                    new LastKnownEvidence(0,.5,64,.5), null)).getRecord().get();
            EntityMountEvidence.attach(mount, record.getMountId());
        }
    }

    private static final class Player extends EntityPlayerMP {
        int attempts;
        private Player() { super(null,null,null,null); }
        @Override public boolean isEntityAlive() { return true; }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isPlayerSleeping() { return false; }
        @Override public boolean isBeingRidden() { return false; }
        @Override public boolean startRiding(Entity entity, boolean force) { assertFalse(force); attempts++; return false; }
    }

    private static final class ProbeWorld extends WorldServer {
        WorldBorder border; Entity mount; boolean onlySeatedSpace, lowCeiling;
        Tracker tracker;
        private ProbeWorld() { super(null,null,null,0,null); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public net.minecraft.entity.EntityTracker getEntityTracker() { return tracker; }
        @Override public int getHeight() { return 256; }
        @Override public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) { return true; }
        @Override public Entity getEntityFromUuid(UUID id) { return mount.getUniqueID().equals(id) ? mount : null; }
        @Override public IBlockState getBlockState(BlockPos pos) {
            return (pos.getY()<64 ? Blocks.STONE : Blocks.AIR).getDefaultState();
        }
        @Override public boolean containsAnyLiquid(AxisAlignedBB box) { return false; }
        @Override public List<AxisAlignedBB> getCollisionBoxes(Entity entity, AxisAlignedBB box) {
            // Preserve candidate/seated clearance while denying every unmounted search position.
            boolean blocked = box.minY<64 || lowCeiling && box.maxY>66 || onlySeatedSpace && box.maxY-box.minY>1.7
                    && Math.abs(box.minY-65.65)>0.001;
            return blocked ? Collections.singletonList(box) : Collections.emptyList();
        }
        @Override public List<Entity> getEntitiesWithinAABBExcludingEntity(Entity excluded, AxisAlignedBB box) {
            return mount!=excluded && mount.getEntityBoundingBox().intersects(box)
                    ? Collections.singletonList(mount) : Collections.emptyList();
        }
    }

    private static final class Tracker extends net.minecraft.entity.EntityTracker {
        int sends;
        private Tracker() { super(null); }
        @Override public void sendToTracking(Entity entity, net.minecraft.network.Packet<?> packet) { sends++; }
    }

    private static final class Provider implements MountProvider, BoardingSupport, PreparationSupport {
        boolean seatAvailable = true, supported = true;
        boolean invalidateOnPrepare;
        com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException fatal;
        public ProviderResult<Void> prepareForPlacement(Entity entity) {
            if (fatal != null) { throw fatal; }
            if (invalidateOnPrepare) { supported = false; }
            return ProviderResult.success();
        }
        public ResourceLocation getProviderId() { return new ResourceLocation("test:readiness"); }
        public boolean supports(Entity entity) { return supported; }
        public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) { throw new AssertionError(); }
        public ProviderResult<SeatEnvelope> describeBoarding(Entity mount, UUID rider) {
            if (!seatAvailable) { return null; }
            return ProviderResult.success(new SeatEnvelope(0,2,0,0,2,0));
        }
    }
}
