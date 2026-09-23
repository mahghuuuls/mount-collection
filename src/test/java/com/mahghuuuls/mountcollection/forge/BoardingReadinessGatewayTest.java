package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.persistence.*;
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
        final ForgeRecallWorldGateway gateway = new ForgeRecallWorldGateway((com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink)null);
        final MountRecord record;
        Fixture() throws Exception {
            world.border = new WorldBorder(); world.mount = mount;
            world.tracker = allocate(Tracker.class);
            player.world = world; player.width = .6F; player.height = 1.8F;
            player.setUniqueId(UUID.randomUUID()); player.setPosition(.5,64,.5);
            mount.world = world; mount.setPosition(.5,64,.5);
            record = new MountRepository().register(new MountRepository.RegistrationCandidate(player.getUniqueID(),
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
        WorldBorder border; Entity mount; boolean onlySeatedSpace;
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
            boolean blocked = box.minY<64 || onlySeatedSpace && box.maxY-box.minY>1.7
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

    private static final class Provider implements MountProvider, BoardingSupport {
        public ResourceLocation getProviderId() { return new ResourceLocation("test:readiness"); }
        public boolean supports(Entity entity) { return true; }
        public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) { throw new AssertionError(); }
        public ProviderResult<SeatEnvelope> describeBoarding(Entity mount, UUID rider) {
            return ProviderResult.success(new SeatEnvelope(0,2,0,0,2,0));
        }
    }
}
