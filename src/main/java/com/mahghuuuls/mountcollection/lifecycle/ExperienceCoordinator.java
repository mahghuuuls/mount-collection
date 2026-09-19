package com.mahghuuuls.mountcollection.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Server-thread, bounded live-request eligibility. Nothing here survives a restart. */
public final class ExperienceCoordinator {
    private static final int MAX_PENDING = 1024;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Consumer<ExperienceCompletion> delivery;
    private final Consumer<String> diagnostic;

    public ExperienceCoordinator(Consumer<ExperienceCompletion> delivery, Consumer<String> diagnostic) {
        this.delivery = Objects.requireNonNull(delivery);
        this.diagnostic = Objects.requireNonNull(diagnostic);
    }

    public boolean admit(UUID request, UUID owner, BooleanSupplier stillCurrent) {
        if (pending.size() >= MAX_PENDING || pending.containsKey(request)) { return false; }
        pending.put(request, new Pending(owner, stillCurrent));
        return true;
    }

    public void complete(ExperienceCompletion completion) {
        // Consume before validation/delivery, including reentrant or throwing adapters.
        Pending admitted = pending.remove(completion.getRequestId());
        if (admitted == null) { return; }
        try {
            if (!admitted.owner.equals(completion.getOwnerId()) || !admitted.current.getAsBoolean()) {
                diagnostic.accept(detail("SUPPRESSED_STALE", completion));
                return;
            }
            delivery.accept(completion);
            diagnostic.accept(detail("DELIVERY_ATTEMPTED", completion));
        } catch (FatalTransferSafetyException fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            try { diagnostic.accept(detail("DELIVERY_FAILED", completion)); }
            catch (FatalTransferSafetyException fatal) { throw fatal; }
            catch (RuntimeException ignored) { /* Diagnostics cannot reverse committed gameplay. */ }
        }
    }

    public void cancel(UUID request) { pending.remove(request); }
    private static String detail(String outcome, ExperienceCompletion completion) {
        return outcome + " kind=" + completion.getKind() + " request=" + completion.getRequestId()
                + " owner=" + completion.getOwnerId() + " mount=" + completion.getMountId();
    }
    public void retainPending(java.util.function.Predicate<UUID> operationExists) {
        pending.entrySet().removeIf(entry -> !entry.getValue().current.getAsBoolean()
                || !operationExists.test(entry.getKey()));
    }
    public void removeOwner(UUID owner) { pending.values().removeIf(value -> value.owner.equals(owner)); }
    public void clear() { pending.clear(); }

    private static final class Pending {
        private final UUID owner;
        private final BooleanSupplier current;
        private Pending(UUID owner, BooleanSupplier current) {
            this.owner = Objects.requireNonNull(owner);
            this.current = Objects.requireNonNull(current);
        }
    }
}
