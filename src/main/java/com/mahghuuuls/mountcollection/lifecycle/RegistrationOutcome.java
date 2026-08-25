package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Optional;

public final class RegistrationOutcome {

    public enum Status {
        SUCCESS("mountcollection.message.registered"),
        NO_RIDDEN_ENTITY("mountcollection.message.recall_not_available"),
        UNSUPPORTED("mountcollection.message.unsupported"),
        NOT_TAMED("mountcollection.message.not_tamed"),
        NOT_OWNED("mountcollection.message.not_owned"),
        DISALLOWED("mountcollection.message.disallowed"),
        ALREADY_REGISTERED("mountcollection.message.already_registered"),
        OWNED_BY_OTHER("mountcollection.message.owned_by_other"),
        INTEGRITY_CONFLICT("mountcollection.message.integrity_conflict"),
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

    private RegistrationOutcome(Status status, MountId mountId) {
        this.status = status;
        this.mountId = mountId;
    }

    public static RegistrationOutcome success(MountId mountId) {
        return new RegistrationOutcome(Status.SUCCESS, mountId);
    }

    public static RegistrationOutcome failure(Status status) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("success requires a Mount ID");
        }
        return new RegistrationOutcome(status, null);
    }

    public Status getStatus() {
        return status;
    }

    public Optional<MountId> getMountId() {
        return Optional.ofNullable(mountId);
    }
}
