package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.network.CollectionIntent;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.UUID;

/** Freezes the displayed target/revision and emits at most one confirmed request. */
final class AbandonmentConfirmation {
    private CollectionIntent request;
    private String name;
    void open(UUID session, MountId target, long revision, String displayName) {
        request = new CollectionIntent(CollectionIntent.Action.ABANDON, session, revision, target);
        name = displayName;
    }
    boolean isOpen() { return request != null; }
    String getName() { return name; }
    void cancel() { request = null; name = null; }
    CollectionIntent confirm() {
        CollectionIntent confirmed = request;
        cancel();
        return confirmed;
    }
}
