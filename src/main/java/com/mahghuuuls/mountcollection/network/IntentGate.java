package com.mahghuuuls.mountcollection.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class IntentGate {

    private final Map<UUID, Long> lastAcceptedTicks = new HashMap<>();

    synchronized boolean acquire(UUID playerId, long activeTick) {
        Long previous = lastAcceptedTicks.get(playerId);
        if (previous != null && activeTick <= previous) {
            return false;
        }
        lastAcceptedTicks.put(playerId, activeTick);
        return true;
    }

    synchronized void remove(UUID playerId) {
        lastAcceptedTicks.remove(playerId);
    }
}
