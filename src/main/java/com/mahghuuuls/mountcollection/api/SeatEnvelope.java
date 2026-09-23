package com.mahghuuuls.mountcollection.api;

/** Immutable bounded range of seat anchors, before adding rider dimensions and Y offset. */
public final class SeatEnvelope {
    /** Resource bound, not a promise that seats this far away can be placed safely. */
    public static final double MAX_OFFSET = 16.0D;
    private final double minX, minY, minZ, maxX, maxY, maxZ;

    public SeatEnvelope(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        if (!valid(minX) || !valid(minY) || !valid(minZ)
                || !valid(maxX) || !valid(maxY) || !valid(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Seat anchors must be finite, ordered and within 16 blocks");
        }
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_OFFSET;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }
}
