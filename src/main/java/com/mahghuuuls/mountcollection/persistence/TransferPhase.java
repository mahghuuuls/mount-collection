package com.mahghuuuls.mountcollection.persistence;

public enum TransferPhase {
    PREPARED,
    CANDIDATE_SPAWN_INTENT,
    CANDIDATE_SPAWNED,
    ASSOCIATED,
    SOURCE_REMOVAL_INTENT,
    SOURCE_REMOVED,
    INTEGRITY_BLOCKED;

    public boolean isActionIntent() {
        return this == CANDIDATE_SPAWN_INTENT || this == SOURCE_REMOVAL_INTENT;
    }
}
