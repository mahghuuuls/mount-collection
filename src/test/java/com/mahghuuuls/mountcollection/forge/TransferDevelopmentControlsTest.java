package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import org.junit.jupiter.api.Test;

final class TransferDevelopmentControlsTest {
    @Test void collectionTimeoutIsOwnerBoundOneShotAndClearedAtLifecycleBoundaries() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        TransferDevelopmentControls release = new TransferDevelopmentControls(() -> false);
        assertFalse(release.armCollectionTimeout(owner));
        assertFalse(release.consumeCollectionTimeout(owner));
        java.util.concurrent.atomic.AtomicBoolean development = new java.util.concurrent.atomic.AtomicBoolean(true);
        TransferDevelopmentControls controls = new TransferDevelopmentControls(development::get);
        assertTrue(controls.armCollectionTimeout(owner));
        controls.clearCollectionTimeout(other);
        assertFalse(controls.consumeCollectionTimeout(other));
        development.set(false);
        assertFalse(controls.consumeCollectionTimeout(owner));
        development.set(true);
        assertTrue(controls.consumeCollectionTimeout(owner));
        assertFalse(controls.consumeCollectionTimeout(owner));
        controls.armCollectionTimeout(owner);
        controls.clearCollectionTimeout(owner);
        assertFalse(controls.consumeCollectionTimeout(owner));
        controls.armCollectionTimeout(owner);
        controls.clear();
        assertFalse(controls.consumeCollectionTimeout(owner));
    }

    @Test
    void controlsAreUnavailableAndCannotBeArmedOutsideDevelopment() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> false);

        assertFalse(controls.isAvailable());
        assertFalse(controls.armFault(
                TransferDevelopmentControls.Fault.JOURNAL_ACKNOWLEDGEMENT));
        assertFalse(controls.armPause(TransferPhase.PREPARED));
        assertFalse(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.armRecoveryProviderUnavailable());
        assertFalse(controls.consumeRecoveryProviderUnavailable());
        assertFalse(controls.shouldPause(TransferPhase.PREPARED));
        assertEquals("unavailable", controls.describe());
    }

    @Test
    void faultsAreConsumedOnceAndCanBeReplacedOrCleared() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armFault(
                TransferDevelopmentControls.Fault.JOURNAL_ACKNOWLEDGEMENT));
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.consumeJournalAcknowledgementFault());

        assertTrue(controls.armFault(
                TransferDevelopmentControls.Fault.PHYSICAL_FENCE_POST_DRAIN));
        assertTrue(controls.consumePhysicalFencePostDrainFault());
        assertFalse(controls.consumePhysicalFencePostDrainFault());

        assertTrue(controls.armFault(
                TransferDevelopmentControls.Fault.JOURNAL_ACKNOWLEDGEMENT));
        controls.clear();
        assertEquals(idleDescription(), controls.describe());
        assertFalse(controls.consumeJournalAcknowledgementFault());
    }

    @Test
    void phasePausePersistsAtTheExactPhaseUntilExplicitlyCleared() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armPause(TransferPhase.ASSOCIATED));
        assertFalse(controls.shouldPause(TransferPhase.CANDIDATE_SPAWNED));
        assertTrue(controls.shouldPause(TransferPhase.ASSOCIATED));
        assertTrue(controls.shouldPause(TransferPhase.ASSOCIATED));
        controls.clear();
        assertFalse(controls.shouldPause(TransferPhase.ASSOCIATED));
    }

    @Test
    void actionIntentPhasesCannotBePaused() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertFalse(controls.armPause(TransferPhase.CANDIDATE_SPAWN_INTENT));
        assertFalse(controls.armPause(TransferPhase.SOURCE_REMOVAL_INTENT));
        assertFalse(controls.armPause(TransferPhase.INTEGRITY_BLOCKED));
        assertEquals(idleDescription(), controls.describe());
    }

    @Test
    void fatalCandidateBoundaryActivatesOnlyAfterIntentAcknowledgement() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armFatalAfterIntent(TransferPhase.CANDIDATE_SPAWN_INTENT));
        assertFalse(controls.consumeJournalAcknowledgementFault());
        controls.phaseAcknowledged(TransferPhase.PREPARED);
        assertFalse(controls.consumeJournalAcknowledgementFault());
        controls.phaseAcknowledged(TransferPhase.CANDIDATE_SPAWN_INTENT);
        assertEquals(
                "fault=JOURNAL_FATAL_SEQUENCE remaining=3 fatalAfter=NONE pause=NONE"
                        + " recoveryFatalAfter=NONE recoveryPause=NONE"
                        + " recoveryProviderUnavailable=false",
                controls.describe());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.consumeJournalAcknowledgementFault());
        assertEquals(idleDescription(), controls.describe());
    }

    @Test
    void fatalSourceBoundaryInjectsTheCloseAndQuarantineFailures() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armFatalAfterIntent(TransferPhase.SOURCE_REMOVAL_INTENT));
        controls.phaseAcknowledged(TransferPhase.SOURCE_REMOVAL_INTENT);

        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.consumeJournalAcknowledgementFault());
    }

    @Test
    void restorationStablePhasesPauseButActionIntentAndBlockedDoNot() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armRestorationPause(RestorationPhase.PREPARED));
        assertTrue(controls.shouldPauseRestoration(RestorationPhase.PREPARED));
        assertFalse(controls.armRestorationPause(RestorationPhase.CANDIDATE_SPAWN_INTENT));
        assertFalse(controls.armRestorationPause(RestorationPhase.INTEGRITY_BLOCKED));
        controls.clear();
        assertFalse(controls.shouldPauseRestoration(RestorationPhase.PREPARED));
    }

    @Test
    void fatalRestorationBoundaryActivatesOnlyAfterIntentAcknowledgement() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armRestorationFatalAfterIntent(
                RestorationPhase.CANDIDATE_SPAWN_INTENT));
        controls.restorationPhaseAcknowledged(RestorationPhase.PREPARED);
        assertFalse(controls.consumeJournalAcknowledgementFault());
        controls.restorationPhaseAcknowledged(RestorationPhase.CANDIDATE_SPAWN_INTENT);
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.consumeJournalAcknowledgementFault());
    }

    @Test
    void providerUnavailableFaultIsOneShot() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> true);

        assertTrue(controls.armRecoveryProviderUnavailable());
        assertTrue(controls.consumeRecoveryProviderUnavailable());
        assertFalse(controls.consumeRecoveryProviderUnavailable());
    }

    private static String idleDescription() {
        return "fault=NONE fatalAfter=NONE pause=NONE"
                + " recoveryFatalAfter=NONE recoveryPause=NONE"
                + " recoveryProviderUnavailable=false";
    }
}
