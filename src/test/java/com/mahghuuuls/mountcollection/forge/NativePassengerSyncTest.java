package com.mahghuuuls.mountcollection.forge;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.*;
import net.minecraft.network.play.server.*;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativePassengerSyncTest {
    @Test void nativeConnectionReceivesPassengerLinksBeforeControlledPoseAndObserverOnlyLinks() throws Exception {
        TestWorld world = allocate(TestWorld.class);
        world.tracker = allocate(Tracker.class);
        EntityPlayerMP rider = allocate(EntityPlayerMP.class), observer = allocate(EntityPlayerMP.class);
        rider.setEntityId(50001); observer.setEntityId(50002);
        rider.world = world; observer.world = world;
        Connection ownerConnection = new Connection(), observerConnection = new Connection();
        new NetHandlerPlayServer(null, ownerConnection, rider);
        new NetHandlerPlayServer(null, observerConnection, observer);
        EntityBoat boat = new EntityBoat(null);
        boat.setPosition(12, 80, 12);
        Field riding = Entity.class.getDeclaredField("ridingEntity");
        riding.setAccessible(true); riding.set(rider, boat);
        Field passengers = Entity.class.getDeclaredField("riddenByEntities");
        passengers.setAccessible(true);
        ((List<Entity>) passengers.get(boat)).add(rider);
        world.tracker.players = new HashSet<>(Arrays.asList(rider, observer));
        NativePassengerSync.synchronize(world, boat, rider);
        assertEquals(2, ownerConnection.packets.size());
        assertTrue(ownerConnection.packets.get(0) instanceof SPacketSetPassengers);
        assertTrue(ownerConnection.packets.get(1) instanceof SPacketMoveVehicle);
        assertEquals(1, observerConnection.packets.size());
        assertTrue(observerConnection.packets.get(0) instanceof SPacketSetPassengers);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class Connection extends NetworkManager {
        final List<Packet<?>> packets = new ArrayList<>();
        Connection() { super(EnumPacketDirection.SERVERBOUND); }
        @Override public boolean isChannelOpen() { return true; }
        @Override public void sendPacket(Packet<?> packet,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>> listener,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>>... listeners) {
            packets.add(packet);
        }
    }
    private static final class TestWorld extends WorldServer {
        Tracker tracker;
        private TestWorld() { super(null, null, null, 0, null); }
        @Override public EntityTracker getEntityTracker() { return tracker; }
    }
    private static final class Tracker extends EntityTracker {
        Set<EntityPlayer> players;
        private Tracker() { super(null); }
        @Override public Set<? extends EntityPlayer> getTrackingPlayers(Entity entity) { return players; }
    }
}
