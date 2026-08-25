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
    void contextualIntentHasNoClientControlledFieldsAndRejectsPayload() {
        ContextualIntentMessage message = new ContextualIntentMessage();
        ByteBuf encoded = Unpooled.buffer();

        message.toBytes(encoded);

        assertEquals(0, encoded.readableBytes());
        message.fromBytes(Unpooled.buffer(0));
        ByteBuf forged = Unpooled.buffer();
        forged.writeByte(1);
        assertThrows(IllegalArgumentException.class, () -> message.fromBytes(forged));
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
}
