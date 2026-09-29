package com.mahghuuuls.mountcollection.network;

import java.util.UUID;

/** One authenticated connection; bounded replay protection without request history growth. */
final class ContextualSession {
    private final UUID id = UUID.randomUUID();
    private long lastSequence;
    private boolean ready;
    private long generation;
    private long relocationSequence;
    boolean relocationExhausted() { return relocationSequence==Long.MAX_VALUE; }
    long nextRelocation() {
        if(relocationSequence==Long.MAX_VALUE) { throw new IllegalStateException("relocation sequence exhausted"); }
        return ++relocationSequence;
    }
    long generation() { return generation; }
    void invalidatePending() { generation++; }
    UUID id() { return id; }
    void acknowledge(UUID offered) { if (id.equals(offered)) { ready = true; } }
    boolean isReady() { return ready; }
    boolean admit(ContextualIntentMessage message) {
        if (!ready || !id.equals(message.getSession()) || message.getSequence() <= lastSequence) {
            return false;
        }
        lastSequence = message.getSequence();
        return true;
    }
}
