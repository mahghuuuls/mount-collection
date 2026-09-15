package com.mahghuuuls.mountcollection.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owns admission through completion: at most one request and one matching Close per owner. */
final class CollectionIngress {
    private final Map<UUID, State> owners = new HashMap<>();

    void dispatch(UUID owner, CollectionIntent intent, long now,
            Consumer<Runnable> scheduler, Consumer<CollectionIntent> action) {
        Ticket ticket = acquire(owner, intent, now);
        if (ticket == null) { return; }
        try {
            scheduler.accept(() -> {
                try {
                    if (isCurrent(owner, ticket)) { action.accept(intent); }
                } finally { release(owner, ticket); }
            });
        } catch (RuntimeException | Error failure) {
            release(owner, ticket);
            throw failure;
        }
    }

    private synchronized Ticket acquire(UUID owner, CollectionIntent intent, long now) {
        State state = owners.computeIfAbsent(owner, ignored -> new State());
        Ticket ticket = new Ticket(state, intent.getAction() == CollectionIntent.Action.CLOSE);
        if (intent.getAction() == CollectionIntent.Action.CLOSE) {
            if (state.close != null || !intent.getSession().equals(state.closable)) { return null; }
            state.closable = null;
            state.close = ticket;
            return ticket;
        }
        if (state.request != null || state.close != null) { return null; }
        if (state.previous != null && now - state.previous < 50000000L) { return null; }
        state.previous = now;
        if (intent.getAction() == CollectionIntent.Action.OPEN) { state.closable = intent.getSession(); }
        state.request = ticket;
        return ticket;
    }
    private synchronized boolean isCurrent(UUID owner, Ticket ticket) {
        return owners.get(owner) == ticket.state
                && (ticket.close ? ticket.state.close : ticket.state.request) == ticket;
    }
    private synchronized void release(UUID owner, Ticket ticket) {
        if (!isCurrent(owner, ticket)) { return; }
        if (ticket.close) { ticket.state.close = null; }
        else { ticket.state.request = null; }
    }
    synchronized void remove(UUID owner) { owners.remove(owner); }
    synchronized void clear() { owners.clear(); }

    private static final class State {
        Long previous;
        UUID closable;
        Ticket request;
        Ticket close;
    }
    private static final class Ticket {
        final State state;
        final boolean close;
        Ticket(State state, boolean close) { this.state = state; this.close = close; }
    }
}
