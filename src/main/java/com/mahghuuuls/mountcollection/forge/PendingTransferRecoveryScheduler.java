package com.mahghuuuls.mountcollection.forge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Defers transfer recovery until normal entity loading has had an opportunity to expose relocated
 * controlled evidence. World-load callbacks can run before those entities enter the world's UUID
 * index, so treating absence there as final would create a false integrity conflict.
 */
final class PendingTransferRecoveryScheduler {

    static final class Request {
        private final UUID operationId;
        private final boolean sourceObserved;
        private final boolean candidateObserved;

        private Request(UUID operationId, int observations) {
            this.operationId = operationId;
            this.sourceObserved = (observations & 1) != 0;
            this.candidateObserved = (observations & 2) != 0;
        }

        UUID getOperationId() { return operationId; }
        boolean isSourceObserved() { return sourceObserved; }
        boolean isCandidateObserved() { return candidateObserved; }
    }

    private final Map<UUID, Integer> observations = new LinkedHashMap<>();
    private final Set<UUID> requested = new LinkedHashSet<>();

    void repositoryActivated() {
        observations.clear();
        requested.clear();
    }

    void controlledEntityJoined(UUID operationId, boolean candidate) {
        if (operationId == null) {
            return;
        }
        int observed = observations.containsKey(operationId)
                ? observations.get(operationId)
                : 0;
        observations.put(operationId, observed | (candidate ? 2 : 1));
        requested.add(operationId);
    }

    void playerJoined() {
        requested.addAll(observations.keySet());
    }

    void worldLoaded() {
        requested.addAll(observations.keySet());
    }

    Request poll() {
        Iterator<UUID> iterator = requested.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        UUID operationId = iterator.next();
        iterator.remove();
        Integer observed = observations.get(operationId);
        return observed == null ? null : new Request(operationId, observed);
    }

    void defer(Request request) {
        if (request == null) {
            return;
        }
        int observed = (request.isSourceObserved() ? 1 : 0)
                | (request.isCandidateObserved() ? 2 : 0);
        int existing = observations.containsKey(request.getOperationId())
                ? observations.get(request.getOperationId())
                : 0;
        observations.put(request.getOperationId(), existing | observed);
        requested.add(request.getOperationId());
    }

    void operationFinished(UUID operationId) {
        observations.remove(operationId);
        requested.remove(operationId);
    }

    void reset() {
        observations.clear();
        requested.clear();
    }
}
