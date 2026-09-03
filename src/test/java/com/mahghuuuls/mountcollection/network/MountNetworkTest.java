package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MountNetworkTest {

    @Test
    void unexpectedOrdinaryFailureRemainsAnInternalFailureResponse() {
        ContextualOutcome outcome = MountNetwork.executeLifecycleIntent(
                () -> { throw new IllegalStateException("ordinary failure"); });

        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE, outcome.getStatus());
    }

    @Test
    void fatalTransferSafetySignalEscapesNetworkOutcomeMapping() {
        FatalTransferSafetyException fatal = new FatalTransferSafetyException(
                UUID.randomUUID(), TransferPhase.CANDIDATE_SPAWN_INTENT,
                "unexpected exception while physical-action intent remained unresolved");

        FatalTransferSafetyException thrown = assertThrows(
                FatalTransferSafetyException.class,
                () -> MountNetwork.executeLifecycleIntent(() -> { throw fatal; }));

        assertSame(fatal, thrown);
    }
}
