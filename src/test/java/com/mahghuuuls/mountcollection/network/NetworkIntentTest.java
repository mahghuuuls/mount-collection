package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NetworkIntentTest {

    @Test
    void preBoardingBuildIsRejectedEvenThoughItsPacketHasTheSameLength() {
        ByteBuf intent = Unpooled.buffer();
        new ContextualIntentMessage(UUID.randomUUID(), 1, true).toBytes(intent);
        intent.setInt(0, 1);
        assertThrows(IllegalArgumentException.class, () -> new ContextualIntentMessage().fromBytes(intent));
        ByteBuf handshake = Unpooled.buffer();
        new ExperienceProtocol(UUID.randomUUID()).toBytes(handshake);
        handshake.setInt(0, 1);
        assertThrows(IllegalArgumentException.class, () -> new ExperienceProtocol().fromBytes(handshake));
    }

    @Test
    void contextualIntentCarriesOnlyBoundedSessionSequenceAndPreference() {
        UUID session = UUID.randomUUID();
        ContextualIntentMessage message = new ContextualIntentMessage(session, 1, true);
        ByteBuf encoded = Unpooled.buffer();

        message.toBytes(encoded);

        assertEquals(29, encoded.readableBytes());
        ContextualIntentMessage decoded = new ContextualIntentMessage();
        decoded.fromBytes(encoded);
        assertEquals(session, decoded.getSession());
        assertEquals(1, decoded.getSequence());
        assertTrue(decoded.isAutomaticRiding());
        assertThrows(IllegalArgumentException.class, () -> message.fromBytes(Unpooled.buffer(0)));
        ByteBuf forged = Unpooled.buffer();
        forged.writeByte(1);
        assertThrows(IllegalArgumentException.class, () -> message.fromBytes(forged));
    }

    @Test
    void protocolRejectsWrongRevisionTrailingBytesAndInvalidBoolean() {
        ByteBuf packet = Unpooled.buffer();
        new ContextualIntentMessage(UUID.randomUUID(), 3, false).toBytes(packet);
        packet.setByte(28, 2);
        assertThrows(IllegalArgumentException.class, () -> new ContextualIntentMessage().fromBytes(packet));
        packet.setByte(28, 0);
        packet.setInt(0, 99);
        packet.readerIndex(0);
        assertThrows(IllegalArgumentException.class, () -> new ContextualIntentMessage().fromBytes(packet));
        ByteBuf extra = Unpooled.buffer();
        new ContextualIntentMessage(UUID.randomUUID(), 3, false).toBytes(extra);
        extra.writeByte(0);
        assertThrows(IllegalArgumentException.class, () -> new ContextualIntentMessage().fromBytes(extra));
    }

    @Test
    void sessionRequiresHandshakeAndRejectsReplaysAndOldConnections() {
        ContextualSession session = new ContextualSession();
        ContextualIntentMessage first = new ContextualIntentMessage(session.id(), 1, true);
        assertFalse(session.admit(first));
        session.acknowledge(UUID.randomUUID());
        assertFalse(session.admit(first));
        session.acknowledge(session.id());
        assertTrue(session.admit(first));
        assertFalse(session.admit(first));
        assertTrue(session.admit(new ContextualIntentMessage(session.id(), 3, false)));
        assertFalse(session.admit(new ContextualIntentMessage(session.id(), 2, true)));
        ContextualSession replacement = new ContextualSession();
        replacement.acknowledge(replacement.id());
        assertFalse(replacement.admit(first));
    }

    @Test
    void gateRejectsRepeatedSameTickIntentAndCanForgetLogoutState() {
        IntentGate gate = new IntentGate();
        UUID player = UUID.randomUUID();

        assertTrue(gate.acquire(player, 10L));
        assertFalse(gate.acquire(player, 10L));
        assertTrue(gate.acquire(player, 11L));
        gate.remove(player);
        assertTrue(gate.acquire(player, 11L));
    }

    @Test
    void lifeTransitionInvalidatesQueuedGenerationWithoutResettingReplayGuard() {
        ContextualSession session = new ContextualSession();
        session.acknowledge(session.id());
        ContextualIntentMessage message = new ContextualIntentMessage(session.id(), 1, true);
        assertTrue(session.admit(message));
        long queuedGeneration = session.generation();
        session.invalidatePending();
        assertTrue(session.generation() != queuedGeneration);
        assertFalse(session.admit(message));
        assertTrue(session.admit(new ContextualIntentMessage(session.id(), 2, true)));
    }
}
