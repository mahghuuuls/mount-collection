package com.mahghuuuls.mountcollection.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.RepositoryTestAccess;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway.PhysicalAction;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountLifecycleServiceTest {

    private static final ResourceLocation PROVIDER = new ResourceLocation("mountcollection:vanilla");
    private static final ResourceLocation HORSE = new ResourceLocation("minecraft:horse");

    @Test
    void policyDenialOccursBeforeRepositoryMutation() {
        MountRepository repository = new MountRepository();
        MountLifecycleService service = service(repository, FilterMode.WHITELIST);

        RegistrationOutcome outcome = commit(
                service, UUID.randomUUID(), UUID.randomUUID(), null);

        assertEquals(RegistrationOutcome.Status.DISALLOWED, outcome.getStatus());
        assertEquals(0, repository.getTotalRecordCount());
    }

    @Test
    void uniquenessDenialPrecedesRegistrationFilterDenial() {
        MountRepository repository = new MountRepository();
        MountLifecycleService allowed = service(repository, FilterMode.BLACKLIST);
        MountLifecycleService disallowed = service(repository, FilterMode.WHITELIST);
        UUID owner = UUID.randomUUID();
        UUID physical = UUID.randomUUID();
        RegistrationOutcome first = commit(allowed, owner, physical, null);

        RegistrationOutcome repeat = commit(
                disallowed, owner, physical, first.getMountId().get());

        assertEquals(RegistrationOutcome.Status.ALREADY_REGISTERED, repeat.getStatus());
        assertEquals(1, repository.getTotalRecordCount());
    }

    @Test
    void repetitionAndCrossOwnerClaimsRemainIdempotentAndNonTransferring() {
        MountRepository repository = new MountRepository();
        MountLifecycleService service = service(repository, FilterMode.BLACKLIST);
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID physical = UUID.randomUUID();

        RegistrationOutcome first = commit(service, firstOwner, physical, null);
        RegistrationOutcome repeat = commit(service, firstOwner, physical, first.getMountId().get());
        RegistrationOutcome crossOwner = commit(service, secondOwner, physical, first.getMountId().get());

        assertEquals(RegistrationOutcome.Status.SUCCESS, first.getStatus());
        assertEquals(RegistrationOutcome.Status.ALREADY_REGISTERED, repeat.getStatus());
        assertEquals(RegistrationOutcome.Status.OWNED_BY_OTHER, crossOwner.getStatus());
        assertEquals(1, repository.getTotalRecordCount());
        assertEquals(firstOwner, repository.find(first.getMountId().get()).get().getOwnerId());
        assertEquals(1L, repository.inspectCollection(firstOwner).getRevision());
        assertEquals(0L, repository.inspectCollection(secondOwner).getRevision());
    }

    @Test
    void staleCorroboratingIdentityFailsClosed() {
        MountRepository repository = new MountRepository();
        MountLifecycleService service = service(repository, FilterMode.BLACKLIST);

        RegistrationOutcome outcome = commit(
                service, UUID.randomUUID(), UUID.randomUUID(), MountId.create());

        assertEquals(RegistrationOutcome.Status.INTEGRITY_CONFLICT, outcome.getStatus());
        assertEquals(0, repository.getTotalRecordCount());
    }

    @Test
    void registrationCommitsProviderOwnedPayloadThroughTheLifecycleBoundary() {
        MountRepository repository = new MountRepository();
        MountLifecycleService service = service(repository, FilterMode.BLACKLIST);
        NBTTagCompound data = new NBTTagCompound();
        data.setString("variant", "provider-owned");

        RegistrationOutcome outcome = service.commitVerifiedRegistration(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PROVIDER,
                new RegistrationProfile(
                        HORSE, HORSE.toString(), new ProviderPayload(2, data)),
                UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0, 64.0, 1.0),
                null);

        assertEquals(RegistrationOutcome.Status.SUCCESS, outcome.getStatus());
        assertEquals(2, repository.find(outcome.getMountId().get()).get().getProviderPayloadVersion());
        assertEquals(
                "provider-owned",
                repository.find(outcome.getMountId().get()).get()
                        .getProviderPayload().copyData().getString("variant"));
    }

    @Test
    void registrationPersistsProviderCharacteristicsAsRecallAuthority() {
        MountRepository repository = new MountRepository();
        MountLifecycleService service = service(repository, FilterMode.BLACKLIST);
        MountCharacteristics aquatic =
                new MountCharacteristics(PlacementProfile.WATER, Collections.emptySet());

        RegistrationOutcome outcome = service.commitVerifiedRegistration(
                UUID.randomUUID(), UUID.randomUUID(), PROVIDER,
                new RegistrationProfile(HORSE, HORSE.toString(), aquatic),
                UUID.randomUUID(), new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null);

        assertEquals(RegistrationOutcome.Status.SUCCESS, outcome.getStatus());
        assertEquals(aquatic,
                repository.find(outcome.getMountId().get()).get().getCharacteristics());
    }

    @Test
    void successfulRecallCommitsDestinationAndCooldownOnlyAfterWorldCommit() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        UUID physical = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), physical,
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        ActiveServerClock clock = new ActiveServerClock();
        clock.restore(100L, 0L);
        FakeRecallWorld gateway = new FakeRecallWorld(record, false, true);
        MountLifecycleService service = recallService(repository, clock, gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 0, InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.RECALLED, outcome.getStatus());
        assertEquals(300L, repository.getRecallCooldownDeadline(owner));
        assertEquals(9.0D, repository.find(record.getMountId()).get().getLastKnown().getX());
        assertEquals(1, gateway.commitCalls);
        assertEquals(record.getCharacteristics(), gateway.plannedCharacteristics);
        ContextualOutcome repeated = service.recallVerified(
                UUID.randomUUID(), null, owner, 0, InhibitedStatus.UNAFFECTED, config());
        assertEquals(ContextualOutcome.Status.COOLDOWN, repeated.getStatus());
        assertEquals(1, gateway.commitCalls);
    }

    @Test
    void passengerAndCommitFailureLeaveRecordAndCooldownUnchanged() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        UUID physical = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), physical,
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        ActiveServerClock clock = new ActiveServerClock();
        FakeRecallWorld passenger = new FakeRecallWorld(record, true, true);
        MountLifecycleService passengerService = recallService(repository, clock, passenger);

        assertEquals(ContextualOutcome.Status.PASSENGER_PRESENT,
                passengerService.recallVerified(
                        UUID.randomUUID(), null, owner, 0,
                        InhibitedStatus.UNAFFECTED, config()).getStatus());
        assertEquals(0, passenger.planCalls);
        FakeRecallWorld failing = new FakeRecallWorld(record, false, false);
        MountLifecycleService failingService = recallService(repository, clock, failing);
        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE,
                failingService.recallVerified(
                        UUID.randomUUID(), null, owner, 0,
                        InhibitedStatus.UNAFFECTED, config()).getStatus());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(1.0D, repository.find(record.getMountId()).get().getLastKnown().getX());
    }

    @Test
    void unsafePlacementAndIntegrityLookupNeverReachCommit() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        FakeRecallWorld unsafe = new FakeRecallWorld(record, false, true, false);
        MountLifecycleService service = recallService(repository, new ActiveServerClock(), unsafe);

        assertEquals(ContextualOutcome.Status.NO_SAFE_DESTINATION,
                service.recallVerified(
                        UUID.randomUUID(), null, owner, 0,
                        InhibitedStatus.UNAFFECTED, config()).getStatus());
        assertEquals(0, unsafe.commitCalls);
        unsafe.locateResult = RecallWorldGateway.LocateResult.integrityConflict();
        assertEquals(ContextualOutcome.Status.INTEGRITY_CONFLICT,
                service.recallVerified(
                        UUID.randomUUID(), null, owner, 0,
                        InhibitedStatus.UNAFFECTED, config()).getStatus());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(1.0D, repository.find(record.getMountId()).get().getLastKnown().getX());
    }

    @Test
    void flyingRecallOverrideStopsBeforePlacementMutationOrCooldown() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountCharacteristics flying = new MountCharacteristics(
                PlacementProfile.SOLID_GROUND, EnumSet.of(MountTrait.FLYING));
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null, flying,
                new ProviderPayload(0, new NBTTagCompound()))).getRecord().get();
        FakeRecallWorld gateway = new FakeRecallWorld(record, false, true);
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 0,
                InhibitedStatus.UNAFFECTED, config(true));

        assertEquals(ContextualOutcome.Status.SUMMON_DISALLOWED, outcome.getStatus());
        assertEquals(0, gateway.planCalls);
        assertEquals(0, gateway.commitCalls);
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(record.getLastKnown(),
                repository.find(record.getMountId()).get().getLastKnown());
    }

    @Test
    void successfulCrossDimensionRecallUsesJournalAndChangesPhysicalUuid() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        MountCharacteristics characteristics = new MountCharacteristics(
                PlacementProfile.WATER, EnumSet.of(MountTrait.FLYING));
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), sourceId,
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null,
                characteristics, new ProviderPayload(0, new NBTTagCompound())))
                .getRecord().get();
        ActiveServerClock clock = new ActiveServerClock();
        clock.restore(100L, 0L);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        MountLifecycleService service = recallService(repository, clock, gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1, InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.RECALLED, outcome.getStatus());
        assertEquals(gateway.candidateId,
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(1, repository.find(record.getMountId()).get().getLastKnown().getDimensionId());
        assertEquals(characteristics,
                repository.find(record.getMountId()).get().getCharacteristics());
        assertEquals(300L, repository.getRecallCooldownDeadline(owner));
        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(1, gateway.spawnCalls);
        assertTrue(gateway.spawnObservedPreparedState);
        assertEquals(1, gateway.removeSourceCalls);
        assertEquals(1, gateway.finalizeCalls);
    }

    @Test
    void crossDimensionSpawnFailureRollsBackWithoutCooldownOrAssociationChange() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.spawnAction = RecallWorldGateway.CandidateAction.FAILED;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE, outcome.getStatus());
        assertEquals(record.getPhysicalEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertTrue(repository.getPendingTransfers().isEmpty());
    }

    @Test
    void unexpectedCandidateSpawnExceptionEscalatesWhileIntentRemainsDurable() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.spawnFailure = new IllegalStateException("injected spawn callback failure");
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> service.recallVerified(
                        UUID.randomUUID(), null, owner, 1,
                        InhibitedStatus.UNAFFECTED, config()));

        assertEquals(TransferPhase.CANDIDATE_SPAWN_INTENT, fatal.getPhase());
        assertEquals(TransferPhase.CANDIDATE_SPAWN_INTENT, repository
                .getPendingTransfers().get(0).getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void associatedTransferSurvivesCodecReloadAndFinishesIdempotently() {
        com.mahghuuuls.mountcollection.persistence.MountSavedData data =
                new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
        MountRepository repository = data.getRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        FakeTransferWorld firstGateway = new FakeTransferWorld(record);
        firstGateway.removeSourceAction = PhysicalAction.FAILED_RESTORED;
        MountLifecycleService firstService = recallService(
                repository, new ActiveServerClock(), firstGateway);

        assertEquals(ContextualOutcome.Status.PERSISTENCE_FAILURE,
                firstService.recallVerified(
                        UUID.randomUUID(), null, owner, 1,
                        InhibitedStatus.UNAFFECTED, config()).getStatus());
        TransferOperation pending = repository.getPendingTransfers().get(0);
        assertEquals(TransferPhase.ASSOCIATED, pending.getPhase());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));

        com.mahghuuuls.mountcollection.persistence.MountSavedData restored =
                new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
        restored.readFromNBT(data.writeToNBT(new NBTTagCompound()));
        FakeTransferWorld restartedGateway = new FakeTransferWorld(
                restored.getRepository().find(record.getMountId()).get());
        restartedGateway.sourceId = pending.getSourceEntityId();
        restartedGateway.candidateId = pending.getCandidateEntityId();
        restartedGateway.sourcePresent = true;
        restartedGateway.candidatePresent = true;
        MountLifecycleService restarted = recallService(
                restored.getRepository(), new ActiveServerClock(), restartedGateway);

        restarted.reconcilePendingTransfers();
        restarted.reconcilePendingTransfers();

        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(pending.getCandidateEntityId(), restored.getRepository()
                .find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(pending.getCooldownDeadline(),
                restored.getRepository().getRecallCooldownDeadline(owner));
        assertEquals(1, restartedGateway.removeSourceCalls);
    }

    @Test
    void uncertainSourceRemovalRetainsAndBlocksAcknowledgedIntent() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.removeSourceAction = PhysicalAction.FAILED;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTEGRITY_CONFLICT, outcome.getStatus());
        assertEquals(TransferPhase.INTEGRITY_BLOCKED,
                repository.getPendingTransfers().get(0).getPhase());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(1, gateway.removeSourceCalls);
    }

    @Test
    void preparedFailureInjectionPersistsBeforeCandidateCreation() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.pauseAfter = TransferPhase.PREPARED;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE, outcome.getStatus());
        assertEquals(0, gateway.spawnCalls);
        assertEquals(TransferPhase.PREPARED,
                repository.getPendingTransfers().get(0).getPhase());
        assertEquals(record.getPhysicalEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void providerPreparationCompletesBeforeCandidateIntentAcknowledgement() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        int[] acknowledgements = {0};
        RepositoryTestAccess.setAcknowledgement(
                repository, () -> ++acknowledgements[0] != 2);

        ContextualOutcome outcome = recallService(
                repository, new ActiveServerClock(), gateway)
                .recallVerified(UUID.randomUUID(), null, owner, 1,
                        InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.PERSISTENCE_FAILURE, outcome.getStatus());
        assertEquals(2, acknowledgements[0]);
        assertTrue(gateway.providerPreparedBeforeCaptureReturned);
        assertEquals(0, gateway.spawnCalls);
        assertEquals(TransferPhase.PREPARED,
                repository.getPendingTransfers().get(0).getPhase());
    }

    @Test
    void failedSourceIntentAcknowledgementDoesNotMutateSourceRelationships() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        int[] acknowledgements = {0};
        RepositoryTestAccess.setAcknowledgement(
                repository, () -> ++acknowledgements[0] != 5);

        ContextualOutcome outcome = recallService(
                repository, new ActiveServerClock(), gateway)
                .recallVerified(UUID.randomUUID(), null, owner, 1,
                        InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.PERSISTENCE_FAILURE, outcome.getStatus());
        assertEquals(5, acknowledgements[0]);
        assertEquals(1, gateway.validateSourceRemovalCalls);
        assertEquals(0, gateway.removeSourceCalls);
        assertTrue(gateway.sourceRelationshipsIntact);
        assertEquals(TransferPhase.ASSOCIATED,
                repository.getPendingTransfers().get(0).getPhase());
    }

    @Test
    void armedCurrentPhasePauseBlocksBackgroundReconciliationUntilCleared() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.pauseAfter = TransferPhase.CANDIDATE_SPAWNED;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTERNAL_FAILURE, outcome.getStatus());
        TransferOperation paused = repository.getPendingTransfers().get(0);
        assertEquals(TransferPhase.CANDIDATE_SPAWNED, paused.getPhase());
        long pausedRevision = repository.getStoreRevision();
        int candidateCheckpoints = gateway.candidateCheckpointCalls;
        assertTrue(gateway.sourcePresent);
        assertTrue(gateway.candidatePresent);

        service.reconcilePendingTransfers();

        assertEquals(pausedRevision, repository.getStoreRevision());
        assertEquals(TransferPhase.CANDIDATE_SPAWNED,
                repository.findTransfer(paused.getOperationId()).get().getPhase());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(candidateCheckpoints, gateway.candidateCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(0, gateway.finalizeCalls);
        assertTrue(gateway.sourcePresent);
        assertTrue(gateway.candidatePresent);

        gateway.pauseAfter = null;
        service.reconcilePendingTransfers();

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(gateway.candidateId,
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(1, gateway.removeSourceCalls);
        assertEquals(1, gateway.finalizeCalls);
    }

    @Test
    void targetedRecoveryDoesNotAdvanceAnotherPendingTransfer() {
        MountRepository repository = new MountRepository();
        MountRecord readyRecord = registered(repository, UUID.randomUUID());
        MountRecord unreadyRecord = registered(repository, UUID.randomUUID());
        UUID readyCandidate = UUID.randomUUID();
        UUID unreadyCandidate = UUID.randomUUID();
        TransferOperation ready = transferOperation(readyRecord, readyCandidate);
        TransferOperation unready = transferOperation(unreadyRecord, unreadyCandidate);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(ready));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(ready.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(ready.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(unready));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(unready.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(unready.getOperationId()));
        FakeTransferWorld gateway = new FakeTransferWorld(readyRecord);
        gateway.candidateId = readyCandidate;
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfer(ready.getOperationId());

        assertFalse(repository.findTransfer(ready.getOperationId()).isPresent());
        assertEquals(readyCandidate,
                repository.find(readyRecord.getMountId()).get().getPhysicalEntityId());
        TransferOperation retained = repository.findTransfer(unready.getOperationId()).get();
        assertEquals(TransferPhase.CANDIDATE_SPAWNED, retained.getPhase());
        assertEquals(unready.getSourceEntityId(),
                repository.find(unreadyRecord.getMountId()).get().getPhysicalEntityId());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(unreadyRecord.getMountId()).get().getCondition());
    }

    @Test
    void candidateSpawnedCandidateFirstObservationDoesNotInspectOrMutate() {
        MountRepository repository = new MountRepository();
        MountRecord record = registered(repository, UUID.randomUUID());
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.CANDIDATE_SPAWNED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.candidateCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void candidateSpawnedSourceFirstObservationDoesNotInspectOrMutate() {
        MountRepository repository = new MountRepository();
        MountRecord record = registered(repository, UUID.randomUUID());
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfer(operation.getOperationId(), true, false);

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.CANDIDATE_SPAWNED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.candidateCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void associatedCandidateFirstObservationDoesNotInspectOrMutate() {
        MountRepository repository = new MountRepository();
        MountRecord record = registered(repository, UUID.randomUUID());
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.ASSOCIATED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void associatedSourceFirstObservationDoesNotInspectOrMutate() {
        MountRepository repository = new MountRepository();
        MountRecord record = registered(repository, UUID.randomUUID());
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfer(operation.getOperationId(), true, false);

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.ASSOCIATED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void preparedSourceFirstRecoveryCancelsWithoutInspectingCandidate() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfers();

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(record.getPhysicalEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(1, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.candidateAbsentCheckpointCalls);
    }

    @Test
    void preparedCandidateFirstObservationDoesNotInspectOrMutate() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.PREPARED,
                repository.getPendingTransfers().get(0).getPhase());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(0, gateway.inspectCandidateCalls);
    }

    @Test
    void candidateSpawnIntentAcceptsOneStepAheadCheckpointedCandidateAndConverges() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfers();

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void unavailablePendingOperationBlocksCompetingRecallWithoutCreatingAnotherOperation() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        gateway.evidenceUnavailable = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.OPERATION_IN_PROGRESS, outcome.getStatus());
        assertEquals(1, repository.getPendingTransfers().size());
        assertEquals(0, gateway.spawnCalls);
    }

    @Test
    void availableCandidatePendingOperationBlocksCompetingRecallBeforeReconciliation() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        gateway.candidatePresent = true;
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        long revisionBeforeCompetingRecall = repository.getStoreRevision();
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.OPERATION_IN_PROGRESS, outcome.getStatus());
        assertEquals(revisionBeforeCompetingRecall, repository.getStoreRevision());
        assertEquals(TransferPhase.CANDIDATE_SPAWNED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0, gateway.candidateCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(0, gateway.finalizeCalls);
        assertEquals(0, gateway.spawnCalls);
    }

    @Test
    void candidateIdentityConflictRemainsAnIntegrityOutcome() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.spawnAction = RecallWorldGateway.CandidateAction.CONFLICT;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTEGRITY_CONFLICT, outcome.getStatus());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
    }

    @Test
    void candidateSpawnedRestartCompletesTheExactJournaledTransfer() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfers();

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
        assertEquals(1, gateway.removeSourceCalls);
    }

    @Test
    void sourceRemovedRestartFinalizesWithoutRepeatingDestructiveWork() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markTransferSourceRemoved(operation.getOperationId()));
        gateway.sourcePresent = false;
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfers();
        service.reconcilePendingTransfers();

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(1, gateway.finalizeCalls);
    }

    @Test
    void sourceRemovalIntentTrustsCheckpointedAbsenceAndDoesNotRepeatRemoval() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));
        gateway.sourcePresent = false;
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(1, gateway.sourceAbsentCheckpointCalls);
        assertEquals(1, gateway.finalizeCalls);
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void sourceRemovedPhaseAcceptsFinalizedCandidateOneStepAhead() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markTransferSourceRemoved(operation.getOperationId()));
        gateway.sourcePresent = true;
        gateway.candidatePresent = true;
        gateway.candidateFinalized = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        service.reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(0, gateway.finalizeCalls);
        assertEquals(0, gateway.inspectSourceCalls);
        assertEquals(1, gateway.inspectCandidateCalls);
        assertEquals(0, gateway.sourceAbsentCheckpointCalls);
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void everyDurablePhaseReconcilesAfterProductionCodecReload() {
        for (TransferPhase phase : new TransferPhase[] {
                TransferPhase.PREPARED,
                TransferPhase.CANDIDATE_SPAWN_INTENT,
                TransferPhase.CANDIDATE_SPAWNED,
                TransferPhase.ASSOCIATED,
                TransferPhase.SOURCE_REMOVAL_INTENT,
                TransferPhase.SOURCE_REMOVED}) {
            com.mahghuuuls.mountcollection.persistence.MountSavedData source =
                    new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
            MountRepository sourceRepository = source.getRepository();
            UUID owner = UUID.randomUUID();
            MountRecord record = registered(sourceRepository, owner);
            UUID candidateId = UUID.randomUUID();
            TransferOperation operation = transferOperation(record, candidateId);
            assertEquals(MountRepository.TransferStatus.SUCCESS,
                    sourceRepository.beginTransfer(operation), phase.name());
            if (phase.ordinal() >= TransferPhase.CANDIDATE_SPAWN_INTENT.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        sourceRepository.markCandidateSpawnIntent(operation.getOperationId()), phase.name());
            }
            if (phase.ordinal() >= TransferPhase.CANDIDATE_SPAWNED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        sourceRepository.markCandidateSpawned(operation.getOperationId()), phase.name());
            }
            if (phase.ordinal() >= TransferPhase.ASSOCIATED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        sourceRepository.associateTransferCandidate(operation.getOperationId()), phase.name());
            }
            if (phase.ordinal() >= TransferPhase.SOURCE_REMOVAL_INTENT.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        sourceRepository.markSourceRemovalIntent(operation.getOperationId()), phase.name());
            }
            if (phase.ordinal() >= TransferPhase.SOURCE_REMOVED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        sourceRepository.markTransferSourceRemoved(operation.getOperationId()), phase.name());
            }

            com.mahghuuuls.mountcollection.persistence.MountSavedData restored =
                    new com.mahghuuuls.mountcollection.persistence.MountSavedData("test");
            restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));
            MountRepository repository = restored.getRepository();
            MountRecord restoredRecord = repository.find(record.getMountId()).get();
            TransferOperation restoredOperation = repository
                    .findTransfer(operation.getOperationId()).get();
            FakeTransferWorld gateway = new FakeTransferWorld(restoredRecord);
            gateway.sourceId = operation.getSourceEntityId();
            gateway.candidateId = operation.getCandidateEntityId();
            gateway.sourcePresent = phase != TransferPhase.SOURCE_REMOVED;
            gateway.candidatePresent = phase.ordinal()
                    >= TransferPhase.CANDIDATE_SPAWNED.ordinal();

            recallService(repository, new ActiveServerClock(), gateway)
                    .reconcilePendingTransfers();

            assertTrue(repository.getPendingTransfers().isEmpty(), phase.name());
            assertEquals(
                    phase.ordinal() < TransferPhase.CANDIDATE_SPAWNED.ordinal()
                            ? operation.getSourceEntityId()
                            : operation.getCandidateEntityId(),
                    repository.find(record.getMountId()).get().getPhysicalEntityId(),
                    phase.name());
            assertEquals(
                    phase.ordinal() < TransferPhase.CANDIDATE_SPAWNED.ordinal()
                            ? 0L
                            : restoredOperation.getCooldownDeadline(),
                    repository.getRecallCooldownDeadline(owner),
                    phase.name());
        }
    }

    @Test
    void failedFinalMarkerCheckpointRetainsRetryableOperationWithoutCooldown() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.markerClearSucceeds = false;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        ContextualOutcome outcome = service.recallVerified(
                UUID.randomUUID(), null, owner, 1,
                InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.PERSISTENCE_FAILURE, outcome.getStatus());
        assertEquals(TransferPhase.SOURCE_REMOVED,
                repository.getPendingTransfers().get(0).getPhase());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
    }

    @Test
    void stableCandidateSpawnedPhaseWaitsWhenSourceIsNotYetObservable() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        gateway.sourcePresent = false;
        gateway.candidatePresent = true;
        MountLifecycleService service = recallService(
                repository, new ActiveServerClock(), gateway);

        long revision = repository.getStoreRevision();
        service.reconcilePendingTransfers();

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.CANDIDATE_SPAWNED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(0, gateway.candidateCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void acknowledgedRelocationIsPassedToThePhysicalFence() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        gateway.candidatePresent = true;
        LastKnownEvidence moved = new LastKnownEvidence(0, 35.0D, 70.0D, 36.0D);
        gateway.actualSourceEvidence = moved;

        recallService(repository, new ActiveServerClock(), gateway)
                .reconcilePendingTransfers();

        assertEquals(moved, gateway.checkpointObservedSourceEvidence);
    }

    @Test
    void stableAssociatedPhaseWaitsWhenCandidateIsNotYetObservable() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));

        long revision = repository.getStoreRevision();
        recallService(repository, new ActiveServerClock(), gateway)
                .reconcilePendingTransfers();

        assertEquals(revision, repository.getStoreRevision());
        assertEquals(TransferPhase.ASSOCIATED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(com.mahghuuuls.mountcollection.persistence.MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0, gateway.candidateAbsentCheckpointCalls);
        assertEquals(0, gateway.removeSourceCalls);
    }

    @Test
    void failedCandidateIntentCleanupIsAcknowledgedAsIntegrityBlocked() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = registered(repository, owner);
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        gateway.candidateCheckpointStatus = RecallWorldGateway.CheckpointStatus.FAILED;
        gateway.removeCandidateSucceeds = false;

        ContextualOutcome outcome = recallService(
                repository, new ActiveServerClock(), gateway)
                .recallVerified(UUID.randomUUID(), null, owner, 1,
                        InhibitedStatus.UNAFFECTED, config());

        assertEquals(ContextualOutcome.Status.INTEGRITY_CONFLICT, outcome.getStatus());
        assertEquals(TransferPhase.INTEGRITY_BLOCKED,
                repository.getPendingTransfers().get(0).getPhase());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(1, gateway.removeCandidateCalls);
    }

    @Test
    void failedSourceAbsenceFenceQuarantinesRemovalIntent() {
        MountRepository repository = new MountRepository();
        MountRecord record = registered(repository, UUID.randomUUID());
        FakeTransferWorld gateway = new FakeTransferWorld(record);
        TransferOperation operation = transferOperation(record, gateway.candidateId);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));
        gateway.sourcePresent = false;
        gateway.candidatePresent = true;
        gateway.sourceAbsentCheckpointStatus = RecallWorldGateway.CheckpointStatus.FAILED;

        recallService(repository, new ActiveServerClock(), gateway)
                .reconcilePendingTransfer(operation.getOperationId(), false, true);

        assertEquals(TransferPhase.INTEGRITY_BLOCKED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(0, gateway.removeSourceCalls);
        assertEquals(1, gateway.sourceAbsentCheckpointCalls);
    }

    @Test
    void retryableTransferFailuresRemainDistinctAndEssentiallyWarned() {
        MountRepository unavailableRepository = new MountRepository();
        UUID unavailableOwner = UUID.randomUUID();
        MountRecord unavailableRecord = registered(unavailableRepository, unavailableOwner);
        FakeTransferWorld unavailableGateway = new FakeTransferWorld(unavailableRecord);
        unavailableGateway.spawnAction = RecallWorldGateway.CandidateAction.UNAVAILABLE;
        ContextualOutcome unavailable = recallService(
                unavailableRepository, new ActiveServerClock(), unavailableGateway)
                .recallVerified(UUID.randomUUID(), null, unavailableOwner, 1,
                        InhibitedStatus.UNAFFECTED, config());
        assertEquals(ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE, unavailable.getStatus());
        assertTrue(unavailableRepository.getPendingTransfers().isEmpty());

        MountRepository failedRepository = new MountRepository();
        UUID failedOwner = UUID.randomUUID();
        MountRecord failedRecord = registered(failedRepository, failedOwner);
        FakeTransferWorld failedGateway = new FakeTransferWorld(failedRecord);
        failedGateway.candidateCheckpointStatus = RecallWorldGateway.CheckpointStatus.FAILED;
        CapturingDiagnostics diagnostics = new CapturingDiagnostics();
        ContextualOutcome failed = recallService(
                failedRepository, new ActiveServerClock(), failedGateway, diagnostics)
                .recallVerified(UUID.randomUUID(), null, failedOwner, 1,
                        InhibitedStatus.UNAFFECTED, config());
        assertEquals(ContextualOutcome.Status.PERSISTENCE_FAILURE, failed.getStatus());
        assertTrue(failedRepository.getPendingTransfers().isEmpty());
        assertEquals(1, failedGateway.removeCandidateCalls);
        assertTrue(diagnostics.lifecycleWarnings.contains("transfer_physical_fence_failed"));
    }

    private static MountRecord registered(MountRepository repository, UUID owner) {
        return repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
    }

    private static TransferOperation transferOperation(MountRecord record, UUID candidateId) {
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", HORSE.toString());
        snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
        return new TransferOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(),
                record.getPhysicalEntityId(), candidateId, record.getLastKnown(),
                new LastKnownEvidence(1, 9.0D, 64.0D, 9.0D), snapshot,
                200L, 200L, TransferPhase.PREPARED, null);
    }

    private static RegistrationOutcome commit(
            MountLifecycleService service, UUID owner, UUID physical, MountId claimed) {
        return service.commitVerifiedRegistration(
                UUID.randomUUID(),
                owner,
                PROVIDER,
                new RegistrationProfile(HORSE, HORSE.toString()),
                physical,
                new LastKnownEvidence(0, 1.0, 64.0, 1.0),
                claimed);
    }

    private static MountLifecycleService service(
            MountRepository repository, FilterMode registrationMode) {
        ProviderRegistry providers = new ProviderRegistry();
        providers.freeze();
        ValidatedMountConfig config = new ValidatedMountConfig(
                new ConfiguredFilter<>(registrationMode, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                200L,
                4,
                16,
                true,
                6000L,
                true,
                false);
        return new MountLifecycleService(repository, providers, () -> config, new NoOpDiagnostics());
    }

    private static MountLifecycleService recallService(
            MountRepository repository, ActiveServerClock clock, RecallWorldGateway gateway) {
        return recallService(repository, clock, gateway, new NoOpDiagnostics());
    }

    private static MountLifecycleService recallService(
            MountRepository repository,
            ActiveServerClock clock,
            RecallWorldGateway gateway,
            DiagnosticSink diagnostics) {
        ProviderRegistry providers = new ProviderRegistry();
        providers.register(new TestProvider());
        providers.freeze();
        return new MountLifecycleService(
                repository, providers, MountLifecycleServiceTest::config,
                diagnostics, clock, new InhibitedIntegration(), gateway);
    }

    private static ValidatedMountConfig config() {
        return config(false);
    }

    private static ValidatedMountConfig config(boolean flyingDisabled) {
        return new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                200L, 4, 16, flyingDisabled, true, 6000L, true, false);
    }

    private static final class TestProvider implements MountProvider {
        @Override public ResourceLocation getProviderId() { return PROVIDER; }
        @Override public boolean supports(Entity entity) { return true; }
        @Override public ProviderResult<RegistrationProfile> validateRegistration(
                Entity entity, UUID playerId) {
            return ProviderResult.success(new RegistrationProfile(HORSE, HORSE.toString()));
        }
    }

    private static final class FakeRecallWorld implements RecallWorldGateway {
        private final MountRecord record;
        private final boolean passenger;
        private final boolean commitSucceeds;
        private final boolean planSucceeds;
        private LocateResult locateResult;
        private int planCalls;
        private int commitCalls;
        private MountCharacteristics plannedCharacteristics;

        private FakeRecallWorld(MountRecord record, boolean passenger, boolean commitSucceeds) {
            this(record, passenger, commitSucceeds, true);
        }

        private FakeRecallWorld(
                MountRecord record,
                boolean passenger,
                boolean commitSucceeds,
                boolean planSucceeds) {
            this.record = record;
            this.passenger = passenger;
            this.commitSucceeds = commitSucceeds;
            this.planSucceeds = planSucceeds;
            this.locateResult = LocateResult.found(
                    new Source(record.getPhysicalEntityId(), 0, passenger, null));
        }

        @Override
        public LocateResult locate(net.minecraft.entity.player.EntityPlayerMP player, MountRecord ignored) {
            return locateResult;
        }

        @Override
        public boolean providerSupports(Source source, MountProvider provider) {
            return true;
        }

        @Override
        public Optional<Destination> plan(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                com.mahghuuuls.mountcollection.api.MountCharacteristics characteristics,
                int normalRadius, int fallbackRadius) {
            planCalls++;
            plannedCharacteristics = characteristics;
            return planSucceeds
                    ? Optional.of(new Destination(
                            new LastKnownEvidence(0, 9.0D, 64.0D, 9.0D)))
                    : Optional.empty();
        }

        @Override
        public boolean commit(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                Destination destination, MountProvider provider) {
            commitCalls++;
            return commitSucceeds;
        }
    }

    private static final class FakeTransferWorld implements RecallWorldGateway {
        private final MountRecord record;
        private UUID sourceId;
        private UUID candidateId = UUID.randomUUID();
        private boolean sourcePresent = true;
        private boolean candidatePresent;
        private boolean candidateFinalized;
        private PhysicalAction removeSourceAction = PhysicalAction.SUCCESS;
        private boolean removeCandidateSucceeds = true;
        private boolean markerClearSucceeds = true;
        private boolean evidenceUnavailable;
        private LastKnownEvidence actualSourceEvidence;
        private LastKnownEvidence actualCandidateEvidence;
        private LastKnownEvidence checkpointObservedSourceEvidence;
        private CheckpointStatus candidateAbsentCheckpointStatus = CheckpointStatus.VERIFIED;
        private CheckpointStatus candidateCheckpointStatus = CheckpointStatus.VERIFIED;
        private CheckpointStatus sourceAbsentCheckpointStatus = CheckpointStatus.VERIFIED;
        private CandidateAction spawnAction = CandidateAction.SUCCESS;
        private RuntimeException spawnFailure;
        private TransferPhase pauseAfter;
        private int spawnCalls;
        private int removeSourceCalls;
        private int finalizeCalls;
        private int candidateAbsentCheckpointCalls;
        private int candidateCheckpointCalls;
        private int sourceAbsentCheckpointCalls;
        private int inspectSourceCalls;
        private int inspectCandidateCalls;
        private int validateSourceRemovalCalls;
        private int removeCandidateCalls;
        private boolean sourceRelationshipsIntact = true;
        private boolean providerPreparedBeforeCaptureReturned;
        private boolean spawnObservedPreparedState;

        private FakeTransferWorld(MountRecord record) {
            this.record = record;
            this.sourceId = record.getPhysicalEntityId();
        }

        @Override
        public LocateResult locate(net.minecraft.entity.player.EntityPlayerMP player, MountRecord ignored) {
            return LocateResult.found(new Source(sourceId, 0, false, null));
        }

        @Override
        public boolean providerSupports(Source source, MountProvider provider) { return true; }

        @Override
        public Optional<Destination> plan(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                com.mahghuuuls.mountcollection.api.MountCharacteristics characteristics,
                int normalRadius, int fallbackRadius) {
            return Optional.of(new Destination(new LastKnownEvidence(1, 9.0D, 64.0D, 9.0D)));
        }

        @Override
        public boolean commit(
                net.minecraft.entity.player.EntityPlayerMP player, Source source,
                Destination destination, MountProvider provider) {
            throw new AssertionError("same-dimension commit must not run");
        }

        @Override
        public Optional<TransferPlan> captureTransfer(
                net.minecraft.entity.player.EntityPlayerMP player,
                Source source,
                Destination destination,
                MountProvider provider) {
            providerPreparedBeforeCaptureReturned = true;
            NBTTagCompound snapshot = new NBTTagCompound();
            snapshot.setString("id", HORSE.toString());
            snapshot.setUniqueId("UUID", sourceId);
            return Optional.of(new TransferPlan(
                    candidateId,
                    new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D),
                    snapshot));
        }

        @Override
        public CandidateAction spawnCandidate(TransferOperation operation) {
            spawnCalls++;
            spawnObservedPreparedState = providerPreparedBeforeCaptureReturned;
            if (spawnFailure != null) {
                throw spawnFailure;
            }
            if (spawnAction == CandidateAction.SUCCESS) {
                candidatePresent = true;
            }
            return spawnAction;
        }

        @Override
        public TransferEvidence inspectTransfer(TransferOperation operation) {
            return inspectTransfer(operation, true, true);
        }

        @Override
        public TransferEvidence inspectTransfer(
                TransferOperation operation, boolean inspectSource, boolean inspectCandidate) {
            if (inspectSource) {
                inspectSourceCalls++;
            }
            if (inspectCandidate) {
                inspectCandidateCalls++;
            }
            return new TransferEvidence(
                    !inspectSource
                            ? TransferEvidence.Presence.NOT_INSPECTED
                            : sourcePresent
                                    ? TransferEvidence.Presence.EXACT
                                    : TransferEvidence.Presence.MISSING,
                    !inspectCandidate
                            ? TransferEvidence.Presence.NOT_INSPECTED
                            : evidenceUnavailable
                                    ? TransferEvidence.Presence.UNAVAILABLE
                                    : !candidatePresent
                                            ? TransferEvidence.Presence.MISSING
                                            : candidateFinalized
                                                    ? TransferEvidence.Presence.FINALIZED
                                                    : TransferEvidence.Presence.EXACT,
                    inspectSource ? actualSourceEvidence : null,
                    inspectCandidate ? actualCandidateEvidence : null);
        }

        @Override
        public CheckpointStatus checkpointCandidate(
                TransferOperation operation, boolean operationMarkerExpected) {
            candidateCheckpointCalls++;
            checkpointObservedSourceEvidence = operation.getSourceEvidence();
            if (!candidatePresent) {
                return CheckpointStatus.FAILED;
            }
            if (operationMarkerExpected == candidateFinalized) {
                return CheckpointStatus.FAILED;
            }
            return candidateCheckpointStatus;
        }

        @Override
        public CheckpointStatus checkpointCandidateAbsent(TransferOperation operation) {
            candidateAbsentCheckpointCalls++;
            return candidatePresent ? CheckpointStatus.FAILED : candidateAbsentCheckpointStatus;
        }

        @Override
        public PhysicalAction removeSource(TransferOperation operation) {
            removeSourceCalls++;
            if (removeSourceAction == PhysicalAction.SUCCESS) {
                sourcePresent = false;
                sourceRelationshipsIntact = false;
            }
            return removeSourceAction;
        }

        @Override
        public PhysicalAction validateSourceRemoval(TransferOperation operation) {
            validateSourceRemovalCalls++;
            return PhysicalAction.SUCCESS;
        }

        @Override
        public PhysicalAction removeCandidate(TransferOperation operation) {
            removeCandidateCalls++;
            if (!removeCandidateSucceeds) {
                return PhysicalAction.FAILED;
            }
            candidatePresent = false;
            candidateFinalized = false;
            return PhysicalAction.SUCCESS;
        }

        @Override
        public PhysicalAction clearCandidateOperationMarker(TransferOperation operation) {
            finalizeCalls++;
            if (candidatePresent && markerClearSucceeds) {
                candidateFinalized = true;
                return PhysicalAction.SUCCESS;
            }
            return PhysicalAction.FAILED;
        }

        @Override
        public CheckpointStatus checkpointSourceAbsent(TransferOperation operation) {
            sourceAbsentCheckpointCalls++;
            return sourcePresent ? CheckpointStatus.FAILED : sourceAbsentCheckpointStatus;
        }

        @Override
        public boolean pauseAfterPhase(TransferPhase phase) {
            return phase == pauseAfter;
        }
    }

    private static final class NoOpDiagnostics implements DiagnosticSink {
        @Override
        public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {}

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}

        @Override
        public void essentialLifecycleWarning(String event, String detail) {}
    }

    private static final class CapturingDiagnostics implements DiagnosticSink {
        private final List<String> lifecycleWarnings = new ArrayList<>();

        @Override
        public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {}

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}

        @Override
        public void essentialLifecycleWarning(String event, String detail) {
            lifecycleWarnings.add(event);
        }
    }
}
