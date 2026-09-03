package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Optional;

public final class ContextualOutcome {

    public enum Status {
        REGISTERED("mountcollection.message.registered"),
        RECALLED("mountcollection.message.recalled"),
        UNSUPPORTED("mountcollection.message.unsupported"),
        NOT_TAMED("mountcollection.message.not_tamed"),
        NOT_OWNED("mountcollection.message.not_owned"),
        REGISTRATION_DISALLOWED("mountcollection.message.registration_disallowed"),
        ALREADY_REGISTERED("mountcollection.message.already_registered"),
        OWNED_BY_OTHER("mountcollection.message.owned_by_other"),
        NO_SELECTION("mountcollection.message.no_selection"),
        MOUNT_MISSING("mountcollection.message.mount_missing"),
        PROVIDER_UNAVAILABLE("mountcollection.message.provider_unavailable"),
        PASSENGER_PRESENT("mountcollection.message.passenger_present"),
        SUMMON_DISALLOWED("mountcollection.message.summon_disallowed"),
        DIMENSION_DISALLOWED("mountcollection.message.dimension_disallowed"),
        INHIBITED("mountcollection.message.inhibited"),
        COOLDOWN("mountcollection.message.cooldown"),
        NO_SAFE_DESTINATION("mountcollection.message.no_safe_destination"),
        OPERATION_IN_PROGRESS("mountcollection.message.operation_in_progress"),
        INTEGRITY_CONFLICT("mountcollection.message.integrity_conflict"),
        TEMPORARILY_UNAVAILABLE("mountcollection.message.temporarily_unavailable"),
        PERSISTENCE_FAILURE("mountcollection.message.persistence_failure"),
        PROVIDER_FAILURE("mountcollection.message.provider_failure"),
        READ_ONLY("mountcollection.message.read_only"),
        INTERNAL_FAILURE("mountcollection.message.internal_failure");

        private final String translationKey;

        Status(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    private final Status status;
    private final MountId mountId;

    private ContextualOutcome(Status status, MountId mountId) {
        this.status = status;
        this.mountId = mountId;
    }

    public static ContextualOutcome success(Status status, MountId mountId) {
        if (status != Status.REGISTERED && status != Status.RECALLED) {
            throw new IllegalArgumentException("status is not successful");
        }
        return new ContextualOutcome(status, mountId);
    }

    public static ContextualOutcome failure(Status status) {
        if (status == Status.REGISTERED || status == Status.RECALLED) {
            throw new IllegalArgumentException("success requires a Mount ID");
        }
        return new ContextualOutcome(status, null);
    }

    public Status getStatus() {
        return status;
    }

    public Optional<MountId> getMountId() {
        return Optional.ofNullable(mountId);
    }
}
