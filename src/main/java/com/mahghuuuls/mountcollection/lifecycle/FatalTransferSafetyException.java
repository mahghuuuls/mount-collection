package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import java.util.Objects;
import java.util.UUID;

/**
 * Signals that a durable physical-action intent could not be closed, rolled back, or
 * quarantined. This exception must escape the server-tick END listener so Minecraft stops before
 * controlled evidence can move during another tick or later event callback.
 */
public final class FatalTransferSafetyException extends RuntimeException {

    private final UUID operationId;
    private final TransferPhase phase;

    public FatalTransferSafetyException(
            UUID operationId, TransferPhase phase, String reason) {
        super(Objects.requireNonNull(reason, "reason"));
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (!phase.isActionIntent()) {
            throw new IllegalArgumentException("fatal transfer safety requires an action intent");
        }
    }

    public UUID getOperationId() {
        return operationId;
    }

    public TransferPhase getPhase() {
        return phase;
    }

    public String boundedDiagnosticDetail() {
        return "operation=" + operationId + " phase=" + phase + " reason=" + getMessage();
    }
}
