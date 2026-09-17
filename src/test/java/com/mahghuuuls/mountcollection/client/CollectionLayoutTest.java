package com.mahghuuuls.mountcollection.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CollectionLayoutTest {
    @org.junit.jupiter.api.Test void enlargedBadgesFitAbovePreviewAndMatchHoverBounds() {
        CollectionLayout layout = new CollectionLayout(320, 240);
        org.junit.jupiter.api.Assertions.assertEquals(14, CollectionLayout.BADGE_SIZE);
        for (int index = 0; index < 2; index++) {
            org.junit.jupiter.api.Assertions.assertTrue(layout.inBadge(layout.badgeLeft(index) + 13, layout.badgeTop() + 13, index));
            org.junit.jupiter.api.Assertions.assertFalse(layout.inBadge(layout.badgeLeft(index) + 14, layout.badgeTop(), index));
            org.junit.jupiter.api.Assertions.assertFalse(layout.inBadge(layout.badgeLeft(index), layout.badgeTop() + 14, index));
            org.junit.jupiter.api.Assertions.assertTrue(layout.badgeTop() + 14 <= layout.previewTop());
            org.junit.jupiter.api.Assertions.assertTrue(layout.badgeLeft(index) + 14 < layout.right());
        }
    }
    @Test void boundedCenteredPanelLeavesWorldVisible() {
        CollectionLayout layout = new CollectionLayout(854, 480);
        assertEquals(460, layout.width); assertEquals(300, layout.height);
        assertEquals(197, layout.left); assertEquals(90, layout.top);
    }
    @Test void scaledSizesKeepContentAndButtonsInsidePanel() {
        for (int[] size : new int[][] {{320, 240}, {427, 240}, {854, 480}, {1920, 1080}, {641, 361}}) {
            CollectionLayout layout = new CollectionLayout(size[0], size[1]);
            assertTrue(layout.left >= 8 && layout.top >= 8);
            assertTrue(layout.right() <= size[0] - 8 && layout.bottom() <= size[1] - 8);
            assertTrue(Math.abs(layout.left - (size[0] - layout.right())) <= 1);
            assertTrue(Math.abs(layout.top - (size[1] - layout.bottom())) <= 1);
            assertTrue(layout.listTop() + layout.visibleRows() * 28 <= layout.bottom() - 30);
            assertTrue(layout.detailWidth() >= 120);
            assertTrue(layout.previewHeight() >= 24);
            assertTrue(layout.previewTop() + layout.previewHeight() < layout.abandonTop());
            assertTrue(layout.renameTop() + 20 <= layout.top + 54);
            assertTrue(layout.nameWidth() >= 100);
            assertEquals(4, layout.nameLeft() - (layout.detailLeft() + 20));
            assertTrue(layout.abandonLeft() >= layout.detailLeft());
            assertTrue(layout.abandonLeft() + 20 < layout.right());
            assertEquals(8, layout.bottom() - 30 - (layout.abandonTop() + 20));
            assertFalse(layout.inName(layout.detailLeft() + 10, layout.top + 40));
            assertTrue(layout.inName(layout.nameLeft(), layout.top + 38));
            assertTrue(layout.dialogLeft() > layout.left);
            assertTrue(layout.dialogLeft() + layout.dialogWidth() < layout.right());
        }
    }
    @Test void hitRegionsUseSameAbsoluteBoundsAsDrawing() {
        CollectionLayout layout = new CollectionLayout(854, 480);
        assertTrue(layout.inList(layout.listLeft(), layout.listTop()));
        assertFalse(layout.inList(layout.listLeft() - 1, layout.listTop()));
        assertFalse(layout.inList(layout.listLeft(), layout.listTop() + layout.visibleRows() * 28));
        assertTrue(layout.inPreview(layout.detailLeft(), layout.previewTop()));
        assertFalse(layout.inPreview(layout.detailLeft() + layout.detailWidth(), layout.previewTop()));
        assertFalse(layout.inPreview(layout.detailLeft(), layout.previewTop() + layout.previewHeight()));
        assertFalse(layout.inList(12, 42), "old fullscreen hit region must not remain active");
    }
}
