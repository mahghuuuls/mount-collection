package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.forge.RelocationOutput;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

/** Native recipient visibility and preflight, separate from wire-session ownership. */
final class ServerRelocations implements RelocationOutput {
    private final BiConsumer<EntityPlayerMP, Entity> delivery;

    ServerRelocations(BiConsumer<EntityPlayerMP, Entity> delivery) { this.delivery = delivery; }

    @Override public boolean ready(Entity entity, double x, double y, double z) {
        try {
            new RelocationMessage(new UUID(0, 0), 1, entity);
            return entity.world instanceof WorldServer && RelocationMessage.legalPosition(x, y, z);
        } catch (RuntimeException unavailable) { return false; }
    }
    @Override public boolean visibleTo(Entity entity, EntityPlayerMP player) {
        return player.world == entity.world && player.connection != null
                && player.connection.getNetworkManager().isChannelOpen()
                && ((WorldServer) entity.world).getEntityTracker().getTrackingPlayers(entity).contains(player);
    }
    @Override public void relocated(Entity entity) {
        WorldServer world = (WorldServer) entity.world;
        for (EntityPlayer raw : new ArrayList<>(world.getEntityTracker().getTrackingPlayers(entity))) {
            EntityPlayerMP recipient = (EntityPlayerMP) raw;
            if (recipient.world == world && recipient.connection != null
                    && recipient.connection.getNetworkManager().isChannelOpen()) {
                delivery.accept(recipient, entity);
            }
        }
    }
}
