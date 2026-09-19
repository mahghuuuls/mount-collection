package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Objects;
import java.util.UUID;

/** A committed gameplay outcome, not a request or a promise of cosmetic delivery. */
public final class ExperienceCompletion {
    public enum Kind { REGISTERED, ARRIVED }
    private final UUID requestId;
    private final UUID ownerId;
    private final MountId mountId;
    private final UUID entityId;
    private final LastKnownEvidence location;
    private final Kind kind;

    public ExperienceCompletion(UUID requestId, UUID ownerId, MountId mountId,
            UUID entityId, LastKnownEvidence location, Kind kind) {
        this.requestId = Objects.requireNonNull(requestId);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.mountId = Objects.requireNonNull(mountId);
        this.entityId = Objects.requireNonNull(entityId);
        this.location = Objects.requireNonNull(location);
        this.kind = Objects.requireNonNull(kind);
    }
    public UUID getRequestId() { return requestId; }
    public UUID getOwnerId() { return ownerId; }
    public MountId getMountId() { return mountId; }
    public UUID getEntityId() { return entityId; }
    public LastKnownEvidence getLocation() { return location; }
    public Kind getKind() { return kind; }
}
