package com.mahghuuuls.mountcollection.forge;

import java.util.Objects;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;

/** Exact block-cell coverage for a proposed entity placement volume. */
final class PlacementVolume {

    private static final double EXCLUSIVE_MAX_EPSILON = 1.0E-7D;

    interface CellMatcher {
        boolean matches(int x, int y, int z);
    }

    private PlacementVolume() {}

    static boolean allCellsMatch(AxisAlignedBB box, CellMatcher matcher) {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(matcher, "matcher");
        int minX = MathHelper.floor(box.minX);
        int minY = MathHelper.floor(box.minY);
        int minZ = MathHelper.floor(box.minZ);
        int maxX = MathHelper.floor(box.maxX - EXCLUSIVE_MAX_EPSILON);
        int maxY = MathHelper.floor(box.maxY - EXCLUSIVE_MAX_EPSILON);
        int maxZ = MathHelper.floor(box.maxZ - EXCLUSIVE_MAX_EPSILON);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!matcher.matches(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
