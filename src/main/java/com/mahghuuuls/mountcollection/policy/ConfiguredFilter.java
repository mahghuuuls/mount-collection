package com.mahghuuuls.mountcollection.policy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class ConfiguredFilter<T> {

    private final FilterMode mode;
    private final Set<T> entries;

    public ConfiguredFilter(FilterMode mode, Set<T> entries) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.entries = Collections.unmodifiableSet(new LinkedHashSet<>(entries));
    }

    public FilterMode getMode() {
        return mode;
    }

    public Set<T> getEntries() {
        return entries;
    }

    public boolean allows(T value) {
        boolean listed = entries.contains(value);
        return mode == FilterMode.BLACKLIST ? !listed : listed;
    }
}
