package com.mahghuuuls.mountcollection.forge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production rider predicate, not a live Minecraft world or entity attachment. */
final class RiderSafetyTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    private final AxisAlignedBB rider = new AxisAlignedBB(.2, 65, .2, .8, 66.8, .8);

    @Test void ceilingBorderEntityAndUnloadedDataIndependentlyRejectTheRider() {
        Probe world = new Probe();
        assertTrue(RiderPlacement.clear(rider, world));
        world.ceiling = 66.5;
        assertFalse(RiderPlacement.clear(rider, world));
        world.ceiling = 256;
        world.borderMaxX = .5;
        assertFalse(RiderPlacement.clear(rider, world));
        world.borderMaxX = 100;
        world.obstructed = true;
        assertFalse(RiderPlacement.clear(rider, world));
        world.obstructed = false;
        world.loaded = false;
        assertFalse(RiderPlacement.clear(rider, world));
        assertFalse(RiderPlacement.clear(new AxisAlignedBB(0, 255, 0, 1, 257, 1), new Probe()));
        assertFalse(RiderPlacement.clear(new AxisAlignedBB(0, -1, 0, 1, 1, 1), new Probe()));
    }

    @Test void everyOccupiedRiderCellMustBeFreeOfFluidAndDamage() {
        Probe world = new Probe();
        for (IBlockState hazard : new IBlockState[] {Blocks.WATER.getDefaultState(),
                Blocks.FLOWING_WATER.getDefaultState(), Blocks.LAVA.getDefaultState(),
                Blocks.FLOWING_LAVA.getDefaultState(), Blocks.FIRE.getDefaultState(),
                Blocks.CACTUS.getDefaultState(), Blocks.MAGMA.getDefaultState()}) {
            world.upperCell = hazard;
            assertFalse(RiderPlacement.clear(rider, world), hazard.toString());
        }
        world.upperCell = Blocks.AIR.getDefaultState();
        assertTrue(RiderPlacement.clear(rider, world));
    }

    @Test void rotatedSeatEnvelopeCannotBorrowMountOnlyClearance() {
        Probe world = new Probe();
        world.borderMaxX = .7;
        assertTrue(world.insideBorder(new AxisAlignedBB(-.4, 64, -.4, .4, 65, .4)));
        AxisAlignedBB offsetRider = RiderPlacement.envelope(
                new com.mahghuuuls.mountcollection.api.SeatEnvelope(0, 1, -.6, 0, 1, -.6),
                0, 64, 0, 90, .6, 1.8, -.35);
        assertFalse(RiderPlacement.clear(offsetRider, world));
    }

    private static final class Probe implements RiderPlacement.WorldAccess {
        boolean loaded = true;
        boolean obstructed;
        double ceiling = 256;
        double borderMaxX = 100;
        IBlockState upperCell = Blocks.AIR.getDefaultState();
        @Override public int height() { return 256; }
        @Override public boolean insideBorder(AxisAlignedBB box) { return box.maxX <= borderMaxX; }
        @Override public boolean loaded(AxisAlignedBB box) { return loaded; }
        @Override public boolean blocksClear(AxisAlignedBB box) {
            assertTrue(loaded, "Do not query collisions in an unloaded world");
            return box.maxY <= ceiling;
        }
        @Override public boolean entitiesClear(AxisAlignedBB box) { return !obstructed; }
        @Override public IBlockState blockAt(int x, int y, int z) {
            assertTrue(loaded, "Do not read unloaded blocks");
            return y == 66 ? upperCell : Blocks.AIR.getDefaultState();
        }
    }
}
