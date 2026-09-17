package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.network.CollectionIntent;
import com.mahghuuuls.mountcollection.network.CollectionReply;

/** Keeps background transfer distinct from visible loading and freezes at most one user action. */
final class CollectionRefresh {
    private enum Phase { IDLE, LOADING, BACKGROUND, ACTION }
    private Phase phase = Phase.IDLE;
    private boolean usable;
    private boolean failed;
    private CollectionIntent deferred;
    private String notice = "";
    private boolean transientNotice;

    String notice() { return notice; }
    void notice(String key) { notice = key; transientNotice = false; }
    void reply(CollectionReply.Result result) {
        notice("mountcollection.gui.result." + result.name().toLowerCase(java.util.Locale.ROOT));
        transientNotice = result == CollectionReply.Result.UNAVAILABLE
                || result == CollectionReply.Result.STALE || result == CollectionReply.Result.RATE_LIMITED;
    }

    boolean pending() { return phase != Phase.IDLE; }
    boolean canRetry() { return failed && !pending(); }
    boolean showLoading() { return phase == Phase.LOADING || phase == Phase.ACTION; }
    boolean actionable() { return usable && (phase == Phase.IDLE || phase == Phase.BACKGROUND); }
    void begin(boolean background) {
        failed = false;
        if (!background) { notice(""); }
        deferred = null;
        phase = background && usable ? Phase.BACKGROUND : Phase.LOADING;
        if (phase == Phase.LOADING) { usable = false; }
    }
    /** False means the request is retained until the in-flight snapshot finishes. */
    boolean submit(CollectionIntent intent) {
        if (!actionable()) { return false; }
        boolean ready = phase == Phase.IDLE;
        deferred = ready ? null : intent;
        phase = Phase.ACTION;
        usable = false;
        return ready;
    }
    CollectionIntent complete() {
        failed = false;
        if (transientNotice) { notice(""); }
        CollectionIntent result = deferred;
        deferred = null; phase = Phase.IDLE; usable = true;
        return result;
    }
    static boolean stillCurrent(CollectionIntent intent, CollectionView view) {
        return intent.getRevision() == view.getSelectionRevision()
                && view.getEntries().stream().anyMatch(entry -> entry.getId().equals(intent.getTarget()));
    }
    void invalidate() { deferred = null; phase = Phase.IDLE; usable = false; failed = true; }
}
