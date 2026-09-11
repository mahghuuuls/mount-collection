package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/** Bounded owner of lifecycle mutations that may enter a physical-action intent. */
final class LifecycleMutationExecutor {

    private static final int DEFAULT_MAX_QUEUED = 256;

    private final DiagnosticSink diagnostics;
    private final int maximumQueued;
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private boolean fatal;
    private boolean draining;
    private FatalTransferSafetyException callbackFailure;

    synchronized void latchCallbackFailure(FatalTransferSafetyException failure) {
        boolean first = callbackFailure == null;
        callbackFailure = failure;
        fatal = true;
        queued.clear();
        if (first) {
            diagnostics.essentialLifecycleWarning("fatal_captured_source_safety",
                    failure.boundedDiagnosticDetail());
        }
    }

    LifecycleMutationExecutor(DiagnosticSink diagnostics) {
        this(diagnostics, DEFAULT_MAX_QUEUED);
    }

    LifecycleMutationExecutor(DiagnosticSink diagnostics, int maximumQueued) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (maximumQueued < 1) {
            throw new IllegalArgumentException("maximumQueued must be positive");
        }
        this.maximumQueued = maximumQueued;
    }

    synchronized boolean enqueue(Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (fatal || queued.size() >= maximumQueued) {
            return false;
        }
        queued.add(mutation);
        return true;
    }

    void drainAtServerTickEnd() {
        final int initialCount;
        synchronized (this) {
            if (callbackFailure != null) {
                throw callbackFailure;
            }
            if (fatal || draining) {
                return;
            }
            draining = true;
            initialCount = queued.size();
        }
        try {
            for (int completed = 0; completed < initialCount; completed++) {
                Runnable mutation;
                synchronized (this) {
                    mutation = queued.poll();
                }
                if (mutation == null) {
                    return;
                }
                try {
                    mutation.run();
                } catch (FatalTransferSafetyException fatalFailure) {
                    synchronized (this) {
                        fatal = true;
                        queued.clear();
                    }
                    diagnostics.essentialLifecycleWarning(
                            "fatal_transfer_persistence_safety",
                            fatalFailure.boundedDiagnosticDetail());
                    throw fatalFailure;
                }
            }
        } finally {
            synchronized (this) {
                draining = false;
            }
        }
    }

    synchronized boolean isFatal() {
        return fatal;
    }

    synchronized int queuedCount() {
        return queued.size();
    }

    synchronized void reset() {
        queued.clear();
        fatal = false;
        draining = false;
        callbackFailure = null;
    }
}
