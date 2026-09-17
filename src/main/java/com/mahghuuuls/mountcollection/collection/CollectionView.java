package com.mahghuuuls.mountcollection.collection;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable presentation projection, never a serialized repository record. */
public final class CollectionView {
    public enum State { LIVING, RECOVERING, READY, BUSY, PROVIDER_UNAVAILABLE, INTEGRITY_UNAVAILABLE }

    public static final class Entry {
        private final MountId id;
        private final String typeKey;
        private final int ordinal;
        private final long order;
        private final State state;
        private final long recoveryTicks;
        private final MountCharacteristics characteristics;
        private final String customName;
        private final boolean summoningDisabled;
        private final com.mahghuuuls.mountcollection.api.MountPreview preview;

        public Entry(MountId id, String typeKey, int ordinal, long order,
                State state, long recoveryTicks, MountCharacteristics characteristics) {
            this(id, typeKey, ordinal, order, state, recoveryTicks, characteristics, "");
        }
        public Entry(MountId id, String typeKey, int ordinal, long order,
                State state, long recoveryTicks, MountCharacteristics characteristics, String customName) {
            this(id, typeKey, ordinal, order, state, recoveryTicks, characteristics, customName, null);
        }
        public Entry(MountId id, String typeKey, int ordinal, long order,
                State state, long recoveryTicks, MountCharacteristics characteristics, String customName,
                com.mahghuuuls.mountcollection.api.MountPreview preview) {
            this(id, typeKey, ordinal, order, state, recoveryTicks, characteristics, customName, preview, false);
        }
        public Entry(MountId id, String typeKey, int ordinal, long order,
                State state, long recoveryTicks, MountCharacteristics characteristics, String customName,
                com.mahghuuuls.mountcollection.api.MountPreview preview, boolean summoningDisabled) {
            this.summoningDisabled = summoningDisabled;
            this.preview = preview;
            this.customName = Objects.requireNonNull(customName, "customName");
            if (!MountNaming.normalize(customName).equals(customName)) { throw new IllegalArgumentException("unnormalized name"); }
            this.id = Objects.requireNonNull(id, "id");
            this.typeKey = Objects.requireNonNull(typeKey, "typeKey");
            if (typeKey.isEmpty() || typeKey.length() > 128 || ordinal < 1 || order < 1
                    || recoveryTicks < 0) {
                throw new IllegalArgumentException("invalid collection presentation");
            }
            this.ordinal = ordinal;
            this.order = order;
            this.state = Objects.requireNonNull(state, "state");
            this.recoveryTicks = recoveryTicks;
            this.characteristics = Objects.requireNonNull(characteristics, "characteristics");
        }

        public MountId getId() { return id; }
        public boolean isSummoningDisabled() { return summoningDisabled; }
        public com.mahghuuuls.mountcollection.api.MountPreview getPreview() { return preview; }
        public String getCustomName() { return customName; }
        public String getTypeKey() { return typeKey; }
        public int getOrdinal() { return ordinal; }
        public long getOrder() { return order; }
        public State getState() { return state; }
        public long getRecoveryTicks() { return recoveryTicks; }
        public MountCharacteristics getCharacteristics() { return characteristics; }
    }

    private final long selectionRevision;
    private final MountId selected;
    private final List<Entry> entries;

    public CollectionView(long selectionRevision, MountId selected, List<Entry> entries) {
        if (selectionRevision < 0) { throw new IllegalArgumentException("negative revision"); }
        this.selectionRevision = selectionRevision;
        this.selected = selected;
        List<Entry> copy = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        for (Entry entry : copy) { Objects.requireNonNull(entry, "entry"); }
        this.entries = Collections.unmodifiableList(copy);
    }

    public long getSelectionRevision() { return selectionRevision; }
    public MountId getSelected() { return selected; }
    public List<Entry> getEntries() { return entries; }
}
