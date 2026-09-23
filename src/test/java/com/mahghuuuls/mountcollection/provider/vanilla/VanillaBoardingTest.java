package com.mahghuuuls.mountcollection.provider.vanilla;

import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VanillaBoardingTest {
    @Test void pigNeedsSaddleButHorseDoesNotGainSteeringPermission() {
        assertFalse(VanillaBoarding.describe(true, false, true, false, false, false, false, .675).isSuccess());
        assertTrue(VanillaBoarding.describe(true, false, true, false, false, false, true, .675).isSuccess());
        assertTrue(VanillaBoarding.describe(false, false, true, false, false, true, false, 1.2).isSuccess());
        assertFalse(VanillaBoarding.describe(false, false, true, false, false, false, true, 1.2).isSuccess());
    }

    @Test void rejectsDeadYoungOccupiedAndMalformedMounts() {
        assertFalse(VanillaBoarding.describe(false, false, false, false, false, true, true, 1).isSuccess());
        assertFalse(VanillaBoarding.describe(false, false, true, true, false, true, true, 1).isSuccess());
        assertFalse(VanillaBoarding.describe(false, false, true, false, true, true, true, 1).isSuccess());
        assertFalse(VanillaBoarding.describe(false, false, true, false, false, true, true, Double.NaN).isSuccess());
    }

    @Test void llamaOffsetAndHorseRearingHaveDifferentConservativeEnvelopes() {
        SeatEnvelope llama = VanillaBoarding.describe(false, true, true, false, false, true, false, 1.25)
                .getValue().get();
        SeatEnvelope horse = VanillaBoarding.describe(false, false, true, false, false, true, false, 1.2)
                .getValue().get();
        assertTrue(llama.getMinX() < -.3); assertTrue(llama.getMaxZ() > .3);
        assertEquals(1.25, llama.getMaxY());
        assertTrue(horse.getMaxX() > .7); assertTrue(horse.getMaxY() > 1.35);
        SeatEnvelope pig = VanillaBoarding.describe(true, false, true, false, false, false, true, .675)
                .getValue().get();
        assertEquals(0, pig.getMaxX()); assertEquals(.675, pig.getMaxY());
    }
}
