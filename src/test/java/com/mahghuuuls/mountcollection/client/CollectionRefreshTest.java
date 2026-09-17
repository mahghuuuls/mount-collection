package com.mahghuuuls.mountcollection.client;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.network.CollectionIntent;
import com.mahghuuuls.mountcollection.network.CollectionReply;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CollectionRefreshTest {
    @Test void retryOnlyAppearsAfterFailureAndHidesDuringRetryOrSuccess() {
        CollectionRefresh state = new CollectionRefresh();
        assertFalse(state.canRetry());
        state.begin(false); assertFalse(state.canRetry());
        state.complete(); assertFalse(state.canRetry());
        state.begin(true); assertFalse(state.canRetry());
        state.invalidate(); assertTrue(state.canRetry());
        state.begin(false); assertFalse(state.canRetry());
        state.invalidate(); assertTrue(state.canRetry());
        state.begin(true); state.complete(); assertFalse(state.canRetry());
    }
    @Test void successfulRetryClearsOnlyTransientFailureNotices() {
        for (CollectionReply.Result result : CollectionReply.Result.values()) {
            CollectionRefresh state = ready();
            state.reply(result);
            String message = state.notice();
            state.invalidate(); state.begin(true);
            assertEquals(message, state.notice());
            state.complete();
            boolean transientFailure = result == CollectionReply.Result.UNAVAILABLE
                    || result == CollectionReply.Result.STALE || result == CollectionReply.Result.RATE_LIMITED;
            assertEquals(transientFailure ? "" : message, state.notice(), result.name());
        }
    }
    @Test void localNoticeSurvivesBackgroundButManualRefreshClearsIt() {
        CollectionRefresh state = ready();
        state.reply(CollectionReply.Result.UNAVAILABLE);
        state.notice("mountcollection.gui.result.invalid_name");
        state.begin(true); state.complete();
        assertEquals("mountcollection.gui.result.invalid_name", state.notice());
        state.begin(false); assertEquals("", state.notice());
    }
    @Test void detailHoverRegionsRemainAccessibleAtMinimumScreenSize() {
        CollectionLayout layout = new CollectionLayout(320, 240);
        assertEquals(134, layout.detailWidth());
        assertEquals(6, layout.top + CollectionLayout.STATUS_OFFSET - (layout.renameTop() + 20));
        for (int offset : new int[] {CollectionLayout.STATUS_OFFSET, CollectionLayout.RECOVERY_OFFSET}) {
            assertTrue(layout.inDetailLine(layout.detailLeft(), layout.top + offset, offset));
            assertTrue(layout.inDetailLine(layout.detailLeft() + 133, layout.top + offset + 9, offset));
            assertFalse(layout.inDetailLine(layout.detailLeft() + 134, layout.top + offset, offset));
            assertFalse(layout.inDetailLine(layout.detailLeft(), layout.top + offset + 10, offset));
        }
    }
    private final MountId id = MountId.fromUuid(UUID.randomUUID());
    private CollectionIntent intent(CollectionIntent.Action action) {
        return new CollectionIntent(action, UUID.randomUUID(), 7, id,
                action == CollectionIntent.Action.RENAME ? "Frozen name" : null);
    }
    private CollectionRefresh ready() {
        CollectionRefresh state = new CollectionRefresh(); state.begin(false); state.complete(); return state;
    }
    @Test void initialLoadingIsVisibleAndNotActionable() {
        CollectionRefresh state = new CollectionRefresh(); state.begin(true);
        assertTrue(state.pending()); assertTrue(state.showLoading()); assertFalse(state.actionable());
        assertNull(state.complete()); assertTrue(state.actionable()); assertFalse(state.showLoading());
    }
    @Test void backgroundRefreshDoesNotFlashLoadingOrDisableButtons() {
        CollectionRefresh state = ready(); state.begin(true);
        assertTrue(state.pending()); assertFalse(state.showLoading()); assertTrue(state.actionable());
        assertNull(state.complete()); assertTrue(state.actionable()); assertFalse(state.pending());
    }
    @Test void eachMutationWaitsForSnapshotAndKeepsExactFrozenIntent() {
        for (CollectionIntent.Action action : new CollectionIntent.Action[] {
                CollectionIntent.Action.SELECT, CollectionIntent.Action.RENAME, CollectionIntent.Action.ABANDON}) {
            CollectionRefresh state = ready(); state.begin(true);
            CollectionIntent frozen = intent(action);
            assertFalse(state.submit(frozen)); assertFalse(state.actionable()); assertTrue(state.showLoading());
            assertFalse(state.submit(intent(action)), "cannot replace the queued target");
            assertSame(frozen, state.complete()); assertNull(state.complete(), "only consume once");
        }
    }
    @Test void actionOnCompletedViewCanBeSentImmediately() {
        CollectionRefresh state = ready();
        assertTrue(state.submit(intent(CollectionIntent.Action.SELECT)));
        assertTrue(state.pending()); assertFalse(state.actionable()); assertTrue(state.showLoading());
        assertNull(state.complete());
    }
    @Test void timeoutInvalidOrRejectedStreamCancelsQueuedAction() {
        CollectionRefresh state = ready(); state.begin(true); state.submit(intent(CollectionIntent.Action.ABANDON));
        state.invalidate(); assertFalse(state.pending()); assertFalse(state.actionable());
        state.begin(true); assertTrue(state.showLoading()); assertNull(state.complete());
    }
    @Test void changedRevisionOrMissingTargetNeverReplaysFrozenAction() {
        CollectionIntent frozen = intent(CollectionIntent.Action.ABANDON);
        CollectionView.Entry row = new CollectionView.Entry(id, "minecraft:horse", 1, 1,
                CollectionView.State.LIVING, 0, MountCharacteristics.solidGround());
        assertTrue(CollectionRefresh.stillCurrent(frozen, new CollectionView(7, null, Collections.singletonList(row))));
        assertFalse(CollectionRefresh.stillCurrent(frozen, new CollectionView(8, null, Collections.singletonList(row))));
        assertFalse(CollectionRefresh.stillCurrent(frozen, new CollectionView(7, null, Collections.emptyList())));
    }
}
