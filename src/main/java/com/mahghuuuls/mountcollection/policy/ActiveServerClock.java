package com.mahghuuuls.mountcollection.policy;

/**
 * Owns active-time arithmetic. Persistence supplies and retains its current tick in later slices.
 */
public final class ActiveServerClock {

    private long currentTick;

    public synchronized ActiveTimeResult restore(long persistedTick, long minimumSafeTick) {
        if (minimumSafeTick < 0L) {
            minimumSafeTick = 0L;
        }
        if (persistedTick < 0L) {
            currentTick = minimumSafeTick;
            return new ActiveTimeResult(currentTick, ActiveTimeResult.Status.NEGATIVE_INPUT);
        }
        if (persistedTick < minimumSafeTick) {
            currentTick = minimumSafeTick;
            return new ActiveTimeResult(currentTick, ActiveTimeResult.Status.BACKWARD_INPUT);
        }
        currentTick = persistedTick;
        return new ActiveTimeResult(currentTick, ActiveTimeResult.Status.VALID);
    }

    public synchronized ActiveTimeResult advance() {
        if (currentTick == Long.MAX_VALUE) {
            currentTick = 0L;
            return new ActiveTimeResult(currentTick, ActiveTimeResult.Status.OVERFLOW_REBASED);
        }
        currentTick++;
        return new ActiveTimeResult(currentTick, ActiveTimeResult.Status.VALID);
    }

    public synchronized long now() {
        return currentTick;
    }

    public synchronized ActiveTimeResult deadlineAfter(long durationTicks) {
        if (durationTicks < 0L) {
            return new ActiveTimeResult(Long.MAX_VALUE, ActiveTimeResult.Status.NEGATIVE_INPUT);
        }
        if (durationTicks > Long.MAX_VALUE - currentTick) {
            return new ActiveTimeResult(Long.MAX_VALUE, ActiveTimeResult.Status.OVERFLOW_SATURATED);
        }
        return new ActiveTimeResult(currentTick + durationTicks, ActiveTimeResult.Status.VALID);
    }

    public synchronized ActiveTimeResult remainingUntil(long deadlineTick) {
        if (deadlineTick < 0L) {
            return new ActiveTimeResult(Long.MAX_VALUE, ActiveTimeResult.Status.NEGATIVE_INPUT);
        }
        return new ActiveTimeResult(Math.max(0L, deadlineTick - currentTick), ActiveTimeResult.Status.VALID);
    }

    /**
     * Bounds a persisted deadline by the duration that originally created it. This prevents a
     * corrupted or reset active clock from turning a finite cooldown into an effectively permanent
     * lock.
     */
    public synchronized ActiveTimeResult remainingUntil(long deadlineTick, long maximumDurationTicks) {
        if (deadlineTick < 0L || maximumDurationTicks < 0L) {
            return new ActiveTimeResult(
                    Math.max(0L, maximumDurationTicks), ActiveTimeResult.Status.NEGATIVE_INPUT);
        }
        long remaining = deadlineTick <= currentTick ? 0L : deadlineTick - currentTick;
        if (remaining < 0L || remaining > maximumDurationTicks) {
            return new ActiveTimeResult(maximumDurationTicks, ActiveTimeResult.Status.BOUNDED_CLAMP);
        }
        return new ActiveTimeResult(remaining, ActiveTimeResult.Status.VALID);
    }
}
