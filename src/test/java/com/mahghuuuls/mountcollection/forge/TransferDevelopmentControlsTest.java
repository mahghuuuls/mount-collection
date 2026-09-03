package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import org.junit.jupiter.api.Test;

final class TransferDevelopmentControlsTest {

    @Test
    void controlsAreUnavailableAndCannotBeArmedOutsideDevelopment() {
        TransferDevelopmentControls controls = new TransferDevelopmentControls(() -> false);

        assertFalse(controls.isAvailable());
        assertFalse(controls.armFault(
                TransferDevelopmentControls.Fault.JOURNAL_ACKNOWLEDGEMENT));
        assertFalse(controls.armPause(TransferPhase.PREPARED));
        assertFalse(controls.consumeJournalAcknowledgementFault());
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
        assertEquals("fault=NONE fatalAfter=NONE pause=NONE", controls.describe());
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
        assertEquals("fault=NONE fatalAfter=NONE pause=NONE", controls.describe());
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
                "fault=JOURNAL_FATAL_SEQUENCE remaining=3 fatalAfter=NONE pause=NONE",
                controls.describe());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertTrue(controls.consumeJournalAcknowledgementFault());
        assertFalse(controls.consumeJournalAcknowledgementFault());
        assertEquals("fault=NONE fatalAfter=NONE pause=NONE", controls.describe());
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
}
