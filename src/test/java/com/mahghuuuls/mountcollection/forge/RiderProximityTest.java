package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RiderProximityTest {
    @Test void inclusiveEuclideanAndTerrainLimitsUseExactPlayerPosition() {
        assertTrue(RiderPlacement.nearby(.25,64,.25,2.25,65,.25));
        assertTrue(RiderPlacement.nearby(.25,64,.25,2.249,63,.25));
        assertFalse(RiderPlacement.nearby(.25,64,.25,2.251,64,.25));
        assertFalse(RiderPlacement.nearby(.25,64,.25,1.75,64,1.75));
        assertFalse(RiderPlacement.nearby(.25,64,.25,.25,65.001,.25));
        assertFalse(RiderPlacement.nearby(.25,64,.25,.25,62.999,.25));
        assertFalse(RiderPlacement.nearby(Double.NaN,64,0,0,64,0));
    }

    @Test void seatHeightDoesNotCountButEveryRotatedAnchorDoes() {
        SeatEnvelope tall = new SeatEnvelope(0,8,0,0,8,0);
        assertTrue(RiderPlacement.nearby(tall,0,64,0,0,0,64,0));
        SeatEnvelope offset = new SeatEnvelope(1,2,0,1,2,0);
        assertTrue(RiderPlacement.nearby(offset,1,64,0,0,0,64,0));
        assertFalse(RiderPlacement.nearby(offset,1.001,64,0,0,0,64,0));
        assertTrue(RiderPlacement.nearby(offset,0,64,1,90,0,64,0));
        SeatEnvelope varied = new SeatEnvelope(-1,2,0,1,2,0);
        assertFalse(RiderPlacement.nearby(varied,1.5,64,0,0,0,64,0));
    }
}
