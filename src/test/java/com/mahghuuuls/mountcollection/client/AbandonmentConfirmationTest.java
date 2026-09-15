package com.mahghuuuls.mountcollection.client;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.network.CollectionIntent;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AbandonmentConfirmationTest {
    @Test void cancellationEmitsNothingAndConfirmationUsesOnlyFrozenIdentityOnce() {
        AbandonmentConfirmation confirmation = new AbandonmentConfirmation();
        UUID session = UUID.randomUUID(); MountId id = MountId.create();
        confirmation.open(session, id, 17, "Shown horse");
        assertTrue(confirmation.isOpen());
        confirmation.cancel();
        assertNull(confirmation.confirm());
        confirmation.open(session, id, 19, "Shown horse");
        assertEquals("Shown horse", confirmation.getName());
        CollectionIntent sent = confirmation.confirm();
        assertEquals(CollectionIntent.Action.ABANDON, sent.getAction());
        assertEquals(session, sent.getSession());
        assertEquals(id, sent.getTarget());
        assertEquals(19, sent.getRevision());
        assertFalse(confirmation.isOpen());
        assertNull(confirmation.confirm());
    }
}
