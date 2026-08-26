package com.mahghuuuls.mountcollection.policy;

import java.util.Optional;

/** Deterministic normal-then-fallback placement candidate enumeration. */
public final class PlacementSearch {

    public interface Probe {
        boolean isSafe(int offsetX, int offsetY, int offsetZ);
    }

    public static final class Offset {
        private final int x;
        private final int y;
        private final int z;

        private Offset(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
    }

    public Optional<Offset> find(int normalRadius, int fallbackRadius, Probe probe) {
        Optional<Offset> normal = search(0, normalRadius, probe);
        return normal.isPresent()
                ? normal
                : search(normalRadius + 1, fallbackRadius, probe);
    }

    private static Optional<Offset> search(int minimumRadius, int radius, Probe probe) {
        for (int ring = Math.max(0, minimumRadius); ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = 2; dy >= -4; dy--) {
                        if (probe.isSafe(dx, dy, dz)) {
                            return Optional.of(new Offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}
