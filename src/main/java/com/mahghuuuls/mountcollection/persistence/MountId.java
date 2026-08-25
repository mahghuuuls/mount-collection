package com.mahghuuuls.mountcollection.persistence;

import java.util.Objects;
import java.util.UUID;

public final class MountId implements Comparable<MountId> {

    private final UUID value;

    private MountId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static MountId create() {
        return new MountId(UUID.randomUUID());
    }

    public static MountId fromUuid(UUID value) {
        return new MountId(value);
    }

    public static MountId parse(String value) {
        return fromUuid(UUID.fromString(value));
    }

    public UUID asUuid() {
        return value;
    }

    @Override
    public int compareTo(MountId other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MountId && value.equals(((MountId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
