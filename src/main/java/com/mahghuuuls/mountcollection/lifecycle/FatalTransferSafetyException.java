package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import java.util.Objects;
import java.util.UUID;

/**
 * Signals that a durable physical-action intent could not be closed, rolled back, or
 * quarantined, or that an acknowledged captured source could not complete containment.
 * This exception must escape the server-tick END listener so Minecraft stops before
 * controlled evidence can move during another tick or later event callback.
 */
public final class FatalTransferSafetyException extends RuntimeException {

    private final UUID operationId;
    private final TransferPhase phase;
    private final RestorationPhase restorationPhase;

    public FatalTransferSafetyException(
            UUID operationId, TransferPhase phase, String reason) {
        this(operationId, phase, reason, null);
    }

    public FatalTransferSafetyException(
            UUID operationId, TransferPhase phase, String reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason"), cause);
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.restorationPhase = null;
        if (!phase.isActionIntent()) {
            throw new IllegalArgumentException("fatal transfer safety requires an action intent");
        }
    }

    public FatalTransferSafetyException(
            UUID operationId, RestorationPhase phase, String reason) {
        this(operationId, phase, reason, null);
    }

    public FatalTransferSafetyException(
            UUID operationId, RestorationPhase phase, String reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "reason"), cause);
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.phase = null;
        this.restorationPhase = Objects.requireNonNull(phase, "phase");
        if (phase != RestorationPhase.CANDIDATE_SPAWN_INTENT) {
            throw new IllegalArgumentException("fatal restoration safety requires an action intent");
        }
    }

    public UUID getOperationId() {
        return operationId;
    }

    public static FatalTransferSafetyException abandonmentFailure(UUID operationId) {
        return new FatalTransferSafetyException(operationId,
                "abandonment safe state could not be acknowledged");
    }

    private FatalTransferSafetyException(UUID operationId, String reason) {
        super(reason);
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.phase = null;
        this.restorationPhase = null;
    }

    /** A captured source is durable authority even before a restoration journal exists. */
    public static FatalTransferSafetyException capturedSourceFailure(UUID sourceId, Throwable cause) {
        return new FatalTransferSafetyException(sourceId, cause);
    }

    private FatalTransferSafetyException(UUID sourceId, Throwable cause) {
        super("captured source could not be contained", cause);
        this.operationId = Objects.requireNonNull(sourceId, "sourceId");
        this.phase = null;
        this.restorationPhase = null;
    }

    public TransferPhase getPhase() {
        return phase;
    }

    public RestorationPhase getRestorationPhase() {
        return restorationPhase;
    }

    public String boundedDiagnosticDetail() {
        Object activePhase = phase == null ? restorationPhase : phase;
        if (activePhase == null) { activePhase = getMessage().startsWith("abandonment") ? "ABANDONMENT" : "CAPTURED_SOURCE"; }
        return "operation=" + operationId + " phase=" + activePhase + " reason=" + getMessage();
    }
}
