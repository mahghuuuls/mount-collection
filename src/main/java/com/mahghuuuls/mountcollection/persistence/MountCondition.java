package com.mahghuuuls.mountcollection.persistence;

public enum MountCondition {
    LIVING,
    PROVIDER_UNAVAILABLE,
    INTEGRITY_BLOCKED;

    boolean retainsAuthoritativePhysicalAssociation() {
        return this == LIVING || this == PROVIDER_UNAVAILABLE;
    }

    boolean isOperational() {
        return this == LIVING;
    }
}
