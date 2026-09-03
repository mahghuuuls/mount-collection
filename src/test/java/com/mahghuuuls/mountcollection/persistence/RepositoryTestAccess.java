package com.mahghuuuls.mountcollection.persistence;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Test-only access to package-owned acknowledgement injection. */
public final class RepositoryTestAccess {

    private RepositoryTestAccess() {}

    public static void setAcknowledgement(
            MountRepository repository, BooleanSupplier acknowledgement) {
        Objects.requireNonNull(repository, "repository").setAcknowledgedPersistence(
                ignored -> Objects.requireNonNull(
                        acknowledgement, "acknowledgement").getAsBoolean());
    }
}
