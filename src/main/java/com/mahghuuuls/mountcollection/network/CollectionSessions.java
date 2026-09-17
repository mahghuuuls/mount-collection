package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.collection.CollectionService;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Server-thread owner sessions: one frozen stream, fair bounded sending and cleanup. */
public final class CollectionSessions {
    private static final int PAGES_PER_TICK = 4;
    private static final long IDLE_TICKS = 600;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, Long> requests = new HashMap<>();
    private int cursor;
    private final java.util.function.Predicate<UUID> suppressOpen;

    public CollectionSessions() { this(owner -> false); }

    public CollectionSessions(java.util.function.Predicate<UUID> suppressOpen) {
        this.suppressOpen = java.util.Objects.requireNonNull(suppressOpen, "suppressOpen");
    }

    public void request(UUID owner, CollectionIntent intent, long tick,
            CollectionService service, BiConsumer<UUID, IMessage> output) {
        request(owner, intent, tick, service, output, ignored -> CollectionReply.Result.UNAVAILABLE);
    }

    public void request(UUID owner, CollectionIntent intent, long tick,
            CollectionService service, BiConsumer<UUID, IMessage> output,
            java.util.function.Function<CollectionIntent, CollectionReply.Result> abandon) {
        Session current = sessions.get(owner);
        if (intent.getAction() == CollectionIntent.Action.CLOSE) {
            if (current != null && current.id.equals(intent.getSession())) { sessions.remove(owner); }
            return;
        }
        Long previous = requests.get(owner);
        long delay = intent.getAction() == CollectionIntent.Action.OPEN ? 20 : 5;
        if (previous != null && tick >= previous && tick - previous < delay) {
            output.accept(owner, new CollectionReply(intent.getSession(), CollectionReply.Result.RATE_LIMITED));
            return;
        }
        requests.put(owner, tick);
        if (service == null) {
            sessions.remove(owner);
            output.accept(owner, new CollectionReply(intent.getSession(), CollectionReply.Result.UNAVAILABLE));
            return;
        }
        if (intent.getAction() == CollectionIntent.Action.SELECT || intent.getAction() == CollectionIntent.Action.RENAME
                || intent.getAction() == CollectionIntent.Action.ABANDON) {
            if (current == null || !current.id.equals(intent.getSession()) || !current.pages.isEmpty()
                    || current.revision != intent.getRevision()) {
                output.accept(owner, new CollectionReply(intent.getSession(), CollectionReply.Result.STALE));
                return;
            }
            CollectionReply.Result result;
            if (intent.getAction() == CollectionIntent.Action.ABANDON) {
                result = abandon.apply(intent);
            } else if (intent.getAction() == CollectionIntent.Action.RENAME) {
                switch (service.rename(owner, intent.getTarget(), intent.getRevision(), intent.getName())) {
                    case SUCCESS: result = CollectionReply.Result.RENAMED; break;
                    case UNCHANGED: result = CollectionReply.Result.NAME_UNCHANGED; break;
                    case INVALID: result = CollectionReply.Result.INVALID_NAME; break;
                    case BUSY: result = CollectionReply.Result.BUSY; break;
                    case UNAVAILABLE: result = CollectionReply.Result.UNAVAILABLE; break;
                    case NOT_OWNED: result = CollectionReply.Result.NOT_OWNED; break;
                    case STALE: result = CollectionReply.Result.STALE; break;
                    case READ_ONLY: result = CollectionReply.Result.READ_ONLY; break;
                    default: result = CollectionReply.Result.SAVE_FAILED;
                }
            } else {
            MountRepository.SelectionStatus status = service.select(owner, intent.getTarget(), intent.getRevision());
            switch (status) {
                case SUCCESS: result = CollectionReply.Result.SELECTED; break;
                case UNCHANGED: result = CollectionReply.Result.UNCHANGED; break;
                case STALE: result = CollectionReply.Result.STALE; break;
                case NOT_OWNED: result = CollectionReply.Result.NOT_OWNED; break;
                case READ_ONLY: result = CollectionReply.Result.READ_ONLY; break;
                default: result = CollectionReply.Result.SAVE_FAILED;
            }
            }
            output.accept(owner, new CollectionReply(intent.getSession(), result));
        }
        // A development-only one-shot can omit an OPEN stream, never a mutation response.
        if (intent.getAction() == CollectionIntent.Action.OPEN && suppressOpen.test(owner)) {
            sessions.remove(owner);
            return;
        }
        CollectionView view = service.snapshot(owner, tick);
        List<CollectionPage> pages = CollectionPage.split(intent.getSession(), view);
        sessions.put(owner, new Session(intent.getSession(), view.getSelectionRevision(), pages, tick));
        output.accept(owner, new CollectionHeader(intent.getSession(), view.getSelectionRevision(),
                view.getEntries().size(), pages.size(), view.getSelected()));
    }

    public void tick(long tick, BiConsumer<UUID, IMessage> output) {
        sessions.entrySet().removeIf(entry -> tick < entry.getValue().lastActivity
                || tick - entry.getValue().lastActivity > IDLE_TICKS);
        List<UUID> owners = new ArrayList<>(sessions.keySet());
        if (owners.isEmpty()) { cursor = 0; return; }
        int sent = 0;
        int checked = 0;
        while (checked < owners.size() && sent < PAGES_PER_TICK) {
            int slot = Math.floorMod(cursor++, owners.size());
            checked++;
            UUID owner = owners.get(slot);
            Session session = sessions.get(owner);
            if (!session.pages.isEmpty()) {
                output.accept(owner, session.pages.get(session.next++));
                session.lastActivity = tick;
                sent++;
                if (session.next == session.pages.size()) { session.pages = Collections.emptyList(); }
            }
        }
    }
    public void remove(UUID owner) { sessions.remove(owner); requests.remove(owner); }
    public void clear() { sessions.clear(); requests.clear(); cursor = 0; }
    int activeCount() { return sessions.size(); }
    private static final class Session {
        final UUID id;
        final long revision;
        List<CollectionPage> pages;
        int next;
        long lastActivity;
        Session(UUID id, long revision, List<CollectionPage> pages, long tick) {
            this.id = id; this.revision = revision; this.pages = pages; lastActivity = tick;
        }
    }
}
