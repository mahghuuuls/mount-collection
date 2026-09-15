package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CollectionIngressTest {
    @Test void stalledExecutionBoundsQueueAcrossManyRateIntervalsAndRetainsClose() {
        CollectionIngress gate = new CollectionIngress();
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        CollectionIntent open = new CollectionIntent(CollectionIntent.Action.OPEN, session, 0, null);
        CollectionIntent close = new CollectionIntent(CollectionIntent.Action.CLOSE, session, 0, null);
        List<Runnable> queued = new ArrayList<>();
        List<CollectionIntent.Action> executed = new ArrayList<>();
        gate.dispatch(owner, open, 0, queued::add, intent -> executed.add(intent.getAction()));
        for (int i = 1; i <= 10000; i++) {
            gate.dispatch(owner, open, i * 50000000L, queued::add, intent -> executed.add(intent.getAction()));
        }
        assertEquals(1, queued.size());
        gate.dispatch(owner, new CollectionIntent(CollectionIntent.Action.CLOSE, UUID.randomUUID(), 0, null),
                1, queued::add, intent -> executed.add(intent.getAction()));
        assertEquals(1, queued.size());
        gate.dispatch(owner, close, 2, queued::add, intent -> executed.add(intent.getAction()));
        for (int i = 1; i <= 10000; i++) {
            gate.dispatch(owner, close, i * 50000000L, queued::add, intent -> executed.add(intent.getAction()));
            gate.dispatch(owner, open, i * 50000000L, queued::add, intent -> executed.add(intent.getAction()));
        }
        assertEquals(2, queued.size());
        queued.get(0).run();
        gate.dispatch(owner, open, 999999999999L, queued::add, intent -> executed.add(intent.getAction()));
        assertEquals(2, queued.size(), "pending close still owns its slot");
        queued.get(1).run();
        assertEquals(java.util.Arrays.asList(CollectionIntent.Action.OPEN, CollectionIntent.Action.CLOSE), executed);
        gate.dispatch(owner, open, 999999999999L, queued::add, intent -> executed.add(intent.getAction()));
        assertEquals(3, queued.size());
    }

    @Test void staleTasksCannotActOrReleaseNewGenerationAfterCleanup() {
        CollectionIngress gate = new CollectionIngress();
        UUID owner = UUID.randomUUID();
        CollectionIntent open = new CollectionIntent(CollectionIntent.Action.OPEN, UUID.randomUUID(), 0, null);
        List<Runnable> queued = new ArrayList<>();
        List<CollectionIntent> executed = new ArrayList<>();
        gate.dispatch(owner, open, 0, queued::add, executed::add);
        gate.remove(owner);
        gate.dispatch(owner, open, 1, queued::add, executed::add);
        queued.get(0).run();
        assertEquals(0, executed.size());
        gate.dispatch(owner, open, 1000000000L, queued::add, executed::add);
        assertEquals(2, queued.size(), "old completion must not release newer reservation");
        gate.clear();
        gate.dispatch(owner, open, 2, queued::add, executed::add);
        queued.get(1).run();
        assertEquals(0, executed.size());
        gate.dispatch(owner, open, 1000000000L, queued::add, executed::add);
        assertEquals(3, queued.size());
        queued.get(2).run();
        assertEquals(1, executed.size());
    }

    @Test void completionFailuresAndSchedulerFailuresReleaseButRetainRateLimit() {
        CollectionIngress gate = new CollectionIngress();
        UUID owner = UUID.randomUUID();
        CollectionIntent open = new CollectionIntent(CollectionIntent.Action.OPEN, UUID.randomUUID(), 0, null);
        List<Runnable> queued = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> gate.dispatch(owner, open, 0,
                task -> { throw new IllegalStateException("scheduler rejected"); }, intent -> { }));
        gate.dispatch(owner, open, 1, queued::add, intent -> { });
        assertEquals(0, queued.size());
        gate.dispatch(owner, open, 50000000L, queued::add,
                intent -> { throw new IllegalStateException("action failed"); });
        assertEquals(1, queued.size());
        assertThrows(IllegalStateException.class, queued.get(0)::run);
        gate.dispatch(owner, open, 100000000L, queued::add, intent -> { });
        assertEquals(2, queued.size());
        queued.get(1).run();
        gate.dispatch(owner, open, 100000001L, queued::add, intent -> { });
        assertEquals(2, queued.size());
    }
}
