package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.network.ExperienceProtocol;
import com.mahghuuuls.mountcollection.network.RelocationMessage;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.text.TextComponentString;

/** One current world view, processed in the same main-thread receive order as native packets. */
final class ClientRelocations {
    private Object connection;
    private Object world;
    private Object player;
    private UUID session;
    private long sequence;
    boolean begin(Minecraft client, ExperienceProtocol challenge, Object source) {
        return begin(source, client.getConnection(), client.world, client.player,
                client.player == null ? 0 : client.player.dimension, challenge);
    }
    boolean begin(Object source, Object currentConnection, Object currentWorld, Object currentPlayer,
            int dimension, ExperienceProtocol challenge) {
        clear();
        if (currentConnection != source || source == null || currentWorld == null || currentPlayer == null
                || dimension != challenge.getDimension()) { return false; }
        connection = source;
        world = currentWorld;
        player = currentPlayer;
        session = challenge.getSession();
        return true;
    }
    void clear() {
        connection = null;
        world = null;
        player = null;
        session = null;
        sequence = 0;
    }
    boolean disconnect(Object source) {
        if (source == null || source != connection) { return false; }
        clear();
        return true;
    }
    void receive(Minecraft client, RelocationMessage message, Object source) {
        if (!admit(source, client.getConnection(), client.world, client.player,
                client.player == null ? 0 : client.player.dimension, message)) { return; }
        Entity entity = client.world.getEntityByID(message.entityId());
        if (entity == null || !entity.getUniqueID().equals(message.entity())
                || entity instanceof net.minecraft.entity.player.EntityPlayer) { return; }
        try { applyPose(entity, message); }
        catch (RuntimeException | LinkageError failure) {
            client.getConnection().getNetworkManager().closeChannel(
                    new TextComponentString("Mount Collection could not synchronize a summoned mount."));
            clear();
        }
    }
    boolean admit(Object source, Object currentConnection, Object currentWorld, Object currentPlayer,
            int dimension, RelocationMessage message) {
        if (source != connection || currentConnection != connection || currentWorld != world || currentPlayer != player
                || session == null || !session.equals(message.session()) || message.sequence() <= sequence
                || dimension != message.dimension()) { return false; }
        sequence = message.sequence();
        return true;
    }
    static void applyPose(Entity e, RelocationMessage m) {
        // Retarget interpolation, then snap. Vanilla retains ownership of both encoded baselines.
        e.setPositionAndRotationDirect(m.x(), m.y(), m.z(), m.yaw(), m.pitch(), 0, true);
        e.setPositionAndRotation(m.x(), m.y(), m.z(), m.yaw(), m.pitch());
        e.prevPosX = e.lastTickPosX = m.x();
        e.prevPosY = e.lastTickPosY = m.y();
        e.prevPosZ = e.lastTickPosZ = m.z();
        e.prevRotationYaw = m.yaw();
        e.prevRotationPitch = m.pitch();
        e.motionX = m.vx();
        e.motionY = m.vy();
        e.motionZ = m.vz();
        e.onGround = m.grounded();
    }
}
