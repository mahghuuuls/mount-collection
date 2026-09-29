package com.mahghuuuls.mountcollection.diagnostics;

import com.mahghuuuls.mountcollection.network.RelocationMessage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;

/** Finite, development-only native snapshots; diagnostic failures never affect delivery. */
public final class RelocationTraceLog {
    private final BooleanSupplier enabled;
    private final Consumer<String> output;
    private int remaining = 64;

    public RelocationTraceLog(BooleanSupplier enabled, Consumer<String> output) {
        this.enabled = enabled;
        this.output = output;
    }

    public void record(Supplier<String> record) {
        try {
            if (remaining <= 0 || !enabled.getAsBoolean()) { return; }
            --remaining;
            output.accept("[MountCollection][RELOCATION_TRACE] " + record.get());
        } catch (RuntimeException | LinkageError unavailable) {
            remaining = 0;
        }
    }

    public static String packet(RelocationMessage message) {
        return "session=" + message.session() + " sequence=" + message.sequence()
                + " dimension=" + message.dimension() + " entityId=" + message.entityId()
                + " entity=" + message.entity() + " target=" + message.x() + "," + message.y() + "," + message.z();
    }

    public static String pose(Entity entity) {
        if (entity == null) { return "absent"; }
        StringBuilder passengers = new StringBuilder();
        int count = 0;
        for (Entity passenger : entity.getPassengers()) {
            if (count++ == 4) { passengers.append("more"); break; }
            passengers.append(passenger.getUniqueID()).append(',');
        }
        return "uuid=" + entity.getUniqueID() + " pos=" + entity.posX + "," + entity.posY + "," + entity.posZ
                + " prev=" + entity.prevPosX + "," + entity.prevPosY + "," + entity.prevPosZ
                + " render=" + entity.lastTickPosX + "," + entity.lastTickPosY + "," + entity.lastTickPosZ
                + " motion=" + entity.motionX + "," + entity.motionY + "," + entity.motionZ
                + " encoded=" + entity.serverPosX + "," + entity.serverPosY + "," + entity.serverPosZ
                + " grounded=" + entity.onGround + " box=" + entity.getEntityBoundingBox()
                + " vehicle=" + (entity.getRidingEntity() == null ? "none" : entity.getRidingEntity().getUniqueID())
                + " passengers=" + passengers;
    }
}
