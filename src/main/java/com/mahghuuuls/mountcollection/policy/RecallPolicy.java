package com.mahghuuuls.mountcollection.policy;

import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRecord;

public final class RecallPolicy {

    public ContextualOutcome.Status evaluate(
            MountRecord record,
            boolean providerAvailable,
            boolean hasPassengers,
            int destinationDimension,
            InhibitedStatus inhibited,
            long remainingCooldown,
            ValidatedMountConfig config) {
        if (record.getCondition() == MountCondition.INTEGRITY_BLOCKED) {
            return ContextualOutcome.Status.INTEGRITY_CONFLICT;
        }
        if (record.getCondition() != MountCondition.LIVING || !providerAvailable) {
            return ContextualOutcome.Status.PROVIDER_UNAVAILABLE;
        }
        if (hasPassengers) {
            return ContextualOutcome.Status.PASSENGER_PRESENT;
        }
        if (!config.getSummoningEntities().allows(record.getEntityTypeId())) {
            return ContextualOutcome.Status.SUMMON_DISALLOWED;
        }
        if (!config.getDestinationDimensions().allows(destinationDimension)) {
            return ContextualOutcome.Status.DIMENSION_DISALLOWED;
        }
        if (inhibited == InhibitedStatus.AFFECTED) {
            return ContextualOutcome.Status.INHIBITED;
        }
        if (remainingCooldown > 0L) {
            return ContextualOutcome.Status.COOLDOWN;
        }
        return null;
    }
}
