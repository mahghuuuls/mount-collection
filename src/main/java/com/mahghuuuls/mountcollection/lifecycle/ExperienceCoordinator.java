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
        return admit(request, owner, stillCurrent, ignored -> { });
    }

    public boolean admit(UUID request, UUID owner, BooleanSupplier stillCurrent,
            Consumer<ExperienceCompletion> boarding) {
        return admit(request, owner, stillCurrent, boarding, RecoveryAdmission::optOut);
    }

    public boolean admit(UUID request, UUID owner, BooleanSupplier stillCurrent,
            Consumer<ExperienceCompletion> boarding, java.util.function.Supplier<RecoveryAdmission> admission) {
        if (pending.size() >= MAX_PENDING || pending.containsKey(request)) { return false; }
        pending.put(request, new Pending(owner, stillCurrent, boarding, admission));
        return true;
    }

    public RecoveryAdmission recoveryAdmission(UUID request, UUID owner) {
        Pending entry = pending.get(request);
        if (entry == null) { return RecoveryAdmission.expired(); }
        if (!entry.owner.equals(owner)) { return RecoveryAdmission.unavailable(); }
        try {
            if (!entry.current.getAsBoolean()) {
                pending.remove(request);
                return RecoveryAdmission.expired();
            }
            RecoveryAdmission result = entry.admission.get();
            return result == null ? RecoveryAdmission.unavailable() : result;
        } catch (FatalTransferSafetyException fatal) { throw fatal; }
        catch (RuntimeException | LinkageError failure) { return RecoveryAdmission.unavailable(); }
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
            boolean cosmeticFailed = false;
            try { delivery.accept(completion); }
            catch (FatalTransferSafetyException fatal) { throw fatal; }
            catch (RuntimeException | LinkageError cosmeticFailure) {
                cosmeticFailed = true;
                // A cosmetic failure must not prevent the independent boarding action.
            }
            if (completion.getKind() == ExperienceCompletion.Kind.ARRIVED && admitted.current.getAsBoolean()) {
                admitted.boarding.accept(completion);
            }
            diagnostic.accept(detail(cosmeticFailed ? "DELIVERY_FAILED" : "DELIVERY_ATTEMPTED", completion));
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
        private final Consumer<ExperienceCompletion> boarding;
        private final java.util.function.Supplier<RecoveryAdmission> admission;
        private Pending(UUID owner, BooleanSupplier current, Consumer<ExperienceCompletion> boarding,
                java.util.function.Supplier<RecoveryAdmission> admission) {
            this.owner = Objects.requireNonNull(owner);
            this.current = Objects.requireNonNull(current);
            this.boarding = Objects.requireNonNull(boarding);
            this.admission = Objects.requireNonNull(admission);
        }
    }
}
