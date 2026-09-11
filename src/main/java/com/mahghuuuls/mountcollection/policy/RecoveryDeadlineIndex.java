package com.mahghuuuls.mountcollection.policy;

import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/** Rebuildable derived index for Recovery deadlines; it owns no durable state. */
public final class RecoveryDeadlineIndex {

    private final PriorityQueue<Entry> deadlines = new PriorityQueue<>();
    private final Map<MountId, Long> current = new HashMap<>();

    public synchronized void rebuild(Iterable<MountRecord> records) {
        Objects.requireNonNull(records, "records");
        deadlines.clear();
        current.clear();
        for (MountRecord record : records) {
            schedule(record);
        }
    }

    public synchronized void schedule(MountRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.getCondition() != MountCondition.RECOVERING
                || record.getRecoveryState() == null) {
            current.remove(record.getMountId());
            return;
        }
        long deadline = record.getRecoveryState().getDeadline();
        current.put(record.getMountId(), deadline);
        deadlines.add(new Entry(record.getMountId(), deadline));
    }

    public synchronized Optional<MountId> peekDue(long activeTick) {
        if (activeTick < 0L) {
            return Optional.empty();
        }
        discardStale();
        Entry first = deadlines.peek();
        return first != null && first.deadline <= activeTick
                ? Optional.of(first.mountId)
                : Optional.empty();
    }

    public synchronized void remove(MountId mountId) {
        current.remove(Objects.requireNonNull(mountId, "mountId"));
    }

    public synchronized int size() {
        return current.size();
    }

    private void discardStale() {
        while (!deadlines.isEmpty()) {
            Entry first = deadlines.peek();
            Long expected = current.get(first.mountId);
            if (expected != null && expected.longValue() == first.deadline) {
                return;
            }
            deadlines.remove();
        }
    }

    private static final class Entry implements Comparable<Entry> {
        private final MountId mountId;
        private final long deadline;

        private Entry(MountId mountId, long deadline) {
            this.mountId = mountId;
            this.deadline = deadline;
        }

        @Override
        public int compareTo(Entry other) {
            int byDeadline = Long.compare(deadline, other.deadline);
            return byDeadline != 0
                    ? byDeadline
                    : mountId.toString().compareTo(other.mountId.toString());
        }
    }
}
