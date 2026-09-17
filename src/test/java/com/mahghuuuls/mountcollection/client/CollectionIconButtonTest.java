package com.mahghuuuls.mountcollection.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CollectionIconButtonTest {
    @Test void iconKeepsVanillaDisabledInputAndExactHoverBounds() {
        CollectionIconButton button = new CollectionIconButton(6, 40, 60, "action_abandon");
        assertTrue(button.contains(40, 60));
        assertTrue(button.contains(59, 79));
        assertFalse(button.contains(60, 60));
        assertFalse(button.contains(40, 80));
        assertTrue(button.mousePressed(null, 50, 70));
        button.enabled = false;
        assertTrue(button.contains(50, 70), "disabled control still explains itself on hover");
        assertFalse(button.mousePressed(null, 50, 70));
        button.visible = false;
        assertFalse(button.contains(50, 70));
        assertFalse(button.mousePressed(null, 50, 70));
    }
}
