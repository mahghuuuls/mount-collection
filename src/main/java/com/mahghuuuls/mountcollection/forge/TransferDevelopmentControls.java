package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded one-shot transfer controls available only in a deobfuscated development runtime. */
final class TransferDevelopmentControls {

    enum Fault {
        NONE,
        JOURNAL_ACKNOWLEDGEMENT,
        JOURNAL_FATAL_SEQUENCE,
        PHYSICAL_FENCE_POST_DRAIN
    }

    private final BooleanSupplier developmentEnvironment;
    private Fault fault = Fault.NONE;
    private int faultUsesRemaining;
    private TransferPhase fatalAfterIntent;
    private TransferPhase pausePhase;

    TransferDevelopmentControls() {
        this(net.minecraftforge.fml.relauncher.FMLLaunchHandler::isDeobfuscatedEnvironment);
    }

    TransferDevelopmentControls(BooleanSupplier developmentEnvironment) {
        this.developmentEnvironment = Objects.requireNonNull(
                developmentEnvironment, "developmentEnvironment");
    }

    synchronized boolean isAvailable() {
        return developmentEnvironment.getAsBoolean();
    }

    synchronized boolean armFault(Fault requested) {
        if (!isAvailable()
                || requested == null
                || requested == Fault.NONE
                || requested == Fault.JOURNAL_FATAL_SEQUENCE) {
            return false;
        }
        fault = requested;
        faultUsesRemaining = requested == Fault.JOURNAL_FATAL_SEQUENCE ? 3 : 1;
        return true;
    }

    synchronized boolean armFatalAfterIntent(TransferPhase requested) {
        if (!isAvailable() || requested == null || !requested.isActionIntent()) {
            return false;
        }
        fatalAfterIntent = requested;
        return true;
    }

    synchronized void phaseAcknowledged(TransferPhase phase) {
        if (!isAvailable() || phase != fatalAfterIntent) {
            return;
        }
        fatalAfterIntent = null;
        fault = Fault.JOURNAL_FATAL_SEQUENCE;
        faultUsesRemaining = phase == TransferPhase.CANDIDATE_SPAWN_INTENT ? 3 : 2;
    }

    synchronized boolean armPause(TransferPhase requested) {
        if (!isAvailable()
                || requested == null
                || requested.isActionIntent()
                || requested == TransferPhase.INTEGRITY_BLOCKED) {
            return false;
        }
        pausePhase = requested;
        return true;
    }

    synchronized boolean consumeJournalAcknowledgementFault() {
        if (consumeFault(Fault.JOURNAL_ACKNOWLEDGEMENT)) {
            return true;
        }
        return consumeFault(Fault.JOURNAL_FATAL_SEQUENCE);
    }

    synchronized boolean consumePhysicalFencePostDrainFault() {
        return consumeFault(Fault.PHYSICAL_FENCE_POST_DRAIN);
    }

    synchronized boolean shouldPause(TransferPhase phase) {
        return isAvailable() && pausePhase == phase;
    }

    synchronized void clear() {
        fault = Fault.NONE;
        faultUsesRemaining = 0;
        pausePhase = null;
        fatalAfterIntent = null;
    }

    synchronized String describe() {
        if (!isAvailable()) {
            return "unavailable";
        }
        return "fault=" + fault.name()
                + (faultUsesRemaining > 1 ? " remaining=" + faultUsesRemaining : "")
                + " fatalAfter=" + (fatalAfterIntent == null ? "NONE" : fatalAfterIntent.name())
                + " pause=" + (pausePhase == null ? "NONE" : pausePhase.name());
    }

    private boolean consumeFault(Fault expected) {
        if (!isAvailable() || fault != expected || faultUsesRemaining < 1) {
            return false;
        }
        faultUsesRemaining--;
        if (faultUsesRemaining == 0) {
            fault = Fault.NONE;
        }
        return true;
    }
}
