package com.mahghuuuls.mountcollection.forge;

import java.util.function.Consumer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketKeepAlive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConnectionDeliveryTest {
    @Test void nativeCompletionFailureClosesCapturedConnectionNotReplacement() throws Exception {
        Player player = allocate(Player.class);
        player.setUniqueId(java.util.UUID.randomUUID());
        player.server = allocate(net.minecraft.server.dedicated.DedicatedServer.class);
        java.lang.reflect.Field thread = net.minecraft.server.MinecraftServer.class.getDeclaredField("serverThread");
        thread.setAccessible(true);
        thread.set(player.server, Thread.currentThread());
        Connection old = new Connection(), replacement = new Connection();
        new net.minecraft.network.NetHandlerPlayServer(player.server, old, player);
        ConnectionDelivery.send(player, () -> new SPacketKeepAlive(1));
        new net.minecraft.network.NetHandlerPlayServer(player.server, replacement, player);
        old.fail();
        assertTrue(old.closed);
        assertFalse(replacement.closed);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class Player extends net.minecraft.entity.player.EntityPlayerMP {
        net.minecraft.server.MinecraftServer server;
        private Player() { super(null, null, null, null); }
        @Override public net.minecraft.server.MinecraftServer getServer() { return server; }
    }
    private static final class Connection extends net.minecraft.network.NetworkManager {
        boolean closed;
        io.netty.util.concurrent.GenericFutureListener callback;
        Connection() { super(net.minecraft.network.EnumPacketDirection.SERVERBOUND); }
        @Override public boolean isChannelOpen() { return !closed; }
        @Override public void closeChannel(net.minecraft.util.text.ITextComponent reason) { closed = true; }
        @Override public void sendPacket(Packet<?> packet,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>> listener,
                io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>>... listeners) {
            callback = listener;
        }
        void fail() throws Exception {
            callback.operationComplete(io.netty.util.concurrent.ImmediateEventExecutor.INSTANCE.newFailedFuture(
                    new IllegalStateException("injected native write completion")));
        }
    }
    @Test void asyncFailureIsAttributedOnlyToCapturedTargetAndDoesNotThrowToWorld() {
        Target first=new Target(), other=new Target();
        ConnectionDelivery.send(first,()->new SPacketKeepAlive(1));
        ConnectionDelivery.send(other,()->new SPacketKeepAlive(2));
        RuntimeException fault=new RuntimeException("async write"); first.callback.accept(fault);
        assertSame(fault,first.failure); assertNull(other.failure); assertEquals(1,other.sent);
    }
    @Test void encodingFailureIsContainedAndDisconnectedTargetIsSkipped() {
        Target target=new Target();
        assertDoesNotThrow(()->ConnectionDelivery.send(target,()->{throw new IllegalArgumentException("encode");}));
        assertNotNull(target.failure); assertEquals(0,target.sent);
        target.open=false;
        ConnectionDelivery.send(target,()->{throw new AssertionError("must not encode");});
    }
    private static final class Target implements ConnectionDelivery.Target {
        boolean open=true; int sent; Throwable failure; Consumer<Throwable> callback;
        public boolean open() { return open; }
        public void send(Packet<?> packet,Consumer<Throwable> completed) { sent++; callback=completed; }
        public void failed(Throwable fault) { failure=fault; }
    }
}
