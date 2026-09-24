package com.mahghuuuls.mountcollection.lifecycle;

import net.minecraft.entity.player.EntityPlayerMP;

/** Attempt-local view of the original request; never written to the restoration journal. */
public final class RecoveryAdmission {
    public enum Mode { AUTOMATIC, OPT_OUT, EXPIRED, UNAVAILABLE }
    private final Mode mode;
    private final EntityPlayerMP rider;
    private final java.util.function.Supplier<ArrivalDisposition> disposition;
    private final Runnable fallback;
    private RecoveryAdmission(Mode mode, EntityPlayerMP rider) {
        this(mode, rider, () -> ArrivalDisposition.COMBINED, () -> { });
    }
    private RecoveryAdmission(Mode mode, EntityPlayerMP rider,
            java.util.function.Supplier<ArrivalDisposition> disposition, Runnable fallback) {
        this.mode = mode;
        this.rider = rider;
        this.disposition = disposition;
        this.fallback = fallback;
    }
    public static RecoveryAdmission automatic(EntityPlayerMP rider) {
        return rider == null ? unavailable() : new RecoveryAdmission(Mode.AUTOMATIC, rider);
    }
    public static RecoveryAdmission optOut() { return new RecoveryAdmission(Mode.OPT_OUT, null); }
    public static RecoveryAdmission expired() { return new RecoveryAdmission(Mode.EXPIRED, null); }
    public static RecoveryAdmission unavailable() { return new RecoveryAdmission(Mode.UNAVAILABLE, null); }
    public Mode getMode() { return mode; }
    public EntityPlayerMP getRider() { return rider; }
    RecoveryAdmission withDisposition(java.util.function.Supplier<ArrivalDisposition> current, Runnable fallback) {
        return new RecoveryAdmission(mode, rider, current, fallback);
    }
    public boolean allowsBoarding() { return disposition.get() != ArrivalDisposition.UNMOUNTED_FALLBACK; }
    public void useUnmountedFallback() { fallback.run(); }
}
