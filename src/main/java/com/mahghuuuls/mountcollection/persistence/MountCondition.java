package com.mahghuuuls.mountcollection.persistence;

public enum MountCondition {
    LIVING,
    OPERATION_IN_PROGRESS,
    PROVIDER_UNAVAILABLE,
    INTEGRITY_BLOCKED;

    boolean retainsAuthoritativePhysicalAssociation() {
        return this == LIVING || this == OPERATION_IN_PROGRESS || this == PROVIDER_UNAVAILABLE;
    }

    boolean isOperational() {
        return this == LIVING;
    }
}
