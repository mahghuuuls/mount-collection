package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

final class PlacementVolumeTest {

    @Test
    void completeEntityVolumeMustMatchTheRequestedMedium() {
        AxisAlignedBB twoBlocksTall = new AxisAlignedBB(1.2D, 64.0D, 2.2D, 1.8D, 66.0D, 2.8D);
        Set<String> medium = new HashSet<>();
        medium.add("1,64,2");

        assertFalse(PlacementVolume.allCellsMatch(
                twoBlocksTall, (x, y, z) -> medium.contains(x + "," + y + "," + z)));

        medium.add("1,65,2");
        assertTrue(PlacementVolume.allCellsMatch(
                twoBlocksTall, (x, y, z) -> medium.contains(x + "," + y + "," + z)));
    }

    @Test
    void exclusiveMaximumFaceDoesNotRequireAnAdjacentCell() {
        AxisAlignedBB exactCell = new AxisAlignedBB(3.0D, 10.0D, 4.0D, 4.0D, 11.0D, 5.0D);

        assertTrue(PlacementVolume.allCellsMatch(
                exactCell, (x, y, z) -> x == 3 && y == 10 && z == 4));
    }

    @Test
    void widerMountRequiresEveryIntersectedHorizontalCell() {
        AxisAlignedBB wide = new AxisAlignedBB(0.2D, 20.0D, 0.2D, 2.2D, 21.0D, 2.2D);

        assertFalse(PlacementVolume.allCellsMatch(wide, (x, y, z) -> x != 1));
        assertTrue(PlacementVolume.allCellsMatch(wide, (x, y, z) -> true));
    }
}
