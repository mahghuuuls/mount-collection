package com.mahghuuuls.mountcollection.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RecoverySupport;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.RestorationOperation;
import com.mahghuuuls.mountcollection.persistence.RestorationPhase;
import com.mahghuuuls.mountcollection.persistence.RepositoryTestAccess;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class RecoveryLifecycleServiceTest {

    @ParameterizedTest
    @EnumSource(value = RecallWorldGateway.CheckpointStatus.class, names = {"FAILED", "UNAVAILABLE"})
    void sourceAbsenceMustBeVerifiedBeforeAnyReplacementIsSpawned(RecallWorldGateway.CheckpointStatus fence) {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.sourceFence = fence;
        MountLifecycleService service = service(repository, new ActiveServerClock(), new TestProvider(false), world, 0);
        service.commitCapturedRecovery(record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0);
        service.recallVerified(UUID.randomUUID(), null, record.getOwnerId(), 0, InhibitedStatus.UNAFFECTED, config(0));
        assertEquals(0, world.spawnCalls);
        assertEquals(RestorationPhase.PREPARED, repository.findRestorationByMount(record.getMountId()).get().getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS, repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.isControlledRecoverySource(record.getMountId(), record.getPhysicalEntityId()));
        assertEquals(ContextualOutcome.Status.OPERATION_IN_PROGRESS,
                service.recallVerified(UUID.randomUUID(), null, record.getOwnerId(), 0,
                        InhibitedStatus.UNAFFECTED, config(0)).getStatus());
        assertEquals(1, world.planCalls);
        world.sourceFence = RecallWorldGateway.CheckpointStatus.VERIFIED;
        service.reconcilePendingRestorations();
        assertEquals(1, world.spawnCalls);
        assertEquals(MountCondition.LIVING, repository.find(record.getMountId()).get().getCondition());
    }

    @ParameterizedTest
    @EnumSource(value = RestorationPhase.class, names = {"CANDIDATE_SPAWNED", "ASSOCIATED"})
    void relocationAcknowledgementPrecedesFencingAndSurvivesReload(RestorationPhase phase) {
        com.mahghuuuls.mountcollection.persistence.MountSavedData saved =
                new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
        MountRepository repository = saved.getRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.pausePhase = phase;
        MountLifecycleService service = service(repository, new ActiveServerClock(), new TestProvider(false), world, 0);
        service.commitCapturedRecovery(record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0);
        service.recallVerified(UUID.randomUUID(), null, record.getOwnerId(), 0, InhibitedStatus.UNAFFECTED, config(0));
        world.pausePhase = null;
        world.actualLocation = new LastKnownEvidence(0, 80.5D, 64, -33.5D);
        RepositoryTestAccess.setAcknowledgement(repository, () -> false);
        long before = repository.getStoreRevision();
        int fences = world.fenceCalls;
        service.reconcilePendingRestorations();
        // Failed commits consume a revision to preserve monotonic acknowledgement identity.
        assertEquals(before + 1, repository.getStoreRevision());
        assertEquals(new LastKnownEvidence(0, 8, 64, 8),
                repository.findRestorationByMount(record.getMountId()).get().getDestinationEvidence());
        assertEquals(fences, world.fenceCalls);
        assertFalse(world.finalized);
        RepositoryTestAccess.setAcknowledgement(repository, () -> true);
        com.mahghuuuls.mountcollection.persistence.MountSavedData reloaded =
                new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
        reloaded.readFromNBT(saved.writeToNBT(new NBTTagCompound()));
        MountRepository restarted = reloaded.getRepository();
        MountLifecycleService resumed = service(restarted, new ActiveServerClock(), new TestProvider(false), world, 0);
        world.present = false;
        resumed.reconcilePendingRestorations();
        assertEquals(phase, restarted.findRestorationByMount(record.getMountId()).get().getPhase());
        assertEquals(1, world.spawnCalls);
        world.present = true;
        resumed.reconcilePendingRestorations();
        assertEquals(MountCondition.LIVING, restarted.find(record.getMountId()).get().getCondition());
        assertEquals(world.actualLocation, restarted.find(record.getMountId()).get().getLastKnown());
        assertEquals(1, world.spawnCalls);
    }

    private static final ResourceLocation PROVIDER =
            new ResourceLocation("mountcollection:test_recovery");
    private static final ResourceLocation HORSE = new ResourceLocation("minecraft:horse");

    @Test
    void successfulCaptureDurablyEntersRecoveryAndDeadlineMakesItReady() {
        MountRepository repository = new MountRepository();
        UUID physical = UUID.randomUUID();
        MountRecord record = register(repository, physical);
        ActiveServerClock clock = new ActiveServerClock();
        clock.restore(10L, 0L);
        repository.updateActiveTick(10L);
        MountLifecycleService service = service(
                repository, clock, new TestProvider(false), null, 5L);

        RecoveryDeathOutcome outcome = service.commitCapturedRecovery(
                record, physical, record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 5L);

        assertEquals(RecoveryDeathOutcome.Status.PROTECTED, outcome.getStatus());
        assertEquals(MountCondition.RECOVERING,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(repository.findByPhysicalEntity(physical).isPresent());
        clock.restore(15L, 0L);
        service.advanceRecoveryDeadlines();
        assertEquals(MountCondition.READY_FOR_RECALL,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals("mountcollection.message.recovery_ready",
                repository.consumePendingNotification(record.getOwnerId()).get());
    }

    @ParameterizedTest
    @EnumSource(CaptureFailure.class)
    void lethalDamageCaptureFailureLeavesNormalDeathUncancelledAndRemovesTracking(
            CaptureFailure failure) {
        Bootstrap.register();
        Entity mount = new EntityBoat(null);
        MountRepository repository = new MountRepository();
        UUID physical = mount.getUniqueID();
        MountRecord record = register(repository, physical);
        TestProvider provider = new TestProvider(true);
        provider.captureFailure = failure;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), provider, null, 5L);

        RecoveryDeathOutcome outcome = service.handleLethalDamage(mount);

        assertEquals(1, provider.captureCalls);
        assertEquals(RecoveryDeathOutcome.Status.NORMAL_DEATH_CAPTURE_FAILED,
                outcome.getStatus());
        assertFalse(outcome.shouldSuppressDeath());
        assertFalse(repository.find(record.getMountId()).isPresent());
        assertFalse(repository.findByPhysicalEntity(physical).isPresent());
        assertFalse(repository.inspectCollection(record.getOwnerId())
                .getSelectedMountId().isPresent());
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertEquals("mountcollection.message.recovery_unavailable",
                repository.consumePendingNotification(record.getOwnerId()).get());
    }

    @Test
    void lethalDamageSuccessfulCaptureProtectsAndRetainsRecoverySnapshot() {
        Bootstrap.register();
        Entity mount = new EntityBoat(null);
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, mount.getUniqueID());
        TestProvider provider = new TestProvider(false);
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), provider, null, 5L);

        RecoveryDeathOutcome outcome = service.handleLethalDamage(mount);

        assertEquals(1, provider.captureCalls);
        assertEquals(RecoveryDeathOutcome.Status.PROTECTED, outcome.getStatus());
        assertTrue(outcome.shouldSuppressDeath());
        assertEquals(MountCondition.RECOVERING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.find(record.getMountId()).get().getRecoveryState() != null);
        assertFalse(repository.findByPhysicalEntity(mount.getUniqueID()).isPresent());
    }

    @Test
    void explicitReadyRecallCompletesTheRestorationJournalExactlyOnce() {
        MountRepository repository = new MountRepository();
        UUID physical = UUID.randomUUID();
        MountRecord record = register(repository, physical);
        ActiveServerClock clock = new ActiveServerClock();
        TestProvider provider = new TestProvider(false);
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        MountLifecycleService service = service(repository, clock, provider, world, 0L);
        java.util.List<ExperienceCompletion> effects = new java.util.ArrayList<>();
        service.setCompletionSink(event -> {
            assertTrue(repository.getPendingRestorations().isEmpty());
            assertEquals(world.candidateId, event.getEntityId());
            effects.add(event);
        });
        assertEquals(RecoveryDeathOutcome.Status.PROTECTED,
                service.commitCapturedRecovery(
                        record, physical, record.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0L).getStatus());

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(ContextualOutcome.Status.RECALLED, outcome.getStatus());
        MountRecord restored = repository.find(record.getMountId()).get();
        assertEquals(MountCondition.LIVING, restored.getCondition());
        assertEquals(world.candidateId, restored.getPhysicalEntityId());
        assertTrue(restored.getRecoveryState() == null);
        assertEquals(1, effects.size());
        service.reconcilePendingRestorations();
        assertEquals(1, effects.size());
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertEquals(1, world.spawnCalls);
    }

    @ParameterizedTest
    @EnumSource(value = RecallWorldGateway.CheckpointStatus.class, names = {"UNAVAILABLE", "FAILED"})
    void compensatedCheckpointFailureRequiresFreshRecall(RecallWorldGateway.CheckpointStatus failure) {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.checkpointOverride = failure;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        assertEquals(RecoveryDeathOutcome.Status.PROTECTED,
                service.commitCapturedRecovery(
                        record, record.getPhysicalEntityId(), record.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0L).getStatus());

        UUID failedOperation = UUID.randomUUID();
        ContextualOutcome first = service.recallVerified(
                failedOperation, null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(failure == RecallWorldGateway.CheckpointStatus.UNAVAILABLE
                ? ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE
                : ContextualOutcome.Status.PERSISTENCE_FAILURE, first.getStatus());
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertEquals(MountCondition.READY_FOR_RECALL,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(world.present);

        world.checkpointOverride = null;
        long revision = repository.getStoreRevision();
        for (int attempt = 0; attempt < 3; attempt++) {
            service.reconcilePendingRestoration(failedOperation);
            service.reconcilePendingRestorations();
            assertEquals(revision, repository.getStoreRevision());
            assertFalse(world.present);
            assertEquals(1, world.spawnCalls);
            assertEquals(0L, repository.getRecallCooldown(record.getOwnerId()).getDeadline());
            assertTrue(repository.find(record.getMountId()).get().getRecoveryState() != null);
        }
        ContextualOutcome retry = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));
        assertEquals(ContextualOutcome.Status.RECALLED, retry.getStatus());
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(world.present);
        assertEquals(2, world.spawnCalls);
    }

    @Test
    void missingStableCandidateWaitsForItsActualChunkInsteadOfQuarantining() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.scriptedPresence.add(RecallWorldGateway.TransferEvidence.Presence.MISSING);
        world.scriptedPresence.add(RecallWorldGateway.TransferEvidence.Presence.MISSING);
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(
                record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE, outcome.getStatus());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(1, world.spawnCalls);
        assertEquals(0L, repository.getRecallCooldown(record.getOwnerId()).getDeadline());
        service.reconcilePendingRestorations();
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(1, world.spawnCalls);
    }

    @Test
    void unavailableMarkerCleanupRemainsAssociatedAndRetryable() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.markerAction = RecallWorldGateway.PhysicalAction.UNAVAILABLE;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(
                record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);

        ContextualOutcome first = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE, first.getStatus());
        RestorationOperation pending = repository.findRestorationByMount(record.getMountId()).get();
        assertEquals(com.mahghuuuls.mountcollection.persistence.RestorationPhase.ASSOCIATED,
                pending.getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());

        world.markerAction = RecallWorldGateway.PhysicalAction.SUCCESS;
        service.reconcilePendingRestoration(pending.getOperationId());
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
    }

    @Test
    void failedIntentRollbackAndFailedQuarantineEscalateFatally() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.checkpointOverride = RecallWorldGateway.CheckpointStatus.UNAVAILABLE;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(
                record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);
        int[] acknowledgements = {0};
        RepositoryTestAccess.setAcknowledgement(
                repository, () -> ++acknowledgements[0] < 3);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> service.recallVerified(
                        UUID.randomUUID(), null, record.getOwnerId(), 0,
                        InhibitedStatus.UNAFFECTED, config(0L)));

        assertEquals(RestorationPhase.CANDIDATE_SPAWN_INTENT,
                fatal.getRestorationPhase());
        assertEquals(RestorationPhase.CANDIDATE_SPAWN_INTENT,
                repository.findRestorationByMount(record.getMountId()).get().getPhase());
        assertEquals(4, acknowledgements[0]);
    }

    @Test
    void restorationNeverYieldsAnActionIntentEvenIfTheGatewayRequestsAPause() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.pausePhase = RestorationPhase.CANDIDATE_SPAWN_INTENT;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(
                record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(ContextualOutcome.Status.RECALLED, outcome.getStatus());
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertTrue(world.finalized);
        assertEquals(1, world.spawnCalls);
    }

    @ParameterizedTest
    @EnumSource(value = RestorationPhase.class,
            names = {"PREPARED", "CANDIDATE_SPAWNED", "ASSOCIATED"})
    void stablePauseHoldsDuringTargetedReconciliationUntilCleared(RestorationPhase phase) {
        assertStablePauseHolds(phase, false);
    }

    @ParameterizedTest
    @EnumSource(value = RestorationPhase.class,
            names = {"PREPARED", "CANDIDATE_SPAWNED", "ASSOCIATED"})
    void stablePauseHoldsDuringBulkReconciliationUntilCleared(RestorationPhase phase) {
        assertStablePauseHolds(phase, true);
    }

    private void assertStablePauseHolds(RestorationPhase phase, boolean bulk) {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.pausePhase = phase;
        MountLifecycleService service = service(
                repository, new ActiveServerClock(), new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(
                record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);

        ContextualOutcome paused = service.recallVerified(
                UUID.randomUUID(), null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));

        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE, paused.getStatus());
        RestorationOperation pending = repository.findRestorationByMount(
                record.getMountId()).get();
        assertEquals(phase, pending.getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());

        long pausedRevision = repository.getStoreRevision();
        int expectedSpawns = phase == RestorationPhase.PREPARED ? 0 : 1;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (bulk) {
                service.reconcilePendingRestorations();
            } else {
                service.reconcilePendingRestoration(pending.getOperationId());
            }
            assertEquals(pausedRevision, repository.getStoreRevision(), phase.toString());
            assertEquals(phase, repository.findRestoration(
                    pending.getOperationId()).get().getPhase());
            assertEquals(expectedSpawns, world.spawnCalls);
            assertEquals(expectedSpawns == 1, world.present);
            assertFalse(world.finalized);
        }

        world.pausePhase = null;
        if (bulk) {
            service.reconcilePendingRestorations();
        } else {
            service.reconcilePendingRestoration(pending.getOperationId());
        }
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertTrue(world.present);
        assertTrue(world.finalized);
        assertEquals(1, world.spawnCalls);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void pendingRestorationCompletionUsesOriginalLiveEligibility(boolean stillCurrent) {
        MountRepository repository = new MountRepository();
        MountRecord record = register(repository, UUID.randomUUID());
        FakeRecoveryWorld world = new FakeRecoveryWorld();
        world.pausePhase = RestorationPhase.CANDIDATE_SPAWNED;
        MountLifecycleService service = service(repository, new ActiveServerClock(),
                new TestProvider(false), world, 0L);
        service.commitCapturedRecovery(record, record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(1, new NBTTagCompound()), 0L);
        UUID request = UUID.randomUUID();
        boolean[] current = {true};
        java.util.List<ExperienceCompletion> effects = new java.util.ArrayList<>();
        ExperienceCoordinator coordinator = new ExperienceCoordinator(effects::add, ignored -> {});
        coordinator.admit(request, record.getOwnerId(), () -> current[0]);
        service.setCompletionSink(coordinator::complete);
        service.recallVerified(request, null, record.getOwnerId(), 0,
                InhibitedStatus.UNAFFECTED, config(0L));
        assertTrue(repository.findRestoration(request).isPresent());
        coordinator.retainPending(id -> repository.findRestoration(id).isPresent());
        service.reconcilePendingRestorations();
        assertTrue(effects.isEmpty());
        current[0] = stillCurrent;
        world.pausePhase = null;
        service.reconcilePendingRestorations();
        service.reconcilePendingRestorations();
        assertTrue(repository.getPendingRestorations().isEmpty());
        assertEquals(MountCondition.LIVING, repository.find(record.getMountId()).get().getCondition());
        assertTrue(world.finalized);
        assertEquals(1, world.spawnCalls);
        assertEquals(stillCurrent ? 1 : 0, effects.size());
        if (stillCurrent) {
            assertEquals(request, effects.get(0).getRequestId());
            coordinator.complete(effects.get(0));
            assertEquals(1, effects.size());
        }
    }

    private static MountLifecycleService service(
            MountRepository repository,
            ActiveServerClock clock,
            TestProvider provider,
            RecallWorldGateway gateway,
            long recoveryTicks) {
        ProviderRegistry providers = new ProviderRegistry();
        providers.register(provider);
        providers.freeze();
        return new MountLifecycleService(
                repository, providers, () -> config(recoveryTicks), new NoOpDiagnostics(),
                clock, new InhibitedIntegration(), gateway);
    }

    private static ValidatedMountConfig config(long recoveryTicks) {
        return new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                20L, 4, 16, false, true, recoveryTicks, true, false);
    }

    private static MountRecord register(MountRepository repository, UUID physical) {
        UUID owner = UUID.randomUUID();
        return repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), physical,
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
    }

    private enum CaptureFailure { RETURNED_FAILURE, NULL_RESULT, EMPTY_SUCCESS, EXCEPTION }

    private static final class TestProvider implements MountProvider, RecoverySupport {
        private final boolean failCapture;
        private CaptureFailure captureFailure = CaptureFailure.RETURNED_FAILURE;
        private int captureCalls;
        private TestProvider(boolean failCapture) { this.failCapture = failCapture; }
        @Override public ResourceLocation getProviderId() { return PROVIDER; }
        @Override public boolean supports(Entity entity) { return true; }
        @Override public ProviderResult<RegistrationProfile> validateRegistration(
                Entity entity, UUID playerId) {
            return ProviderResult.success(new RegistrationProfile(HORSE, HORSE.toString()));
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public ProviderResult<ProviderPayload> capturePersistentState(Entity mount) {
            captureCalls++;
            if (failCapture) {
                switch (captureFailure) {
                    case NULL_RESULT: return null;
                    // Simulate an ill-typed external provider returning a valueless success.
                    case EMPTY_SUCCESS: return (ProviderResult) ProviderResult.success();
                    case EXCEPTION: throw new IllegalStateException("capture failed");
                    default: break;
                }
            }
            return failCapture
                    ? ProviderResult.failure(ProviderFailure.INTERNAL_ERROR)
                    : ProviderResult.success(new ProviderPayload(1, new NBTTagCompound()));
        }
        @Override public ProviderResult<Void> applyPersistentState(
                Entity mount, ProviderPayload payload) {
            return ProviderResult.success();
        }
    }

    private static final class FakeRecoveryWorld implements RecallWorldGateway {
        private CheckpointStatus sourceFence = CheckpointStatus.VERIFIED;
        private LastKnownEvidence actualLocation;
        private int fenceCalls;
        private int planCalls;
        @Override public RecoveryEvidence recoverySourceEvidence(MountRecord record) {
            return new RecoveryEvidence(TransferEvidence.Presence.MISSING, null);
        }
        @Override public CheckpointStatus retireRecoverySource(MountRecord record) {
            return sourceFence;
        }
        @Override public RecoveryEvidence recoveryCandidateEvidence(RestorationOperation operation, MountRecord record) {
            return new RecoveryEvidence(inspectRecoveryCandidate(operation, record),
                    actualLocation == null ? operation.getDestinationEvidence() : actualLocation);
        }
        private final UUID candidateId = UUID.randomUUID();
        private boolean present;
        private boolean finalized;
        private int spawnCalls;
        private CheckpointStatus checkpointOverride;
        private PhysicalAction markerAction = PhysicalAction.SUCCESS;
        private RestorationPhase pausePhase;
        private final Deque<TransferEvidence.Presence> scriptedPresence = new ArrayDeque<>();
        @Override public LocateResult locate(
                net.minecraft.entity.player.EntityPlayerMP player, MountRecord record) {
            return LocateResult.missing();
        }
        @Override public boolean providerSupports(Source source, MountProvider provider) {
            return true;
        }
        @Override public Optional<Destination> plan(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                com.mahghuuuls.mountcollection.api.MountCharacteristics characteristics,
                int normalRadius, int fallbackRadius) {
            return Optional.empty();
        }
        @Override public boolean commit(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                Destination destination, MountProvider provider) {
            return false;
        }
        @Override public Optional<RecoveryPlan> planRecovery(
                net.minecraft.entity.player.EntityPlayerMP player, MountRecord record,
                MountProvider provider, int normalRadius, int fallbackRadius) {
            planCalls++;
            return Optional.of(new RecoveryPlan(candidateId,
                    new Destination(new LastKnownEvidence(0, 8.0D, 64.0D, 8.0D))));
        }
        @Override public CandidateAction spawnRecoveryCandidate(
                RestorationOperation operation, MountRecord record, MountProvider provider) {
            spawnCalls++;
            present = true;
            return CandidateAction.SUCCESS;
        }
        @Override public TransferEvidence.Presence inspectRecoveryCandidate(
                RestorationOperation operation, MountRecord record) {
            if (!scriptedPresence.isEmpty()) {
                return scriptedPresence.removeFirst();
            }
            return !present ? TransferEvidence.Presence.MISSING
                    : finalized ? TransferEvidence.Presence.FINALIZED
                    : TransferEvidence.Presence.EXACT;
        }
        @Override public CheckpointStatus checkpointRecoveryCandidate(
                RestorationOperation operation, MountRecord record, boolean markerExpected) {
            fenceCalls++;
            if (actualLocation != null) { assertEquals(actualLocation, operation.getDestinationEvidence()); }
            if (checkpointOverride != null) {
                return checkpointOverride;
            }
            return present && markerExpected != finalized
                    ? CheckpointStatus.VERIFIED : CheckpointStatus.FAILED;
        }
        @Override public PhysicalAction removeRecoveryCandidate(
                RestorationOperation operation, MountRecord record) {
            present = false;
            return PhysicalAction.SUCCESS;
        }
        @Override public CheckpointStatus checkpointRecoveryCandidateAbsent(
                RestorationOperation operation, MountRecord record) {
            return present ? CheckpointStatus.FAILED : CheckpointStatus.VERIFIED;
        }
        @Override public PhysicalAction clearRecoveryOperationMarker(
                RestorationOperation operation) {
            if (markerAction != PhysicalAction.SUCCESS) {
                return markerAction;
            }
            finalized = true;
            return PhysicalAction.SUCCESS;
        }
        @Override public boolean pauseAfterRestorationPhase(RestorationPhase phase) {
            return phase == pausePhase;
        }
    }

    private static final class NoOpDiagnostics implements DiagnosticSink {
        @Override public void detail(
                DiagnosticCategory category, String event, Map<String, String> fields) {}
        @Override public void essentialWarning(
                String category, String rejectedValue, String fallback) {}
        @Override public void essentialLifecycleWarning(String event, String detail) {}
    }
}
