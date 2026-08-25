package com.mahghuuuls.mountcollection.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ActiveServerClockTest {

    @Test
    void normalTimeAdvancesAndComputesDeadlines() {
        ActiveServerClock clock = new ActiveServerClock();
        assertTrue(clock.restore(100L, 80L).isValid());
        assertEquals(100L, clock.now());
        assertEquals(120L, clock.deadlineAfter(20L).getValue());
        assertEquals(20L, clock.remainingUntil(120L).getValue());
        clock.advance();
        assertEquals(19L, clock.remainingUntil(120L).getValue());
    }

    @Test
    void backwardAndNegativeRestoreUseSafeMinimum() {
        ActiveServerClock clock = new ActiveServerClock();
        assertEquals(ActiveTimeResult.Status.NEGATIVE_INPUT, clock.restore(-1L, 40L).getStatus());
        assertEquals(40L, clock.now());
        assertEquals(ActiveTimeResult.Status.BACKWARD_INPUT, clock.restore(10L, 40L).getStatus());
        assertEquals(40L, clock.now());
    }

    @Test
    void invalidDurationAndDeadlineFailClosed() {
        ActiveServerClock clock = new ActiveServerClock();
        clock.restore(10L, 0L);
        ActiveTimeResult deadline = clock.deadlineAfter(-1L);
        ActiveTimeResult remaining = clock.remainingUntil(-1L);
        assertEquals(ActiveTimeResult.Status.NEGATIVE_INPUT, deadline.getStatus());
        assertEquals(Long.MAX_VALUE, deadline.getValue());
        assertEquals(ActiveTimeResult.Status.NEGATIVE_INPUT, remaining.getStatus());
        assertEquals(Long.MAX_VALUE, remaining.getValue());
    }

    @Test
    void overflowSaturatesInsteadOfWrapping() {
        ActiveServerClock clock = new ActiveServerClock();
        clock.restore(Long.MAX_VALUE - 1L, 0L);
        assertEquals(ActiveTimeResult.Status.OVERFLOW_SATURATED, clock.deadlineAfter(2L).getStatus());
        assertEquals(Long.MAX_VALUE, clock.deadlineAfter(2L).getValue());
        assertTrue(clock.advance().isValid());
        assertEquals(ActiveTimeResult.Status.OVERFLOW_SATURATED, clock.advance().getStatus());
        assertEquals(Long.MAX_VALUE, clock.now());
    }
}
