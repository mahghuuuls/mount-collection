package com.mahghuuuls.mountcollection.forge;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketMoveVehicle;
import net.minecraft.network.play.server.SPacketSetPassengers;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;

/** Native link/pose ordering, with transport failures isolated from world-safety containment. */
final class NativePassengerSync {
    private NativePassengerSync() { }
    static void synchronize(WorldServer world, Entity mount, EntityPlayerMP rider) {
        SPacketSetPassengers links = new SPacketSetPassengers(mount);
        Set<EntityPlayer> recipients = new HashSet<>(world.getEntityTracker().getTrackingPlayers(mount));
        recipients.add(rider);
        for (EntityPlayer raw : recipients) {
            EntityPlayerMP recipient = (EntityPlayerMP) raw;
            if (recipient.connection == null || !recipient.connection.getNetworkManager().isChannelOpen()) { continue; }
            try {
                if (recipient.world == world) { ConnectionDelivery.send(recipient, () -> links); }
                if (recipient == rider) {
                    if (rider.getRidingEntity() == mount && mount.isPassenger(rider)) {
                        ConnectionDelivery.send(recipient, () -> new SPacketMoveVehicle(mount));
                    } else if (!rider.isRiding() && rider.isEntityAlive()) {
                        recipient.connection.setPlayerLocation(rider.posX, rider.posY, rider.posZ,
                                rider.rotationYaw, rider.rotationPitch);
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                org.apache.logging.log4j.LogManager.getLogger("mountcollection").error(
                        "Mount passenger synchronization failed for {}", recipient.getUniqueID(), failure);
                try { recipient.connection.disconnect(new TextComponentTranslation("mountcollection.sync_failed")); }
                catch (RuntimeException | LinkageError closing) {
                    recipient.connection.getNetworkManager().closeChannel(new TextComponentTranslation("mountcollection.sync_failed"));
                }
            }
        }
    }
}
