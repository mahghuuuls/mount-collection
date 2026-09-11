package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Typed decision returned synchronously at Forge's cancellable lethal-event boundary. */
public final class RecoveryDeathOutcome {

    public enum Status {
        PROTECTED,
        UNTRACKED,
        NORMAL_DEATH_RECOVERY_DISABLED,
        NORMAL_DEATH_PROVIDER_UNAVAILABLE,
        NORMAL_DEATH_CAPTURE_FAILED,
        NORMAL_DEATH_CLOCK_FAILURE,
        PERSISTENCE_FAILURE
    }

    private final Status status;
    private final UUID ownerId;
    private final MountId mountId;

    private RecoveryDeathOutcome(Status status, UUID ownerId, MountId mountId) {
        this.status = Objects.requireNonNull(status, "status");
        this.ownerId = ownerId;
        this.mountId = mountId;
    }

    public static RecoveryDeathOutcome untracked() {
        return new RecoveryDeathOutcome(Status.UNTRACKED, null, null);
    }

    public static RecoveryDeathOutcome tracked(Status status, UUID ownerId, MountId mountId) {
        if (status == Status.UNTRACKED) {
            throw new IllegalArgumentException("tracked outcome cannot be untracked");
        }
        return new RecoveryDeathOutcome(status,
                Objects.requireNonNull(ownerId, "ownerId"),
                Objects.requireNonNull(mountId, "mountId"));
    }

    public Status getStatus() { return status; }
    public Optional<UUID> getOwnerId() { return Optional.ofNullable(ownerId); }
    public Optional<MountId> getMountId() { return Optional.ofNullable(mountId); }
    public boolean shouldSuppressDeath() { return status == Status.PROTECTED; }
}
