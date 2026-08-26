package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
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
