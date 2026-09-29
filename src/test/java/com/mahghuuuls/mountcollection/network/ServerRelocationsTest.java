package com.mahghuuuls.mountcollection.network;

import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.*;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ServerRelocationsTest {
    private static int nextId = 100000;
    @Test void onlyOpenSameWorldNativeTrackingRecipientsReceiveCurrentPose() throws Exception {
        TestWorld world = allocate(TestWorld.class);
        world.tracker = allocate(Tracker.class);
        world.tracker.players = new LinkedHashSet<>();
        EntityBoat boat = new EntityBoat(null);
        boat.world = world;
        boat.setPosition(3, 80, 4);
        EntityPlayerMP owner = player(world, true), observer = player(world, true);
        EntityPlayerMP closed = player(world, false), otherWorld = player(allocate(TestWorld.class), true);
        EntityPlayerMP untracked = player(world, true);
        world.tracker.players.addAll(Arrays.asList(owner, observer, closed, otherWorld));
        List<EntityPlayerMP> delivered = new ArrayList<>();
        ServerRelocations output = new ServerRelocations((recipient, entity) -> {
            assertSame(boat, entity);
            assertEquals(3, entity.posX);
            delivered.add(recipient);
        });
        assertTrue(output.ready(boat, 3, 80, 4));
        assertFalse(output.ready(boat, Double.NaN, 80, 4));
        assertTrue(output.visibleTo(boat, owner));
        assertFalse(output.visibleTo(boat, untracked));
        assertFalse(output.visibleTo(boat, closed));
        assertFalse(output.visibleTo(boat, otherWorld));
        output.relocated(boat);
        assertEquals(Arrays.asList(owner, observer), delivered);
        assertFalse(untracked.isRiding());
    }
    private static EntityPlayerMP player(WorldServer world, boolean open) throws Exception {
        EntityPlayerMP player = allocate(EntityPlayerMP.class);
        player.setEntityId(nextId++);
        player.setUniqueId(UUID.randomUUID());
        player.world = world;
        new NetHandlerPlayServer(null, new Connection(open), player);
        return player;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class Connection extends NetworkManager {
        private final boolean open;
        Connection(boolean open) { super(EnumPacketDirection.SERVERBOUND); this.open = open; }
        @Override public boolean isChannelOpen() { return open; }
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
