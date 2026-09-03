package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountRepositoryTest {

    private static final ResourceLocation PROVIDER = new ResourceLocation("mountcollection:vanilla");
    private static final ResourceLocation HORSE = new ResourceLocation("minecraft:horse");

    @Test
    void registrationOwnsGlobalIdentityIndexesSelectionOrdinalsAndRevisions() {
        MountRepository repository = new MountRepository();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();

        MountRecord first = register(repository, firstOwner, UUID.randomUUID(), null).getRecord().get();
        MountRecord second = register(repository, firstOwner, UUID.randomUUID(), null).getRecord().get();
        MountRecord other = register(repository, secondOwner, UUID.randomUUID(), null).getRecord().get();

        assertFalse(first.getMountId().equals(second.getMountId()));
        assertEquals(1, first.getFallbackOrdinal());
        assertEquals(2, second.getFallbackOrdinal());
        assertEquals(1, other.getFallbackOrdinal());
        assertEquals(1L, first.getRegistrationOrder());
        assertEquals(2L, second.getRegistrationOrder());
        assertEquals(3L, other.getRegistrationOrder());
        assertEquals(second.getMountId(), repository.inspectCollection(firstOwner).getSelectedMountId().get());
        assertEquals(2L, repository.inspectCollection(firstOwner).getRevision());
        assertEquals(1L, repository.inspectCollection(secondOwner).getRevision());
        assertEquals(2, repository.getOwnedRecords(firstOwner).size());
        assertEquals(1, repository.getOwnedRecords(secondOwner).size());
    }

    @Test
    void duplicateAndCrossOwnerPhysicalClaimsNeverTransferOwnership() {
        MountRepository repository = new MountRepository();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID physicalId = UUID.randomUUID();
        MountRecord registered = register(repository, firstOwner, physicalId, null).getRecord().get();

        assertEquals(
                MountRepository.RegistrationStatus.DUPLICATE_ENTITY,
                register(repository, firstOwner, physicalId, null).getStatus());
        assertEquals(
                MountRepository.RegistrationStatus.OWNED_BY_OTHER,
                register(repository, secondOwner, physicalId, null).getStatus());
        assertEquals(
                MountRepository.RegistrationStatus.INTEGRITY_CONFLICT,
                register(repository, secondOwner, UUID.randomUUID(), registered.getMountId()).getStatus());
        assertEquals(1, repository.getTotalRecordCount());
        assertEquals(firstOwner, repository.find(registered.getMountId()).get().getOwnerId());
        assertEquals(MountCondition.INTEGRITY_BLOCKED, repository.find(registered.getMountId()).get().getCondition());
    }

    @Test
    void unknownEntityEvidenceFailsClosedWithoutCreatingARecord() {
        MountRepository repository = new MountRepository();

        MountRepository.RegistrationResult result = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), MountId.create());

        assertEquals(MountRepository.RegistrationStatus.INTEGRITY_CONFLICT, result.getStatus());
        assertEquals(0, repository.getTotalRecordCount());
    }

    @Test
    void copiedMountIdOnDifferentPhysicalEntityQuarantinesTheRecord() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();

        MountRepository.RegistrationResult result = register(
                repository, owner, UUID.randomUUID(), record.getMountId());

        assertEquals(MountRepository.RegistrationStatus.INTEGRITY_CONFLICT, result.getStatus());
        assertEquals(
                MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
    }

    @Test
    void reconciliationReattachesExactEvidenceAndBlocksContradictions() {
        MountRepository repository = new MountRepository();
        UUID physicalId = UUID.randomUUID();
        MountRecord record = register(repository, UUID.randomUUID(), physicalId, null).getRecord().get();
        LastKnownEvidence moved = new LastKnownEvidence(0, 20.0, 70.0, -4.0);

        assertEquals(
                MountRepository.ReconciliationStatus.REATTACH_REQUIRED,
                repository.reconcile(physicalId, null, moved));
        assertEquals(moved, repository.find(record.getMountId()).get().getLastKnown());
        assertEquals(
                MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT,
                repository.reconcile(UUID.randomUUID(), record.getMountId(), moved));
        assertEquals(
                MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(repository.findByPhysicalEntity(physicalId).isPresent());
    }

    @Test
    void reconciliationQuarantinesBothKnownRecordsWhenPhysicalAndCarriedIdsDisagree() {
        MountRepository repository = new MountRepository();
        MountRecord first = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        MountRecord second = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();

        assertEquals(
                MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT,
                repository.reconcile(
                        second.getPhysicalEntityId(), first.getMountId(), second.getLastKnown()));
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(first.getMountId()).get().getCondition());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(second.getMountId()).get().getCondition());
        assertFalse(repository.findByPhysicalEntity(second.getPhysicalEntityId()).isPresent());
    }

    @Test
    void reconciliationQuarantinesMappedRecordWhenItCarriesUnknownValidId() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();

        assertEquals(
                MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT,
                repository.reconcile(record.getPhysicalEntityId(), MountId.create(), record.getLastKnown()));
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(repository.findByPhysicalEntity(record.getPhysicalEntityId()).isPresent());
    }

    @Test
    void malformedCorroborationBlocksOnlyTheExactPhysicalAssociation() {
        MountRepository repository = new MountRepository();
        UUID firstPhysical = UUID.randomUUID();
        UUID secondPhysical = UUID.randomUUID();
        MountRecord first = register(repository, UUID.randomUUID(), firstPhysical, null).getRecord().get();
        MountRecord second = register(repository, UUID.randomUUID(), secondPhysical, null).getRecord().get();

        assertEquals(
                MountRepository.ReconciliationStatus.INTEGRITY_CONFLICT,
                repository.reportMalformedEvidence(firstPhysical));
        assertEquals(MountCondition.INTEGRITY_BLOCKED, repository.find(first.getMountId()).get().getCondition());
        assertEquals(MountCondition.LIVING, repository.find(second.getMountId()).get().getCondition());
        assertTrue(repository.findByPhysicalEntity(secondPhysical).isPresent());
    }

    @Test
    void preparedRecallHasNonRejectingOneUseFinalization() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        LastKnownEvidence destination = new LastKnownEvidence(0, 9.0D, 64.0D, 9.0D);

        MountRepository.RecallCommit commit = repository.prepareRecall(
                owner, record.getMountId(), record.getPhysicalEntityId()).get();
        commit.complete(destination, 300L, 200L);

        assertEquals(destination, repository.find(record.getMountId()).get().getLastKnown());
        assertEquals(300L, repository.getRecallCooldown(owner).getDeadline());
        assertEquals(200L, repository.getRecallCooldown(owner).getDuration());
    }

    @Test
    void activeTimeRebaseExpiresOldDeadlinesAtomically() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        repository.commitRecall(
                owner, record.getMountId(), record.getPhysicalEntityId(),
                record.getLastKnown(), Long.MAX_VALUE, 200L);

        repository.rebaseActiveTime(0L);

        assertEquals(0L, repository.getActiveTick());
        assertEquals(0L, repository.getRecallCooldown(owner).getDeadline());
        assertEquals(0L, repository.getRecallCooldown(owner).getDuration());
    }

    @Test
    void anomalousFutureDeadlineIsNormalizedOnceAndCanCountDown() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        repository.commitRecall(
                owner, record.getMountId(), record.getPhysicalEntityId(),
                record.getLastKnown(), 1_000_000L, 200L);

        assertTrue(repository.normalizeRecallCooldown(owner, 10L, 200L));
        assertEquals(210L, repository.getRecallCooldown(owner).getDeadline());
        assertFalse(repository.normalizeRecallCooldown(owner, 11L, 200L));
    }

    @Test
    void journaledTransferAdvancesDurablyAndChangesAssociationBeforeCooldown() {
        MountRepository repository = new MountRepository();
        int[] barriers = {0};
        repository.setAcknowledgedPersistence(snapshot -> {
            barriers[0]++;
            return true;
        });
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);

        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(repository.prepareRecall(
                owner, record.getMountId(), record.getPhysicalEntityId()).isPresent());
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertFalse(repository.findByPhysicalEntity(record.getPhysicalEntityId()).isPresent());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markTransferSourceRemoved(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.finishTransfer(operation.getOperationId()));

        assertTrue(repository.getPendingTransfers().isEmpty());
        assertEquals(operation.getCooldownDeadline(), repository.getRecallCooldownDeadline(owner));
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertEquals(7, barriers[0]);
    }

    @Test
    void failedPhaseAcknowledgementRollsBackThePhaseButKeepsOperationExclusive() {
        MountRepository repository = new MountRepository();
        int[] commits = {0};
        repository.setAcknowledgedPersistence(snapshot -> ++commits[0] == 1);
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);

        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                repository.markCandidateSpawnIntent(operation.getOperationId()));

        assertEquals(TransferPhase.PREPARED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                repository.find(record.getMountId()).get().getCondition());
        assertFalse(repository.prepareRecall(
                owner, record.getMountId(), record.getPhysicalEntityId()).isPresent());
        assertEquals(2L, repository.getStoreRevision());
    }

    @Test
    void preAssociationTransferCanRollBackWithoutChangingIdentityOrCooldown() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                repository, owner, UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);

        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.cancelTransfer(operation.getOperationId()));

        assertEquals(record.getPhysicalEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(0L, repository.getRecallCooldownDeadline(owner));
        assertTrue(repository.getPendingTransfers().isEmpty());
    }

    @Test
    void changedAssociationQuarantinesTransferInsteadOfGuessing() {
        MountRepository repository = new MountRepository();
        MountRecord first = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        MountRecord second = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", HORSE.toString());
        TransferOperation operation = new TransferOperation(
                UUID.randomUUID(), first.getMountId(), first.getOwnerId(),
                first.getPhysicalEntityId(), second.getPhysicalEntityId(),
                first.getLastKnown(), new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                snapshot, 200L, 200L, TransferPhase.PREPARED, null);

        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.INTEGRITY_CONFLICT,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                repository.find(first.getMountId()).get().getCondition());
        assertEquals(TransferPhase.INTEGRITY_BLOCKED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(MountCondition.LIVING,
                repository.find(second.getMountId()).get().getCondition());
    }

    @Test
    void snapshotTypeMismatchIsRejectedBeforeJournalMutation() {
        MountRepository repository = new MountRepository();
        int[] commits = {0};
        repository.setAcknowledgedPersistence(snapshot -> {
            commits[0]++;
            return true;
        });
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        NBTTagCompound wrongType = new NBTTagCompound();
        wrongType.setString("id", "minecraft:pig");
        wrongType.setUniqueId("UUID", record.getPhysicalEntityId());
        TransferOperation operation = new TransferOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(),
                record.getPhysicalEntityId(), UUID.randomUUID(), record.getLastKnown(),
                new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                wrongType, 200L, 200L, TransferPhase.PREPARED, null);

        assertEquals(MountRepository.TransferStatus.REJECTED,
                repository.beginTransfer(operation));
        assertEquals(0, commits[0]);
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.getPendingTransfers().isEmpty());
    }

    @Test
    void snapshotWithoutTypeCannotBecomeATransferOperation() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();

        assertThrows(IllegalArgumentException.class, () -> new TransferOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(),
                record.getPhysicalEntityId(), UUID.randomUUID(), record.getLastKnown(),
                new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                new NBTTagCompound(), 200L, 200L, TransferPhase.PREPARED, null));
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.getPendingTransfers().isEmpty());
    }

    @Test
    void relocatedEvidenceIsAcknowledgedAndRolledBackOnPersistenceFailure() {
        MountRepository repository = new MountRepository();
        int[] commits = {0};
        repository.setAcknowledgedPersistence(snapshot -> ++commits[0] != 2);
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);
        LastKnownEvidence movedSource = new LastKnownEvidence(0, 33.0D, 70.0D, 34.0D);

        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                repository.updateTransferEvidence(
                        operation.getOperationId(), movedSource, operation.getDestinationEvidence()));

        assertEquals(operation.getSourceEvidence(), repository
                .findTransfer(operation.getOperationId()).get().getSourceEvidence());
        assertEquals(operation.getSourceEvidence(), repository
                .find(record.getMountId()).get().getLastKnown());

        repository.setAcknowledgedPersistence(snapshot -> true);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.updateTransferEvidence(
                        operation.getOperationId(), movedSource, operation.getDestinationEvidence()));
        assertEquals(movedSource, repository
                .findTransfer(operation.getOperationId()).get().getSourceEvidence());
        assertEquals(movedSource, repository.find(record.getMountId()).get().getLastKnown());
    }

    @Test
    void associatedRollbackRestoresExactSourceAndIsAtomicOnPersistenceFailure() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));

        repository.setAcknowledgedPersistence(snapshot -> false);
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                repository.rollbackAssociatedTransfer(operation.getOperationId()));
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(TransferPhase.ASSOCIATED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());

        repository.setAcknowledgedPersistence(snapshot -> true);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.rollbackAssociatedTransfer(operation.getOperationId()));
        assertEquals(operation.getSourceEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());
        assertEquals(MountCondition.LIVING,
                repository.find(record.getMountId()).get().getCondition());
        assertTrue(repository.getPendingTransfers().isEmpty());
    }

    @Test
    void sourceRemovalIntentRollbackIsAtomicAndReturnsToAssociated() {
        MountRepository repository = new MountRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID(), null).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markCandidateSpawned(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.associateTransferCandidate(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markSourceRemovalIntent(operation.getOperationId()));

        repository.setAcknowledgedPersistence(snapshot -> false);
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                repository.rollbackSourceRemovalIntent(operation.getOperationId()));
        assertEquals(TransferPhase.SOURCE_REMOVAL_INTENT,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
        assertEquals(operation.getCandidateEntityId(),
                repository.find(record.getMountId()).get().getPhysicalEntityId());

        repository.setAcknowledgedPersistence(snapshot -> true);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.rollbackSourceRemovalIntent(operation.getOperationId()));
        assertEquals(TransferPhase.ASSOCIATED,
                repository.findTransfer(operation.getOperationId()).get().getPhase());
    }

    private static TransferOperation operation(MountRecord record) {
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", HORSE.toString());
        snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
        return new TransferOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(),
                record.getPhysicalEntityId(), UUID.randomUUID(), record.getLastKnown(),
                new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                snapshot, 200L, 200L, TransferPhase.PREPARED, null);
    }

    private static MountRepository.RegistrationResult register(
            MountRepository repository, UUID ownerId, UUID physicalId, MountId claimedId) {
        return repository.register(new MountRepository.RegistrationCandidate(
                ownerId,
                PROVIDER,
                HORSE,
                HORSE.toString(),
                physicalId,
                new LastKnownEvidence(0, 1.0, 64.0, 2.0),
                claimedId));
    }
}
