package com.mahghuuuls.mountcollection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CollectionOrderingTest {
    @Test void localizedNamesAndStableOrderDetermineSortingWithoutSelecting() {
        CollectionView.Entry older = entry(1); CollectionView.Entry newer = entry(2); CollectionView.Entry third = entry(3);
        CollectionView view = new CollectionView(4L, newer.getId(), Arrays.asList(third, newer, older));
        Map<MountId, String> names = new HashMap<>();
        names.put(older.getId(), "Horse"); names.put(newer.getId(), "hORSE"); names.put(third.getId(), "Donkey");
        List<CollectionView.Entry> rows = new ArrayList<>(view.getEntries());
        rows.sort(CollectionOrdering.byDisplayName(names));
        assertEquals(Arrays.asList(third, older, newer), rows);
        names.put(third.getId(), "Zebra");
        rows.sort(CollectionOrdering.byDisplayName(names));
        assertEquals(Arrays.asList(older, newer, third), rows);
        assertEquals(newer.getId(), view.getSelected());
        assertEquals(Arrays.asList(third, newer, older), view.getEntries());
    }
    private CollectionView.Entry entry(int order) {
        return new CollectionView.Entry(MountId.create(), "type", 1, order,
                CollectionView.State.LIVING, 0L, MountCharacteristics.solidGround());
    }
}
