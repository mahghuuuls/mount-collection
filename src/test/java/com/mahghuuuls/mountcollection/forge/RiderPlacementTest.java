package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RiderPlacementTest {
    @Test void transformsOffsetSeatAndAddsActualPlayerDimensions() {
        SeatEnvelope seat = new SeatEnvelope(0, 1.2, -0.3, 0, 1.2, -0.3);
        AxisAlignedBB box = RiderPlacement.envelope(seat, 10, 64, 20, 90, .6, 1.8, -.35);
        assertEquals(10, box.minX, 1E-8); assertEquals(10.6, box.maxX, 1E-8);
        assertEquals(19.7, box.minZ, 1E-8); assertEquals(20.3, box.maxZ, 1E-8);
        assertEquals(64.85, box.minY, 1E-8); assertEquals(66.65, box.maxY, 1E-8);
    }

    @Test void includesAllPoseCornersAndStandingRiderHeight() {
        SeatEnvelope seat = new SeatEnvelope(-.7, 1.2, -.7, .7, 1.35, .7);
        AxisAlignedBB box = RiderPlacement.envelope(seat, 0, 64, 0, 45, .6, 1.8, -.35);
        assertEquals(.7 * Math.sqrt(2) + .3, box.maxX, 1E-8);
        assertEquals(-box.maxX, box.minX, 1E-8);
        assertEquals(66.8, box.maxY, 1E-8);
    }

    @Test void invalidSeatRangesAreRejectedRatherThanNormalized() {
        assertThrows(IllegalArgumentException.class, () -> new SeatEnvelope(1, 0, 0, -1, 1, 1));
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -17, 17}) {
            assertThrows(IllegalArgumentException.class, () -> new SeatEnvelope(0, 0, 0, value, 0, 0));
        }
        assertDoesNotThrow(() -> new SeatEnvelope(0, 0, 0, 0, 0, 0));
    }

    @Test void malformedRiderDimensionsFailClosed() {
        SeatEnvelope seat = new SeatEnvelope(0, 1, 0, 0, 1, 0);
        for (double value : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 17}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RiderPlacement.envelope(seat, 0, 64, 0, 0, value, 1.8, -.35));
            assertThrows(IllegalArgumentException.class,
                    () -> RiderPlacement.envelope(seat, 0, 64, 0, 0, .6, value, -.35));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RiderPlacement.envelope(seat, 0, 64, 0, Float.NaN, .6, 1.8, -.35));
    }
}
