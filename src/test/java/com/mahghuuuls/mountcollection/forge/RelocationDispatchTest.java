package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.network.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.*;
import net.minecraft.network.play.server.SPacketKeepAlive;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production registration, event/session renewal and commit/boarding dispatch; transport is captured. */
final class RelocationDispatchTest {
    @Test void renewedChallengePrecedesRelocationAndPassengerPublication() throws Exception {
        Fixture f = new Fixture();
        f.network.playerLoggedIn(f.game.player);
        UUID first = f.channel.challenge.getSession();
        f.ack(first); // Intentionally queued, then made stale by same-dimension respawn.
        f.bootstrap.onPlayerRespawn(new net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent(f.game.player, false));
        UUID second = f.channel.challenge.getSession();
        assertNotEquals(first, second);
        f.runTasks();
        assertFalse(f.ready());
        f.ack(second); f.runTasks(); assertTrue(f.ready());
        f.game.player.dimension = -1;
        f.bootstrap.onPlayerChangedDimension(new net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent(f.game.player, 0, -1));
        assertEquals(-1, f.channel.challenge.getDimension());
        assertNotEquals(second, f.channel.challenge.getSession());
        f.game.player.dimension = 0;
        f.bootstrap.onPlayerChangedDimension(new net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent(f.game.player, -1, 0));
        assertFalse(f.ready());
        Object session = f.sessions().get(f.game.player.getUniqueID());
        set(session.getClass(), "relocationSequence", session, Long.MAX_VALUE);
        UUID beforeOverflow = f.channel.challenge.getSession();
        f.events.clear();
        f.game.mount.setPosition(8.5, 64, 8.5);
        assertTrue(f.game.gateway.commit(f.game.player, f.game.source(), f.game.plan().get(), f.game.provider));
        assertNotEquals(beforeOverflow, f.channel.challenge.getSession());
        assertEquals(1, f.channel.relocation.sequence());
        assertEquals(f.channel.challenge.getSession(), f.channel.relocation.session());
        assertTrue(f.game.gateway.boardArrived(f.game.player, f.game.record, f.game.provider));
        assertEquals(Arrays.asList("challenge", "relocation", "SPacketSetPassengers", "SPacketMoveVehicle"), f.events);
        assertEquals(1, f.trace.size());
        assertTrue(f.trace.get(0).contains("phase=SEND_ATTEMPT"));
        assertTrue(f.trace.get(0).contains("session=" + f.channel.relocation.session()));
        assertTrue(f.trace.get(0).contains("entity=" + f.game.mount.getUniqueID()));
    }
    private static final class Fixture {
        final BoardingReadinessGatewayTest.Fixture game = new BoardingReadinessGatewayTest.Fixture();
        final List<String> events = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        final MountNetwork network = allocate(MountNetwork.class);
        final Channel channel = allocate(Channel.class);
        final CommonBootstrap bootstrap = allocate(CommonBootstrap.class);
        Fixture() throws Exception {
            channel.events = events;
            set(MountNetwork.class, "channel", network, channel);
            set(MountNetwork.class, "contextualSessions", network, new HashMap<>());
            set(MountNetwork.class, "relocationTrace", network,
                    new com.mahghuuuls.mountcollection.diagnostics.RelocationTraceLog(() -> true, trace::add));
            set(net.minecraft.world.World.class, "worldInfo", game.world,
                    new net.minecraft.world.storage.WorldInfo(new net.minecraft.nbt.NBTTagCompound()));
            set(net.minecraft.entity.Entity.class, "riddenByEntities", game.player, new ArrayList<>());
            MountCollectionServices services = new MountCollectionServices(
                    new com.mahghuuuls.mountcollection.provider.ProviderRegistry(), null,
                    new com.mahghuuuls.mountcollection.diagnostics.MountCollectionDiagnostics(
                            org.apache.logging.log4j.LogManager.getLogger("dispatch-test")), null);
            network.preInitialize(services);
            game.gateway.setRelocationOutput((RelocationOutput) get(MountCollectionServices.class, "relocationOutput", services));
            set(CommonBootstrap.class, "network", bootstrap, network);
            new NetHandlerPlayServer(null, new Connection(events), game.player);
            game.player.attach = true;
            game.world.tracker.players = Collections.singleton(game.player);
            game.world.tasks = new ArrayList<>();
            net.minecraft.server.dedicated.DedicatedServer server = allocate(net.minecraft.server.dedicated.DedicatedServer.class);
            Players players = allocate(Players.class); players.player = game.player;
            set(net.minecraft.server.MinecraftServer.class, "playerList", server, players);
            game.world.server = server;
        }
        Map<?, ?> sessions() throws Exception { return (Map<?, ?>) get(MountNetwork.class, "contextualSessions", network); }
        boolean ready() throws Exception {
            Method method = MountNetwork.class.getDeclaredMethod("protocolReady", EntityPlayerMP.class);
            method.setAccessible(true); return (Boolean) method.invoke(network, game.player);
        }
        void ack(UUID session) throws Exception {
            MessageContext context = allocate(MessageContext.class);
            set(MessageContext.class, "netHandler", context, game.player.connection);
            channel.ack.onMessage(new ExperienceProtocolAck(session, game.player.dimension), context);
        }
        void runTasks() { List<Runnable> queued = new ArrayList<>(game.world.tasks); game.world.tasks.clear(); queued.forEach(Runnable::run); }
    }
    private static final class Players extends net.minecraft.server.dedicated.DedicatedPlayerList {
        EntityPlayerMP player;
        private Players() { super(null); }
        @Override public EntityPlayerMP getPlayerByUUID(UUID id) { return player.getUniqueID().equals(id) ? player : null; }
    }
    private static final class Channel extends SimpleNetworkWrapper {
        List<String> events; ExperienceProtocol challenge; RelocationMessage relocation; IMessageHandler ack;
        private Channel() { super("unused"); }
        @Override public <REQ extends IMessage, REPLY extends IMessage> void registerMessage(
                IMessageHandler<? super REQ, ? extends REPLY> handler, Class<REQ> type, int id, Side side) {
            if (type == ExperienceProtocolAck.class) { ack = handler; }
        }
        @Override public void sendTo(IMessage message, EntityPlayerMP player) {
            assertTrue(message instanceof ExperienceProtocol);
            challenge = (ExperienceProtocol) message; events.add("challenge");
        }
        @Override public Packet<?> getPacketFrom(IMessage message) {
            assertTrue(message instanceof RelocationMessage);
            relocation = (RelocationMessage) message;
            return new SPacketKeepAlive(0);
        }
    }
    private static final class Connection extends NetworkManager {
        final List<String> events;
        Connection(List<String> events) { super(EnumPacketDirection.SERVERBOUND); this.events = events; }
        @Override public boolean isChannelOpen() { return true; }
        @Override public void sendPacket(Packet<?> packet,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>> listener,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>>... rest) {
            events.add(packet instanceof SPacketKeepAlive ? "relocation" : packet.getClass().getSimpleName());
        }
    }
    private static Object get(Class<?> type, String name, Object target) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Class<?> type, String name, Object target, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
