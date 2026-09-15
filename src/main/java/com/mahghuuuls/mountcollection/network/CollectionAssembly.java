package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One client-side assembly, with no allocations sized from untrusted totals. */
public final class CollectionAssembly {
    private CollectionHeader header;
    private final Map<Integer, List<CollectionView.Entry>> pages = new HashMap<>();
    private final Set<MountId> ids = new HashSet<>();
    private int received;

    public void begin(CollectionHeader header) {
        clear();
        this.header = java.util.Objects.requireNonNull(header, "header");
    }
    public void clear() { header = null; pages.clear(); ids.clear(); received = 0; }
    public int getReceived() { return received; }
    public int getTotal() { return header == null ? 0 : header.getTotal(); }
    public boolean isComplete() { return header != null && pages.size() == header.getPages() && received == header.getTotal(); }

    /** A superseded page is ignored; malformed pages for the active snapshot invalidate it. */
    public boolean accept(CollectionPage page) {
        if (header == null || !header.getSnapshot().equals(page.getSnapshot())) { return false; }
        try {
            if (page.getRevision() != header.getRevision() || page.getIndex() >= header.getPages()
                    || pages.containsKey(page.getIndex())
                    || page.getEntries().size() > header.getTotal() - received) {
                throw new IllegalArgumentException("inconsistent snapshot page");
            }
            for (CollectionView.Entry entry : page.getEntries()) {
                if (!ids.add(entry.getId())) { throw new IllegalArgumentException("duplicate mount identity"); }
            }
            pages.put(page.getIndex(), page.getEntries());
            received += page.getEntries().size();
            if (pages.size() == header.getPages() && received != header.getTotal()) {
                throw new IllegalArgumentException("snapshot total mismatch");
            }
            if (isComplete() && header.getSelected() != null && !ids.contains(header.getSelected())) {
                throw new IllegalArgumentException("selected mount missing from snapshot");
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            clear();
            throw invalid;
        }
    }
    public CollectionView finish() {
        if (!isComplete()) { throw new IllegalStateException("snapshot incomplete"); }
        List<CollectionView.Entry> result = new ArrayList<>();
        for (int i = 0; i < header.getPages(); i++) { result.addAll(pages.get(i)); }
        CollectionView view = new CollectionView(header.getRevision(), header.getSelected(), result);
        clear();
        return view;
    }
}
