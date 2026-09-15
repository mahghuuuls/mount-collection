package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Comparator;
import java.util.Map;

/** One ordering rule over already client-localized display names. */
final class CollectionOrdering {
    private CollectionOrdering() { }
    static Comparator<CollectionView.Entry> byDisplayName(Map<MountId, String> names) {
        return Comparator.comparing((CollectionView.Entry entry) -> names.get(entry.getId()), String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(CollectionView.Entry::getOrder);
    }
}
