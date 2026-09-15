package com.mahghuuuls.mountcollection.collection;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;

final class MountNamingTest {
    @Test void trimsUnicodeSpacesAndCountsCodePointsNotUtf16Units() {
        String horse = "\ud83d\udc0e";
        String boundary = String.join("", Collections.nCopies(32, horse));
        assertEquals(boundary, MountNaming.normalize("\u2003 " + boundary + "\u00a0"));
        assertEquals(64, boundary.length());
        assertThrows(IllegalArgumentException.class, () -> MountNaming.normalize(boundary + horse));
        assertEquals("", MountNaming.normalize(" \u2003\u00a0"));
        assertEquals("A  B", MountNaming.normalize(" A  B "));
    }
    @Test void rejectsHiddenControlsFormattingAndMalformedUnicodeBeforeTrimming() {
        for (String invalid : new String[] {"\nHorse", "Horse\r", "\tHorse", "Horse\u0000", "Horse\u0085",
                "Horse\u2028", "Horse\u2029", "\u00a7Horse", "\ud800", "\udc00", "\ud800a"}) {
            assertThrows(IllegalArgumentException.class, () -> MountNaming.normalize(invalid));
        }
    }
    @Test void distinguishesUnobservedClearedAndPendingWithoutLosingRevision() {
        MountNaming initial = MountNaming.unobserved();
        assertNull(initial.getCustomName());
        MountNaming named = initial.renamed(" Horse ");
        assertEquals("Horse", named.getCustomName());
        assertEquals(1, named.getRevision());
        assertTrue(named.isPending());
        MountNaming applied = named.applied();
        assertFalse(applied.isPending());
        assertEquals(1, applied.getRevision());
        assertEquals("", applied.renamed("").getCustomName());
        assertThrows(IllegalArgumentException.class, () -> new MountNaming(null, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new MountNaming(" Horse ", 1, true));
        assertThrows(IllegalStateException.class, () -> new MountNaming("Horse", Long.MAX_VALUE, false).renamed("A"));
    }
}
