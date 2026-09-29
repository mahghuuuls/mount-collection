package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.network.RelocationMessage;
import java.util.UUID;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ClientRelocationsTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    @Test void armedTraceObservesRealReceiverBeforeAfterAndFiniteNativeSamples() throws Exception {
        TraceHarness h = new TraceHarness();
        h.view.receive(h.client, h.message(1), h.handler);
        assertTrue(h.lines.isEmpty()); // Default is off even in development.
        h.boat.setPosition(339.5, 80, 328.5);
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(2), h.handler);
        assertEquals(2, h.lines.size());
        assertTrue(h.lines.get(0).contains("phase=BEFORE"));
        assertTrue(h.lines.get(0).contains("pos=339.5,80.0,328.5"));
        assertTrue(h.lines.get(1).contains("phase=APPLIED"));
        assertTrue(h.lines.get(1).contains("pos=318.5,80.0,318.5"));
        assertEquals(1234, h.boat.serverPosX);
        assertEquals(320.5, h.client.player.posX);
        for (int i = 0; i < 200; i++) { h.trace.tick(h.client); }
        assertEquals(16, h.lines.size()); // Before/after, twelve tick ends, forty and160.
        assertTrue(h.lines.get(15).contains("END_SAMPLE_160"));
        h.view.receive(h.client, h.message(3), h.handler);
        assertEquals(16, h.lines.size()); // No automatic rearm.
        assertEquals(1234, h.boat.serverPosX);
        assertFalse(h.boat.isBeingRidden());
    }

    @Test void traceExpiresAndCannotCrossWorldPlayerConnectionOrChallenge() throws Exception {
        TraceHarness h = new TraceHarness();
        assertTrue(h.arm());
        h.now[0] = 45_000_000_000L;
        h.view.receive(h.client, h.message(1), h.handler);
        assertTrue(h.lines.isEmpty());
        assertTrue(h.arm());
        h.view.begin(h.client, new com.mahghuuuls.mountcollection.network.ExperienceProtocol(h.session, 0), h.handler);
        h.view.receive(h.client, h.message(2), h.handler);
        assertTrue(h.lines.isEmpty());
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(3), h.handler);
        assertEquals(2, h.lines.size());
        LookupWorld oldWorld = (LookupWorld) h.client.world;
        h.client.world = allocate(LookupWorld.class);
        h.trace.tick(h.client);
        h.client.world = oldWorld;
        h.trace.tick(h.client);
        assertEquals(2, h.lines.size());
        assertTrue(h.arm());
        h.client.player = null;
        h.trace.tick(h.client);
        assertFalse(h.trace.arm(h.handler, h.client.world, null, h.boat.getUniqueID()));
        assertFalse(new ClientRelocationTrace(() -> false, () -> 0L, h.lines::add)
                .arm(h.handler, oldWorld, new Object(), h.boat.getUniqueID()));
    }

    @Test void diagnosticsCannotBreakApplicationAndSupersedingPacketsEndCapture() throws Exception {
        TraceHarness h = new TraceHarness();
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(1), h.handler);
        h.view.receive(h.client, h.message(2), h.handler);
        assertTrue(h.lines.get(2).contains("SUPERSEDED"));
        h.trace.tick(h.client);
        assertEquals(3, h.lines.size());
        ClientRelocationTrace broken = new ClientRelocationTrace(() -> true, () -> 0L,
                line -> { throw new IllegalStateException("log failure"); });
        ClientRelocations view = new ClientRelocations(broken);
        view.begin(h.client, new com.mahghuuuls.mountcollection.network.ExperienceProtocol(h.session, 0), h.handler);
        assertTrue(broken.arm(h.handler, h.client.world, h.client.player, h.boat.getUniqueID()));
        h.boat.setPosition(339.5, 80, 328.5);
        view.receive(h.client, h.message(3), h.handler);
        assertEquals(318.5, h.boat.posX);
        assertEquals(320.5, h.client.player.posX);
    }

    @Test void rejectedAndLostEntitiesStopObservationWithoutReplay() throws Exception {
        TraceHarness h = new TraceHarness();
        h.view.receive(h.client, h.message(2), h.handler);
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(1), h.handler);
        assertTrue(h.lines.get(1).contains("REJECTED_VIEW"));
        h.trace.tick(h.client);
        assertEquals(2, h.lines.size());
        assertTrue(h.arm());
        ((LookupWorld) h.client.world).entity = null;
        h.view.receive(h.client, h.message(3), h.handler);
        assertTrue(h.lines.get(3).contains("REJECTED_ENTITY"));
        ((LookupWorld) h.client.world).entity = h.boat;
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(4), h.handler);
        h.boat.setUniqueId(UUID.randomUUID());
        h.trace.tick(h.client);
        assertTrue(h.lines.get(6).contains("ENTITY_LOST"));
        h.trace.tick(h.client);
        assertEquals(7, h.lines.size());
    }

    @Test void disconnectAndConnectionReplacementClearCaptureAndReleaseCannotArm() throws Exception {
        TraceHarness h = new TraceHarness();
        assertTrue(h.arm());
        h.view.receive(h.client, h.message(1), h.handler);
        assertTrue(h.view.disconnect(h.handler));
        h.trace.tick(h.client);
        assertEquals(2, h.lines.size());
        assertTrue(h.arm());
        field(net.minecraft.client.entity.EntityPlayerSP.class, "connection", h.client.player,
                allocate(net.minecraft.client.network.NetHandlerPlayClient.class));
        h.trace.tick(h.client);
        field(net.minecraft.client.entity.EntityPlayerSP.class, "connection", h.client.player, h.handler);
        h.view.receive(h.client, h.message(2), h.handler);
        assertEquals(2, h.lines.size());
        ClientRelocationTrace release = new ClientRelocationTrace(() -> false, () -> 0L, h.lines::add);
        assertThrows(net.minecraft.command.CommandException.class,
                () -> release.execute(null, null, new String[]{h.boat.getUniqueID().toString()}));
    }

    private static final class TraceHarness {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final long[] now = {0};
        final ClientRelocationTrace trace = new ClientRelocationTrace(() -> true, () -> now[0], lines::add);
        final ClientRelocations view = new ClientRelocations(trace);
        final net.minecraft.client.Minecraft client = allocate(net.minecraft.client.Minecraft.class);
        final net.minecraft.client.network.NetHandlerPlayClient handler = allocate(net.minecraft.client.network.NetHandlerPlayClient.class);
        final EntityBoat boat = new EntityBoat(new ClientWorld());
        final UUID session = UUID.randomUUID();
        TraceHarness() throws Exception {
            client.player = allocate(net.minecraft.client.entity.EntityPlayerSP.class);
            field(net.minecraft.client.entity.EntityPlayerSP.class, "connection", client.player, handler);
            field(Entity.class, "riddenByEntities", client.player, new java.util.ArrayList<Entity>());
            client.player.setUniqueId(UUID.randomUUID());
            client.player.setPosition(320.5, 80, 320.5);
            LookupWorld world = allocate(LookupWorld.class);
            world.entity = boat;
            client.world = world;
            boat.serverPosX = 1234;
            view.begin(client, new com.mahghuuuls.mountcollection.network.ExperienceProtocol(session, 0), handler);
        }
        boolean arm() { return trace.arm(handler, client.world, client.player, boat.getUniqueID()); }
        RelocationMessage message(long sequence) {
            EntityBoat server = new EntityBoat(null);
            server.setEntityId(boat.getEntityId()); server.setUniqueId(boat.getUniqueID());
            server.setPosition(318.5, 80, 318.5);
            return new RelocationMessage(session, sequence, server);
        }
    }
    @Test void nativeApplicationFailureClosesConnectionAndClearsBoundView() throws Exception {
        ClientRelocations view = new ClientRelocations();
        net.minecraft.client.Minecraft client = allocate(net.minecraft.client.Minecraft.class);
        net.minecraft.client.network.NetHandlerPlayClient handler = allocate(net.minecraft.client.network.NetHandlerPlayClient.class);
        ClosingConnection connection = new ClosingConnection();
        field(net.minecraft.client.network.NetHandlerPlayClient.class, "netManager", handler, connection);
        client.player = allocate(net.minecraft.client.entity.EntityPlayerSP.class);
        field(net.minecraft.client.entity.EntityPlayerSP.class, "connection", client.player, handler);
        LookupWorld world = allocate(LookupWorld.class);
        client.world = world;
        ThrowingBoat boat = new ThrowingBoat(new ClientWorld());
        world.entity = boat;
        UUID session = UUID.randomUUID();
        assertTrue(view.begin(client, new com.mahghuuuls.mountcollection.network.ExperienceProtocol(session, 0), handler));
        view.receive(client, new RelocationMessage(session, 1, boat), handler);
        assertTrue(connection.closed);
        assertFalse(view.admit(handler, handler, world, client.player, 0,
                new RelocationMessage(session, 2, boat)));
    }
    private static final class ThrowingBoat extends EntityBoat {
        ThrowingBoat(net.minecraft.world.World world) { super(world); }
        @Override public void setPositionAndRotationDirect(double x, double y, double z, float yaw,
                float pitch, int steps, boolean teleport) { throw new IllegalStateException("native application"); }
    }
    private static final class ClosingConnection extends net.minecraft.network.NetworkManager {
        boolean closed;
        ClosingConnection() { super(net.minecraft.network.EnumPacketDirection.CLIENTBOUND); }
        @Override public void closeChannel(net.minecraft.util.text.ITextComponent reason) { closed = true; }
    }
    @Test void receiverDropsMissingAndReusedIdsWithoutReplayingOrChangingPlayer() throws Exception {
        ClientRelocations view = new ClientRelocations();
        net.minecraft.client.Minecraft client = allocate(net.minecraft.client.Minecraft.class);
        net.minecraft.client.network.NetHandlerPlayClient connection = allocate(net.minecraft.client.network.NetHandlerPlayClient.class);
        client.player = allocate(net.minecraft.client.entity.EntityPlayerSP.class);
        field(net.minecraft.client.entity.EntityPlayerSP.class, "connection", client.player, connection);
        LookupWorld world = allocate(LookupWorld.class);
        client.world = world;
        UUID session = UUID.randomUUID();
        assertTrue(view.begin(client, new com.mahghuuuls.mountcollection.network.ExperienceProtocol(session, 0), connection));
        EntityBoat server = new EntityBoat(null), entity = new EntityBoat(new ClientWorld());
        server.setPosition(12, 80, 12);
        entity.setEntityId(server.getEntityId());
        RelocationMessage missing = new RelocationMessage(session, 1, server);
        view.receive(client, missing, connection);
        world.entity = entity;
        entity.setUniqueId(server.getUniqueID());
        view.receive(client, missing, connection);
        assertEquals(0, entity.posX); // Missing entity consumed the sequence; there is no delayed replay.
        entity.setUniqueId(UUID.randomUUID());
        view.receive(client, new RelocationMessage(session, 2, server), connection);
        assertEquals(0, entity.posX);
        entity.setUniqueId(server.getUniqueID());
        view.receive(client, new RelocationMessage(session, 3, server), connection);
        assertEquals(12, entity.posX);
        assertEquals(0, client.player.posX);
        assertFalse(client.player.isRiding());
        net.minecraft.client.entity.EntityOtherPlayerMP remote = allocate(net.minecraft.client.entity.EntityOtherPlayerMP.class);
        remote.setEntityId(server.getEntityId());
        remote.setUniqueId(server.getUniqueID());
        world.entity = remote;
        view.receive(client, new RelocationMessage(session, 4, server), connection);
        assertEquals(0, remote.posX);
    }
    @Test void nativeMovementAndTeleportHandlersRemainCompatibleAfterSnap() throws Exception {
        EntityBoat boat = new EntityBoat(new ClientWorld());
        boat.setPosition(10, 80, 10);
        EntityTracker.updateServerPosition(boat, 10, 80, 10);
        LookupWorld world = allocate(LookupWorld.class);
        world.entity = boat;
        net.minecraft.client.Minecraft client = allocate(net.minecraft.client.Minecraft.class);
        field(net.minecraft.client.Minecraft.class, "thread", client, Thread.currentThread());
        net.minecraft.client.network.NetHandlerPlayClient handler = allocate(net.minecraft.client.network.NetHandlerPlayClient.class);
        field(net.minecraft.client.network.NetHandlerPlayClient.class, "client", handler, client);
        field(net.minecraft.client.network.NetHandlerPlayClient.class, "world", handler, world);
        EntityBoat target = new EntityBoat(null);
        target.setEntityId(boat.getEntityId());
        target.setPosition(12, 80, 12);
        ClientRelocations.applyPose(boat, new RelocationMessage(UUID.randomUUID(), 1, target));
        handler.handleEntityMovement(new net.minecraft.network.play.server.SPacketEntity.S15PacketEntityRelMove(
                boat.getEntityId(), 8192, 0, 8192, true));
        java.lang.reflect.Method tick = EntityBoat.class.getDeclaredMethod("tickLerp");
        tick.setAccessible(true);
        for (int i = 0; i < 10; i++) { tick.invoke(boat); }
        assertEquals(12, boat.posX);
        assertEquals(EntityTracker.getPositionLong(12), boat.serverPosX);
        target.setPosition(40, 80, 40);
        ClientRelocations.applyPose(boat, new RelocationMessage(UUID.randomUUID(), 2, target));
        handler.handleEntityTeleport(new net.minecraft.network.play.server.SPacketEntityTeleport(target));
        handler.handleEntityMovement(new net.minecraft.network.play.server.SPacketEntity.S15PacketEntityRelMove(
                boat.getEntityId(), 4096, 0, 0, true));
        for (int i = 0; i < 10; i++) { tick.invoke(boat); }
        assertEquals(41, boat.posX);
        assertEquals(40, boat.posZ);
    }
    private static void field(Class<?> type, String name, Object target, Object value) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class LookupWorld extends net.minecraft.client.multiplayer.WorldClient {
        Entity entity;
        private LookupWorld() { super(null, null, 0, null, null); }
        @Override public Entity getEntityByID(int id) { return entity != null && entity.getEntityId() == id ? entity : null; }
        @Override public long getTotalWorldTime() { return 123L; }
    }
    @Test void delayedDisconnectCannotClearReplacementConnection() {
        ClientRelocations view = new ClientRelocations();
        Object oldConnection = new Object(), replacement = new Object();
        Object world = new Object(), player = new Object();
        UUID session = UUID.randomUUID();
        assertTrue(view.begin(replacement, replacement, world, player, 0,
                new com.mahghuuuls.mountcollection.network.ExperienceProtocol(session, 0)));
        assertFalse(view.disconnect(oldConnection));
        assertTrue(view.admit(replacement, replacement, world, player, 0,
                new RelocationMessage(session, 1, new EntityBoat(null))));
        assertTrue(view.disconnect(replacement));
        assertFalse(view.admit(replacement, replacement, world, player, 0,
                new RelocationMessage(session, 2, new EntityBoat(null))));
    }
    @Test void viewAdmissionRejectsReplacedConnectionsPlayersWorldsAndReplay() {
        ClientRelocations view=new ClientRelocations(); Object connection=new Object(),world=new Object(),player=new Object();
        UUID session=UUID.randomUUID();
        com.mahghuuuls.mountcollection.network.ExperienceProtocol challenge=
            new com.mahghuuuls.mountcollection.network.ExperienceProtocol(session,0);
        assertFalse(view.begin(connection,connection,world,player,1,challenge));
        assertTrue(view.begin(connection,connection,world,player,0,challenge));
        EntityBoat boat=new EntityBoat(null);
        RelocationMessage message=new RelocationMessage(session,1,boat);
        assertFalse(view.admit(connection,new Object(),world,player,0,message));
        assertFalse(view.admit(connection,connection,new Object(),player,0,message));
        assertFalse(view.admit(connection,connection,world,new Object(),0,message));
        assertFalse(view.admit(connection,connection,world,player,1,message));
        assertFalse(view.admit(connection,connection,world,player,0,new RelocationMessage(UUID.randomUUID(),1,boat)));
        assertTrue(view.admit(connection,connection,world,player,0,message));
        assertFalse(view.admit(connection,connection,world,player,0,message));
        UUID renewed=UUID.randomUUID();
        assertTrue(view.begin(connection,connection,world,new Object(),0,
            new com.mahghuuuls.mountcollection.network.ExperienceProtocol(renewed,0)));
        assertFalse(view.admit(connection,connection,world,player,0,message));
        view.clear(); assertFalse(view.admit(connection,connection,world,player,0,message));
    }
    @Test void boatSnapRetargetsNativeInterpolationAndLeavesEncodedBaselineUntouched() throws Exception {
        EntityBoat client=new EntityBoat(new ClientWorld()), server=new EntityBoat(null);
        client.setPosition(339.5,80,328.5); client.serverPosX=EntityTracker.getPositionLong(339.5);
        client.serverPosY=EntityTracker.getPositionLong(80); client.serverPosZ=EntityTracker.getPositionLong(328.5);
        long oldX=client.serverPosX,oldZ=client.serverPosZ;
        server.setPosition(318.5,80,318.5); server.onGround=true;
        client.setPositionAndRotationDirect(339.5,80,328.5,0,0,3,true);
        ClientRelocations.applyPose(client,new RelocationMessage(UUID.randomUUID(),1,server));
        assertEquals(oldX,client.serverPosX); assertEquals(oldZ,client.serverPosZ);
        java.lang.reflect.Method tick=EntityBoat.class.getDeclaredMethod("tickLerp"); tick.setAccessible(true);
        for(int i=0;i<10;i++) { tick.invoke(client); assertEquals(318.5,client.posX); assertEquals(318.5,client.posZ); }
        assertEquals(318.5,client.prevPosX); assertTrue(client.onGround);
        // Native relative decoding still uses its untouched old baseline and resolves the new target.
        long target=EntityTracker.getPositionLong(server.posX);
        client.serverPosX += target-oldX; client.serverPosZ += target-oldZ;
        client.setPositionAndRotationDirect(client.serverPosX/4096D,80,client.serverPosZ/4096D,0,0,3,false);
        tick.invoke(client); assertEquals(318.5,client.posX);
        client.serverPosX+=4096;
        client.setPositionAndRotationDirect(client.serverPosX/4096D,80,318.5,0,0,3,false);
        for(int i=0;i<10;i++) { tick.invoke(client); }
        assertEquals(319.5,client.posX);
    }
    @Test void repeatedSnapsAndLivingNativeTargetsDoNotRewriteTrackingOrPassengers() throws Exception {
        for(Entity client:new Entity[]{new EntityBoat(new ClientWorld()),new EntityHorse(new ClientWorld())}) {
            EntityBoat server=new EntityBoat(null); client.serverPosX=12345;
            for(int i=1;i<=2;i++) {
                server.setPosition(i,80,i);
                ClientRelocations.applyPose(client,new RelocationMessage(UUID.randomUUID(),i,server));
                assertEquals(i,client.posX); assertEquals(i,client.lastTickPosX);
                assertEquals(12345,client.serverPosX); assertFalse(client.isRiding()); assertFalse(client.isBeingRidden());
            }
        }
    }
    private static final class ClientWorld extends net.minecraft.world.World {
        ClientWorld() {
            super(new net.minecraft.world.storage.SaveHandlerMP(),
                new net.minecraft.world.storage.WorldInfo(new net.minecraft.world.WorldSettings(0,
                    net.minecraft.world.GameType.CREATIVE,false,false,net.minecraft.world.WorldType.DEFAULT),"relocation"),
                new net.minecraft.world.WorldProviderSurface(),new net.minecraft.profiler.Profiler(),true);
        }
        protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
        public net.minecraft.util.math.BlockPos getSpawnPoint() { return net.minecraft.util.math.BlockPos.ORIGIN; }
        protected boolean isChunkLoaded(int x,int z,boolean allowEmpty) { throw new AssertionError("unexpected chunk query"); }
    }
}
