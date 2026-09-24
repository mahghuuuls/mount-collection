package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import net.minecraft.util.math.AxisAlignedBB;

/** Core-owned transform of provider seat anchors into a conservative occupied rider volume. */
final class RiderPlacement {
    private RiderPlacement() { }

    /** Limits player displacement independently of the mount's wider recall search. */
    static boolean nearby(double playerX, double playerY, double playerZ,
            double seatedX, double mountY, double seatedZ) {
        double dx = seatedX - playerX, dz = seatedZ - playerZ, dy = mountY - playerY;
        return Double.isFinite(dx) && Double.isFinite(dz) && Double.isFinite(dy)
                && dx * dx + dz * dz <= 4.0D && Math.abs(dy) <= 1.0D;
    }

    static boolean nearby(SeatEnvelope seat, double x, double y, double z, float yaw,
            double playerX, double playerY, double playerZ) {
        if (seat == null || !Float.isFinite(yaw)) { return false; }
        double angle = Math.toRadians(yaw % 360.0F);
        double sin = Math.sin(angle), cos = Math.cos(angle);
        // A declaration can cover several native poses; every possible anchor must fit.
        for (double localX : new double[] {seat.getMinX(), seat.getMaxX()}) {
            for (double localZ : new double[] {seat.getMinZ(), seat.getMaxZ()}) {
                if (!nearby(playerX, playerY, playerZ, x + localX * cos - localZ * sin,
                        y, z + localX * sin + localZ * cos)) { return false; }
            }
        }
        return true;
    }

    /** Reads only loaded world data; no mount profile or player effect may waive rider hazards. */
    interface WorldAccess {
        int height();
        boolean insideBorder(AxisAlignedBB box);
        boolean loaded(AxisAlignedBB box);
        boolean blocksClear(AxisAlignedBB box);
        boolean entitiesClear(AxisAlignedBB box);
        net.minecraft.block.state.IBlockState blockAt(int x, int y, int z);
    }

    static boolean clear(AxisAlignedBB box, WorldAccess world) {
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.maxX)
                || !Double.isFinite(box.minY) || !Double.isFinite(box.maxY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxZ)
                || box.minY < 0 || box.maxY >= world.height()
                || !world.insideBorder(box) || !world.loaded(box)
                || !world.blocksClear(box) || !world.entitiesClear(box)) { return false; }
        return PlacementVolume.allCellsMatch(box, (x, y, z) -> {
            net.minecraft.block.state.IBlockState state = world.blockAt(x, y, z);
            net.minecraft.block.material.Material material = state.getMaterial();
            return material != net.minecraft.block.material.Material.WATER
                    && material != net.minecraft.block.material.Material.LAVA
                    && material != net.minecraft.block.material.Material.FIRE
                    && state.getBlock() != net.minecraft.init.Blocks.FIRE
                    && state.getBlock() != net.minecraft.init.Blocks.CACTUS
                    && state.getBlock() != net.minecraft.init.Blocks.MAGMA;
        });
    }

    static AxisAlignedBB envelope(SeatEnvelope seat, double x, double y, double z, float yaw,
            double riderWidth, double riderHeight, double riderYOffset) {
        if (seat == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Double.isFinite(riderWidth)
                || !Double.isFinite(riderHeight) || !Double.isFinite(riderYOffset)
                || riderWidth <= 0 || riderWidth > 16 || riderHeight <= 0 || riderHeight > 16
                || Math.abs(riderYOffset) > 16) {
            throw new IllegalArgumentException("Invalid rider placement dimensions or pose");
        }
        double angle = Math.toRadians(yaw % 360.0F);
        double sin = Math.sin(angle), cos = Math.cos(angle);
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double localX : new double[] {seat.getMinX(), seat.getMaxX()}) {
            for (double localZ : new double[] {seat.getMinZ(), seat.getMaxZ()}) {
                double rotatedX = localX * cos - localZ * sin;
                double rotatedZ = localX * sin + localZ * cos;
                minX = Math.min(minX, rotatedX); maxX = Math.max(maxX, rotatedX);
                minZ = Math.min(minZ, rotatedZ); maxZ = Math.max(maxZ, rotatedZ);
            }
        }
        double halfWidth = riderWidth / 2.0D;
        return new AxisAlignedBB(x + minX - halfWidth, y + seat.getMinY() + riderYOffset,
                z + minZ - halfWidth, x + maxX + halfWidth,
                y + seat.getMaxY() + riderYOffset + riderHeight, z + maxZ + halfWidth);
    }
}
