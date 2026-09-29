package com.mahghuuuls.mountcollection.forge;

import java.util.function.Supplier;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.util.text.TextComponentTranslation;

/** One recipient's native connection owns both synchronous and asynchronous delivery failure. */
public final class ConnectionDelivery {
    private ConnectionDelivery() { }
    interface Target {
        boolean open();
        void send(Packet<?> packet, java.util.function.Consumer<Throwable> completed);
        void failed(Throwable failure);
    }
    static void send(Target target, Supplier<Packet<?>> packet) {
        if (!target.open()) { return; }
        try {
            target.send(packet.get(), failure -> {
                if (failure != null) { target.failed(failure); }
            });
        } catch (RuntimeException | LinkageError failure) { target.failed(failure); }
    }
    public static void send(EntityPlayerMP player, Supplier<Packet<?>> packet) {
        if (player.connection == null) { return; }
        final NetworkManager connection = player.connection.getNetworkManager();
        send(new Target() {
            public boolean open() { return connection.isChannelOpen(); }
            public void send(Packet<?> value, java.util.function.Consumer<Throwable> completed) {
                connection.sendPacket(value, future -> completed.accept(future.isSuccess() ? null : future.cause()));
            }
            public void failed(Throwable failure) {
                player.getServer().addScheduledTask(() -> {
                    if (!connection.isChannelOpen()) { return; }
                    org.apache.logging.log4j.LogManager.getLogger("mountcollection").error(
                            "Mount synchronization failed for {}", player.getUniqueID(), failure);
                    // Close the captured connection, never a replacement player's new connection.
                    connection.closeChannel(new TextComponentTranslation("mountcollection.sync_failed"));
                });
            }
        }, packet);
    }
}
