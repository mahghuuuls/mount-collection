package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Owns verification and rollback of this call's provisional native passenger link. */
final class NativeBoarding {
    interface Environment {
        boolean ready();
        boolean seatedSafely();
        boolean returnSafely();
        void synchronize();
    }

    private NativeBoarding() { }

    static boolean board(Entity rider, Entity mount, Environment environment) {
        if (rider.isRiding() || mount.isBeingRidden()) { return false; }
        Links links;
        try {
            links = new Links(rider, mount);
            if (!environment.ready()) { return false; }
        } catch (FatalTransferSafetyException fatal) { throw fatal; }
        catch (RuntimeException | LinkageError unavailable) { return false; }
        boolean accepted = false;
        final double originalX = rider.posX, originalY = rider.posY, originalZ = rider.posZ;
        try {
            if (!rider.startRiding(mount, false)) { return false; }
            if (!links.attached()) { return false; }
            mount.updatePassenger(rider);
            accepted = environment.seatedSafely() && links.attached();
            return accepted;
        } catch (FatalTransferSafetyException fatal) { throw fatal; }
        catch (RuntimeException | LinkageError rejected) { return false; }
        finally {
            if (!accepted) {
                try {
                    boolean changed = !links.detached() || rider.posX != originalX
                            || rider.posY != originalY || rider.posZ != originalZ;
                    if (rider.getRidingEntity() == mount) {
                        try { rider.dismountRidingEntity(); }
                        catch (FatalTransferSafetyException fatal) { throw fatal; }
                        catch (RuntimeException | LinkageError ignored) {
                            // Native cleanup is fallible and cancelable; verify and undo our pair below.
                        }
                    }
                    links.removePair();
                    if (!links.detached()) { throw new IllegalStateException("passenger rollback incomplete"); }
                    // A hook may have moved the rider to another vehicle. Never detach or reposition it.
                    if (changed && !rider.isRiding() && !environment.returnSafely()) {
                        throw new IllegalStateException("no safe unmounted return position");
                    }
                    if (changed) { environment.synchronize(); }
                } catch (FatalTransferSafetyException fatal) { throw fatal; }
                catch (RuntimeException | LinkageError failure) {
                    throw FatalTransferSafetyException.boardingFailure(rider.getUniqueID(), failure);
                }
            }
        }
    }

    /** No mutable passenger list escapes this boundary; getters alone expose only copies. */
    private static final class Links {
        private final Entity rider;
        private final Entity mount;
        private final Field vehicle;
        private final Field passengers;

        Links(Entity rider, Entity mount) {
            this.rider = rider;
            this.mount = mount;
            vehicle = ReflectionHelper.findField(Entity.class, new String[]{"ridingEntity", "field_184239_as"});
            passengers = ReflectionHelper.findField(Entity.class, new String[]{"riddenByEntities", "field_184244_h"});
            // Establish readable access and expected representation before any attachment.
            try {
                if (vehicle.get(rider) != null || !(passengers.get(mount) instanceof List)) {
                    throw new IllegalStateException("unavailable passenger access");
                }
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }

        boolean attached() { return rider.getRidingEntity() == mount && mount.isPassenger(rider); }
        boolean detached() { return rider.getRidingEntity() != mount && !mount.isPassenger(rider); }

        void removePair() {
            try {
                if (vehicle.get(rider) == mount) { vehicle.set(rider, null); }
                List<?> occupants = (List<?>) passengers.get(mount);
                for (int i = occupants.size() - 1; i >= 0; i--) {
                    if (occupants.get(i) == rider) { occupants.remove(i); }
                }
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
    }
}
