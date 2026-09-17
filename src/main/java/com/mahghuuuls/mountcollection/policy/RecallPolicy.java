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
        if (!config.allowsSummoning(record.getEntityTypeId(), record.getCharacteristics())) {
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

    public ContextualOutcome.Status evaluateRecovery(
            MountRecord record,
            boolean providerAvailable,
            int destinationDimension,
            InhibitedStatus inhibited,
            long remainingCooldown,
            ValidatedMountConfig config) {
        if (record.getCondition() == MountCondition.INTEGRITY_BLOCKED) {
            return ContextualOutcome.Status.INTEGRITY_CONFLICT;
        }
        if (record.getCondition() != MountCondition.READY_FOR_RECALL || !providerAvailable) {
            return ContextualOutcome.Status.PROVIDER_UNAVAILABLE;
        }
        if (!config.allowsSummoning(record.getEntityTypeId(), record.getCharacteristics())) {
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
