package com.mahghuuuls.mountcollection.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PlacementSearchTest {

    @Test
    void normalCandidatesPrecedeFallbackAndUseDeterministicVerticalOrder() {
        PlacementSearch search = new PlacementSearch();
        List<String> visited = new ArrayList<>();

        Optional<PlacementSearch.Offset> result = search.find(1, 3, (x, y, z) -> {
            visited.add(x + ":" + y + ":" + z);
            return x == -2 && y == 0 && z == -2;
        });

        assertEquals(-2, result.get().getX());
        assertEquals(0, result.get().getY());
        assertEquals(-2, result.get().getZ());
        assertEquals("0:2:0", visited.get(0));
        assertFalse(visited.stream().anyMatch(value -> value.startsWith("-3:")));
    }

    @Test
    void unsafeFixtureExhaustsOnlyConfiguredBound() {
        PlacementSearch search = new PlacementSearch();
        List<Integer> horizontalRings = new ArrayList<>();

        Optional<PlacementSearch.Offset> result = search.find(1, 2, (x, y, z) -> {
            horizontalRings.add(Math.max(Math.abs(x), Math.abs(z)));
            return false;
        });

        assertFalse(result.isPresent());
        assertEquals(2, horizontalRings.stream().mapToInt(Integer::intValue).max().getAsInt());
        assertFalse(horizontalRings.stream().anyMatch(ring -> ring > 2));
    }
}
