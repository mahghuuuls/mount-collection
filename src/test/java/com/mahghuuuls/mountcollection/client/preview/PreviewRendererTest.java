package com.mahghuuuls.mountcollection.client.preview;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.IntBuffer;
import net.minecraft.crash.CrashReport;
import net.minecraft.util.ReportedException;
import org.junit.jupiter.api.Test;

final class PreviewRendererTest {
    private PreviewSession session() {
        ClientPreviewRegistry registry = new ClientPreviewRegistry(); registry.freeze();
        return new PreviewSession(registry);
    }
    @Test void wrappedFatalCausesEscapeFallback() {
        OutOfMemoryError fatal = new OutOfMemoryError("synthetic; no memory exhaustion");
        ReportedException reported = new ReportedException(new CrashReport("test renderer", fatal));
        RuntimeException outer = new RuntimeException(reported);
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> PreviewRenderer.fallback(session(), outer)));
        ThreadDeath death = new ThreadDeath();
        assertSame(death, assertThrows(ThreadDeath.class, () -> PreviewRenderer.fallback(session(), new RuntimeException(death))));
    }
    @Test void ordinaryFailuresAndCyclicCausesAreRecoverable() {
        assertFalse(PreviewRenderer.fallback(session(), new ReportedException(new CrashReport("test", new IllegalStateException("ordinary")))));
        RuntimeException first = new RuntimeException(), second = new RuntimeException();
        first.initCause(second); second.initCause(first);
        assertFalse(PreviewRenderer.fallback(session(), first));
    }
    @Test void cleanupRestoresBothEnableStatesAndRectangle() {
        FakeScissor state = new FakeScissor();
        IntBuffer saved = IntBuffer.wrap(new int[] {7, 11, 20, 30});
        state.enabled = false;
        PreviewRenderer.restoreScissor(state, true, saved);
        assertTrue(state.enabled, "failing renderer disabled previously enabled clipping");
        assertArrayEquals(new int[] {7, 11, 20, 30}, state.rectangle);
        state.enabled = true;
        PreviewRenderer.restoreScissor(state, false, saved);
        assertFalse(state.enabled);
        assertArrayEquals(new int[] {7, 11, 20, 30}, state.rectangle);
    }
    private static final class FakeScissor implements PreviewRenderer.ScissorState {
        boolean enabled;
        int[] rectangle;
        public void enabled(boolean value) { enabled = value; }
        public void rectangle(int x, int y, int width, int height) { rectangle = new int[] {x, y, width, height}; }
    }
}
