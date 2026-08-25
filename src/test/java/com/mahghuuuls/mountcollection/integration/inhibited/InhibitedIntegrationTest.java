package com.mahghuuuls.mountcollection.integration.inhibited;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class InhibitedIntegrationTest {

    @Test
    void disabledAndUnavailableRemainDistinct() {
        InhibitedIntegration integration = new InhibitedIntegration(() -> null);

        assertEquals(InhibitedStatus.DISABLED, integration.getStatus(null, false));
        assertEquals(InhibitedStatus.UNAVAILABLE, integration.getStatus(null, true));
    }

    @Test
    void availableEffectDistinguishesUnaffectedAndAffected() {
        assertEquals(
                InhibitedStatus.UNAFFECTED,
                InhibitedIntegration.resolve(true, true, () -> false));
        assertEquals(
                InhibitedStatus.AFFECTED,
                InhibitedIntegration.resolve(true, true, () -> true));
    }
}
