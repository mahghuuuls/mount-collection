package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import java.util.EnumSet;
import java.util.UUID;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ActiveTimeResult;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountStoreCodecTest {

    @Test void renameIsOwnerScopedDurableAndDoesNotChangeSelectionOrOrdinals() {
        MountSavedData data = new MountSavedData("test");
        MountRepository repository = data.getRepository();
        UUID owner = UUID.randomUUID();
        MountRecord first = register(repository, owner, UUID.randomUUID()).getRecord().get();
        MountRecord second = register(repository, owner, UUID.randomUUID()).getRecord().get();
        long revision = repository.inspectCollection(owner).getRevision();
        assertEquals(MountRepository.RenameStatus.NOT_OWNED, repository.rename(UUID.randomUUID(), first.getMountId(), revision, "A"));
        assertEquals(MountRepository.RenameStatus.STALE, repository.rename(owner, first.getMountId(), revision - 1, "A"));
        assertEquals(MountRepository.RenameStatus.INVALID, repository.rename(owner, first.getMountId(), revision, "\nA"));
        repository.setAcknowledgedPersistence(ignored -> false);
        assertEquals(MountRepository.RenameStatus.PERSISTENCE_FAILURE, repository.rename(owner, first.getMountId(), revision, "A"));
        assertEquals(null, repository.find(first.getMountId()).get().getNaming().getCustomName());
        assertEquals(revision, repository.inspectCollection(owner).getRevision());
        repository.setAcknowledgedPersistence(ignored -> true);
        assertEquals(MountRepository.RenameStatus.SUCCESS, repository.rename(owner, first.getMountId(), revision, " A "));
        assertEquals(MountRepository.RenameStatus.UNCHANGED, repository.rename(owner, first.getMountId(), revision, "A"));
        assertEquals(MountRepository.RenameStatus.SUCCESS, repository.rename(owner, second.getMountId(), revision + 1, "A"));
        MountSavedData loaded = new MountSavedData("test");
        loaded.readFromNBT(data.writeToNBT(new NBTTagCompound()));
        for (MountRecord record : loaded.getRepository().getOwnedRecords(owner)) {
            assertEquals("A", record.getNaming().getCustomName());
            assertTrue(record.getNaming().isPending());
            assertEquals(1, record.getNaming().getRevision());
        }
        assertEquals(first.getFallbackOrdinal(), loaded.getRepository().find(first.getMountId()).get().getFallbackOrdinal());
        assertEquals(second.getMountId(), loaded.getRepository().inspectCollection(owner).getSelectedMountId().get());
        assertEquals(MountRepository.RenameStatus.SUCCESS, loaded.getRepository().rename(owner, first.getMountId(), revision + 2, ""));
        assertEquals("", loaded.getRepository().find(first.getMountId()).get().getNaming().getCustomName());
    }

    @Test void namingSurvivesRecoveryCopiesAndBusyRenameIsRejected() {
        MountRepository repository = new MountRepository();
        MountRecord original = register(repository, UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        repository.rename(original.getOwnerId(), original.getMountId(), 1, "Before recovery");
        repository.enterRecovery(original.getOwnerId(), original.getMountId(), original.getPhysicalEntityId(),
                new RecoveryState(original.getPhysicalEntityId(), original.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0, 0));
        long revision = repository.inspectCollection(original.getOwnerId()).getRevision();
        assertEquals(MountRepository.RenameStatus.SUCCESS, repository.rename(original.getOwnerId(), original.getMountId(), revision, "Recovering"));
        RestorationOperation operation = new RestorationOperation(UUID.randomUUID(), original.getMountId(),
                original.getOwnerId(), UUID.randomUUID(), new LastKnownEvidence(0, 4, 64, 4),
                0, 0, RestorationPhase.PREPARED, null);
        repository.beginRestoration(operation);
        assertEquals("Recovering", repository.find(original.getMountId()).get().getNaming().getCustomName());
        assertEquals(MountRepository.RenameStatus.BUSY, repository.rename(original.getOwnerId(), original.getMountId(),
                repository.inspectCollection(original.getOwnerId()).getRevision(), "Busy"));
    }

    @Test void legacyRecoverySchemaMigratesToUnobservedNamingAndMalformedCurrentNameIsRetained() {
        MountSavedData data = new MountSavedData("test");
        MountRecord original = register(data.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound legacy = data.writeToNBT(new NBTTagCompound());
        legacy.setInteger("RootVersion", 6);
        legacy.removeTag("Abandonments");
        NBTTagCompound record = legacy.getTagList("Records", 10).getCompoundTagAt(0);
        record.setInteger("Version", 3); record.removeTag("Naming");
        MountSavedData loaded = new MountSavedData("test");
        loaded.readFromNBT(legacy);
        assertEquals(MountCondition.LIVING, loaded.getRepository().find(original.getMountId()).get().getCondition());
        assertEquals(null, loaded.getRepository().find(original.getMountId()).get().getNaming().getCustomName());
        NBTTagCompound rewritten = loaded.writeToNBT(new NBTTagCompound());
        assertEquals(MountStoreCodec.CURRENT_ROOT_VERSION, rewritten.getInteger("RootVersion"));
        NBTTagCompound raw = rewritten.getTagList("Records", 10).getCompoundTagAt(0);
        raw.getCompoundTag("Naming").setString("CustomName", "Invalid\n");
        MountSavedData invalid = new MountSavedData("test");
        invalid.readFromNBT(rewritten);
        assertEquals(MountCondition.INTEGRITY_BLOCKED, invalid.getRepository().find(original.getMountId()).get().getCondition());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RestorationPhase.class)
    void everyRestorationPhaseRoundTripsWithoutLosingAuthority(RestorationPhase phase) {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        MountRecord record = register(repository, UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        repository.enterRecovery(record.getOwnerId(), record.getMountId(), record.getPhysicalEntityId(),
                new RecoveryState(record.getPhysicalEntityId(), record.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0, 0));
        RestorationOperation operation = new RestorationOperation(UUID.randomUUID(), record.getMountId(),
                record.getOwnerId(), UUID.randomUUID(), new LastKnownEvidence(0, 4, 64, 4),
                0, 0, RestorationPhase.PREPARED, null);
        repository.beginRestoration(operation);
        if (phase != RestorationPhase.PREPARED && phase != RestorationPhase.INTEGRITY_BLOCKED) {
            repository.markRestorationSpawnIntent(operation.getOperationId());
            if (phase != RestorationPhase.CANDIDATE_SPAWN_INTENT) {
                repository.markRestorationCandidateSpawned(operation.getOperationId());
                if (phase == RestorationPhase.ASSOCIATED) {
                    repository.associateRestorationCandidate(operation.getOperationId());
                }
            }
        }
        if (phase == RestorationPhase.INTEGRITY_BLOCKED) {
            repository.blockRestoration(operation.getOperationId(), "test conflict");
        }
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));
        assertEquals(phase, restored.getRepository().findRestoration(operation.getOperationId()).get().getPhase());
        MountRecord actual = restored.getRepository().find(record.getMountId()).get();
        assertTrue(actual.getRecoveryState() != null);
        assertEquals(phase == RestorationPhase.INTEGRITY_BLOCKED ? MountCondition.INTEGRITY_BLOCKED
                : MountCondition.OPERATION_IN_PROGRESS, actual.getCondition());
        assertEquals(phase == RestorationPhase.ASSOCIATED ? operation.getCandidateEntityId() : null,
                actual.getPhysicalEntityId());
    }

    @Test
    void priorRestorationSchemaCannotAssertNewSourceRetirementGuarantee() {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        MountRecord record = register(repository, UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        repository.enterRecovery(record.getOwnerId(), record.getMountId(), record.getPhysicalEntityId(),
                new RecoveryState(record.getPhysicalEntityId(), record.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0, 0));
        RestorationOperation operation = new RestorationOperation(UUID.randomUUID(), record.getMountId(),
                record.getOwnerId(), UUID.randomUUID(), new LastKnownEvidence(0, 4, 64, 4),
                0, 0, RestorationPhase.PREPARED, null);
        repository.beginRestoration(operation);
        repository.markRestorationSpawnIntent(operation.getOperationId());
        NBTTagCompound encoded = source.writeToNBT(new NBTTagCompound());
        encoded.getTagList("Restorations", 10).getCompoundTagAt(0).setInteger("Version", 1);
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().find(record.getMountId()).get().getRecoveryState() != null);
        assertEquals(1, restored.writeToNBT(new NBTTagCompound()).getTagList("Restorations", 10).tagCount());
    }

    @Test
    void productionCodecRoundTripPreservesIdentitySelectionOrdinalsAndClock() {
        MountSavedData original = new MountSavedData("test");
        MountRepository repository = original.getRepository();
        UUID owner = UUID.randomUUID();
        UUID firstPhysical = UUID.randomUUID();
        UUID secondPhysical = UUID.randomUUID();
        MountRecord first = register(repository, owner, firstPhysical).getRecord().get();
        MountRecord second = register(repository, owner, secondPhysical).getRecord().get();
        repository.updateActiveTick(4321L);
        assertEquals(MountRepository.RecallCommitStatus.SUCCESS, repository.commitRecall(
                owner, second.getMountId(), secondPhysical, second.getLastKnown(), 4400L, 79L));

        NBTTagCompound encoded = original.writeToNBT(new NBTTagCompound());
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);

        assertEquals(2, restored.getRepository().getTotalRecordCount());
        assertEquals(firstPhysical, restored.getRepository().find(first.getMountId()).get().getPhysicalEntityId());
        assertEquals(2, restored.getRepository().find(second.getMountId()).get().getFallbackOrdinal());
        assertEquals(second.getMountId(), restored.getRepository().inspectCollection(owner).getSelectedMountId().get());
        assertEquals(2L, restored.getRepository().inspectCollection(owner).getRevision());
        assertEquals(4321L, restored.getRepository().getActiveTick());
        assertEquals(4400L, restored.getRepository().getRecallCooldownDeadline(owner));
        assertEquals(79L, restored.getRepository().getRecallCooldown(owner).getDuration());
    }

    @Test
    void currentRecordRoundTripPreservesBoundedCharacteristics() {
        MountSavedData source = new MountSavedData("test");
        MountCharacteristics characteristics = new MountCharacteristics(
                PlacementProfile.LAVA, EnumSet.of(MountTrait.FLYING));
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID(), characteristics)
                .getRecord().get();

        NBTTagCompound encoded = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound raw = encoded.getTagList("Records", 10).getCompoundTagAt(0);
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);

        assertEquals(MountRecord.CURRENT_VERSION, raw.getInteger("Version"));
        assertEquals("LAVA", raw.getString("PlacementProfile"));
        assertEquals("FLYING", raw.getTagList("MountTraits", 8).getStringTagAt(0));
        assertEquals(characteristics,
                restored.getRepository().find(record.getMountId()).get().getCharacteristics());
    }

    @Test
    void currentRecordRoundTripPreservesRecoverySnapshotAndSelection() {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(repository, owner, UUID.randomUUID()).getRecord().get();
        NBTTagCompound payloadData = new NBTTagCompound();
        payloadData.setString("Saddle", "preserved");
        RecoveryState recovery = new RecoveryState(
                record.getPhysicalEntityId(), record.getLastKnown(),
                new ProviderPayload(3, payloadData), 120L, 120L);
        assertEquals(MountRepository.RecoveryStatus.SUCCESS,
                repository.enterRecovery(owner, record.getMountId(),
                        record.getPhysicalEntityId(), recovery));

        NBTTagCompound encoded = source.writeToNBT(new NBTTagCompound());
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);
        MountRecord decoded = restored.getRepository().find(record.getMountId()).get();

        assertEquals(MountCondition.RECOVERING, decoded.getCondition());
        assertEquals(record.getPhysicalEntityId(), decoded.getRecoveryState().getSourceEntityId());
        assertEquals(120L, decoded.getRecoveryState().getDeadline());
        assertEquals(120L, decoded.getRecoveryState().getDuration());
        assertEquals(3, decoded.getRecoveryState().getProviderPayload().getVersion());
        assertEquals("preserved",
                decoded.getRecoveryState().getProviderPayload().copyData().getString("Saddle"));
        assertEquals(record.getMountId(),
                restored.getRepository().inspectCollection(owner).getSelectedMountId().get());
        assertFalse(restored.getRepository().findByPhysicalEntity(
                record.getPhysicalEntityId()).isPresent());
    }

    @Test
    void malformedCurrentRecoveryStateIsRetainedAndBlocked() {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        assertEquals(MountRepository.RecoveryStatus.SUCCESS,
                repository.enterRecovery(record.getOwnerId(), record.getMountId(),
                        record.getPhysicalEntityId(), new RecoveryState(
                                record.getPhysicalEntityId(), record.getLastKnown(),
                                new ProviderPayload(1, new NBTTagCompound()), 20L, 20L)));
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Records", 10).getCompoundTagAt(0)
                .getCompoundTag("Recovery").removeTag("SourceEvidence");

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
    }

    @Test
    void restorationJournalRoundTripRetainsRecoveryAuthorityAndPhase() {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        MountRecord record = register(
                repository, UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        assertEquals(MountRepository.RecoveryStatus.SUCCESS,
                repository.enterRecovery(record.getOwnerId(), record.getMountId(),
                        record.getPhysicalEntityId(), new RecoveryState(
                                record.getPhysicalEntityId(), record.getLastKnown(),
                                new ProviderPayload(1, new NBTTagCompound()), 0L, 0L)));
        RestorationOperation operation = new RestorationOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(), UUID.randomUUID(),
                new LastKnownEvidence(0, 4.0D, 64.0D, 4.0D),
                40L, 40L, RestorationPhase.PREPARED, null);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.beginRestoration(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                repository.markRestorationSpawnIntent(operation.getOperationId()));

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));

        assertEquals(RestorationPhase.CANDIDATE_SPAWN_INTENT,
                restored.getRepository().findRestoration(operation.getOperationId())
                        .get().getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                restored.getRepository().find(record.getMountId()).get().getCondition());
    }

    @Test
    void duplicateRestorationOperationsAreBothRetainedAndQuarantined() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        source.getRepository().enterRecovery(
                record.getOwnerId(), record.getMountId(), record.getPhysicalEntityId(),
                new RecoveryState(
                        record.getPhysicalEntityId(), record.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0L, 0L));
        RestorationOperation operation = new RestorationOperation(
                UUID.randomUUID(), record.getMountId(), record.getOwnerId(), UUID.randomUUID(),
                new LastKnownEvidence(0, 4.0D, 64.0D, 4.0D),
                0L, 0L, RestorationPhase.PREPARED, null);
        source.getRepository().beginRestoration(operation);
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound first = root.getTagList("Restorations", 10)
                .getCompoundTagAt(0).copy();
        NBTTagCompound duplicate = first.copy();
        duplicate.setString("OperationId", UUID.randomUUID().toString());
        NBTTagList duplicated = new NBTTagList();
        duplicated.appendTag(first);
        duplicated.appendTag(duplicate);
        root.setTag("Restorations", duplicated);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingRestorations().isEmpty());
        assertEquals(2, restored.writeToNBT(new NBTTagCompound())
                .getTagList("Restorations", 10).tagCount());
    }

    @Test
    void transferAndRestorationForSameMountAreBothRetainedAndQuarantined() {
        MountSavedData recoverySource = new MountSavedData("recovery");
        MountRecord recoveryRecord = register(
                recoverySource.getRepository(), UUID.randomUUID(), UUID.randomUUID())
                        .getRecord().get();
        recoverySource.getRepository().enterRecovery(
                recoveryRecord.getOwnerId(), recoveryRecord.getMountId(),
                recoveryRecord.getPhysicalEntityId(),
                new RecoveryState(
                        recoveryRecord.getPhysicalEntityId(), recoveryRecord.getLastKnown(),
                        new ProviderPayload(1, new NBTTagCompound()), 0L, 0L));
        RestorationOperation restoration = new RestorationOperation(
                UUID.randomUUID(), recoveryRecord.getMountId(), recoveryRecord.getOwnerId(),
                UUID.randomUUID(), new LastKnownEvidence(0, 4.0D, 64.0D, 4.0D),
                0L, 0L, RestorationPhase.PREPARED, null);
        recoverySource.getRepository().beginRestoration(restoration);
        NBTTagCompound root = recoverySource.writeToNBT(new NBTTagCompound());

        MountSavedData transferSource = new MountSavedData("transfer");
        MountRecord transferRecord = register(
                transferSource.getRepository(), UUID.randomUUID(), UUID.randomUUID())
                        .getRecord().get();
        transferSource.getRepository().beginTransfer(operation(transferRecord));
        NBTTagCompound transferRaw = transferSource.writeToNBT(new NBTTagCompound())
                .getTagList("Transfers", 10).getCompoundTagAt(0).copy();
        transferRaw.setString("MountId", recoveryRecord.getMountId().toString());
        transferRaw.setString("OwnerId", recoveryRecord.getOwnerId().toString());
        NBTTagList transfers = new NBTTagList();
        transfers.appendTag(transferRaw);
        root.setTag("Transfers", transfers);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(recoveryRecord.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingRestorations().isEmpty());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        NBTTagCompound retained = restored.writeToNBT(new NBTTagCompound());
        assertEquals(1, retained.getTagList("Restorations", 10).tagCount());
        assertEquals(1, retained.getTagList("Transfers", 10).tagCount());
    }

    @Test
    void currentPlayerSchemaRequiresItsExactVersion() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        register(source.getRepository(), owner, UUID.randomUUID());
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Players", 10).getCompoundTagAt(0).removeTag("Version");

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertFalse(restored.getRepository().inspectCollection(owner)
                .getSelectedMountId().isPresent());
        assertTrue(restored.writeToNBT(new NBTTagCompound())
                .getTagList("Players", 10).tagCount() >= 1);
    }

    @Test
    void restorationIntegrityReasonIsBoundedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new RestorationOperation(
                UUID.randomUUID(), MountId.create(), UUID.randomUUID(), UUID.randomUUID(),
                new LastKnownEvidence(0, 0.0D, 64.0D, 0.0D),
                0L, 0L, RestorationPhase.INTEGRITY_BLOCKED,
                new String(new char[161]).replace('\0', 'x')));
    }

    @Test
    void everyPersistedRestorationPhaseReloadsThroughTheProductionCodec() {
        for (RestorationPhase expected : RestorationPhase.values()) {
            MountSavedData source = new MountSavedData("test");
            MountRecord record = register(
                    source.getRepository(), UUID.randomUUID(), UUID.randomUUID())
                            .getRecord().get();
            source.getRepository().enterRecovery(
                    record.getOwnerId(), record.getMountId(), record.getPhysicalEntityId(),
                    new RecoveryState(
                            record.getPhysicalEntityId(), record.getLastKnown(),
                            new ProviderPayload(1, new NBTTagCompound()), 0L, 0L));
            RestorationOperation operation = new RestorationOperation(
                    UUID.randomUUID(), record.getMountId(), record.getOwnerId(), UUID.randomUUID(),
                    new LastKnownEvidence(0, 4.0D, 64.0D, 4.0D),
                    0L, 0L, RestorationPhase.PREPARED, null);
            source.getRepository().beginRestoration(operation);
            if (expected == RestorationPhase.CANDIDATE_SPAWN_INTENT
                    || expected == RestorationPhase.CANDIDATE_SPAWNED
                    || expected == RestorationPhase.ASSOCIATED) {
                source.getRepository().markRestorationSpawnIntent(operation.getOperationId());
            }
            if (expected == RestorationPhase.CANDIDATE_SPAWNED
                    || expected == RestorationPhase.ASSOCIATED) {
                source.getRepository().markRestorationCandidateSpawned(operation.getOperationId());
            }
            if (expected == RestorationPhase.ASSOCIATED) {
                source.getRepository().associateRestorationCandidate(operation.getOperationId());
            } else if (expected == RestorationPhase.INTEGRITY_BLOCKED) {
                source.getRepository().blockRestoration(
                        operation.getOperationId(), "injected test conflict");
            }

            MountSavedData restored = new MountSavedData("test");
            restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));

            assertEquals(expected, restored.getRepository()
                    .findRestoration(operation.getOperationId()).get().getPhase());
        }
    }

    @Test
    void legacyRecordMigratesUnambiguouslyToGroundWithoutTraits() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.setInteger("RootVersion", 4);
        NBTTagCompound raw = root.getTagList("Records", 10).getCompoundTagAt(0);
        raw.setInteger("Version", 1);
        raw.removeTag("PlacementProfile");
        raw.removeTag("MountTraits");

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound migrated = restored.writeToNBT(new NBTTagCompound())
                .getTagList("Records", 10).getCompoundTagAt(0);

        assertEquals(MountCharacteristics.solidGround(),
                restored.getRepository().find(record.getMountId()).get().getCharacteristics());
        assertTrue(restored.isDirty());
        assertEquals(MountRecord.CURRENT_VERSION, migrated.getInteger("Version"));
        assertEquals("SOLID_GROUND", migrated.getString("PlacementProfile"));
        assertEquals(0, migrated.getTagList("MountTraits", 8).tagCount());
    }

    @Test
    void malformedOrUnknownCurrentCharacteristicsAreRetainedAndBlocked() {
        assertCharacteristicRecordBlocked(raw -> raw.setString("PlacementProfile", "AERIAL"));
        assertCharacteristicRecordBlocked(raw -> raw.removeTag("PlacementProfile"));
        assertCharacteristicRecordBlocked(raw -> {
            NBTTagList traits = new NBTTagList();
            traits.appendTag(new NBTTagString("UNKNOWN"));
            raw.setTag("MountTraits", traits);
        });
        assertCharacteristicRecordBlocked(raw -> {
            NBTTagList traits = new NBTTagList();
            traits.appendTag(new NBTTagString("FLYING"));
            traits.appendTag(new NBTTagString("FLYING"));
            raw.setTag("MountTraits", traits);
        });
    }

    @Test
    void malformedOrLegacyRecordVersionCannotBypassCurrentCharacteristicDecoding() {
        assertCharacteristicRecordBlocked(raw -> raw.removeTag("Version"));
        assertCharacteristicRecordBlocked(raw -> raw.setString("Version", "1"));
        assertCharacteristicRecordBlocked(raw -> raw.setInteger("Version", -1));
        assertCharacteristicRecordBlocked(raw -> raw.setInteger("Version", 1));
    }

    @Test
    void restartPreservesRemainingCooldownWithoutCountingDowntime() {
        MountSavedData source = new MountSavedData("test");
        MountRepository repository = source.getRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = register(repository, owner, UUID.randomUUID()).getRecord().get();
        repository.updateActiveTick(100L);
        assertEquals(MountRepository.RecallCommitStatus.SUCCESS, repository.commitRecall(
                owner, record.getMountId(), record.getPhysicalEntityId(), record.getLastKnown(), 300L, 200L));

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));
        ActiveServerClock restartedClock = new ActiveServerClock();
        restartedClock.restore(restored.getRepository().getActiveTick(), 0L);

        MountRepository.CooldownState cooldown = restored.getRepository().getRecallCooldown(owner);
        assertEquals(200L, restartedClock.remainingUntil(
                cooldown.getDeadline(), cooldown.getDuration()).getValue());
    }

    @Test
    void transferJournalRoundTripPreservesEveryRecoveryFieldAndPhase() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                source.getRepository(), owner, UUID.randomUUID()).getRecord().get();
        source.getRepository().updateActiveTick(700L);
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", "minecraft:horse");
        snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
        TransferOperation operation = new TransferOperation(
                UUID.randomUUID(), record.getMountId(), owner, record.getPhysicalEntityId(),
                UUID.randomUUID(), record.getLastKnown(),
                new LastKnownEvidence(-1, 12.5D, 70.0D, -3.5D), snapshot,
                900L, 200L, TransferPhase.PREPARED, null);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().markCandidateSpawnIntent(operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().markCandidateSpawned(operation.getOperationId()));

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));
        TransferOperation decoded = restored.getRepository()
                .findTransfer(operation.getOperationId()).get();

        assertEquals(TransferPhase.CANDIDATE_SPAWNED, decoded.getPhase());
        assertEquals(operation.getMountId(), decoded.getMountId());
        assertEquals(operation.getSourceEntityId(), decoded.getSourceEntityId());
        assertEquals(operation.getCandidateEntityId(), decoded.getCandidateEntityId());
        assertEquals(operation.getDestinationEvidence(), decoded.getDestinationEvidence());
        assertEquals("minecraft:horse", decoded.copySourceSnapshot().getString("id"));
        assertEquals(900L, decoded.getCooldownDeadline());
        assertEquals(200L, decoded.getCooldownDuration());
    }

    @Test
    void malformedTransferIsRetainedAndItsKnownMountIsQuarantined() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setInteger("Version", 99);
        malformed.setString("OperationId", UUID.randomUUID().toString());
        malformed.setString("MountId", record.getMountId().toString());
        root.getTagList("Transfers", 10).appendTag(malformed);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertEquals(1, rewritten.getTagList("Transfers", 10).tagCount());
        assertEquals(99, rewritten.getTagList("Transfers", 10)
                .getCompoundTagAt(0).getInteger("Version"));
    }

    @Test
    void versionOnePendingTransferFailsClosedInsteadOfUsingPreIntentRecoveryRules() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Transfers", 10).getCompoundTagAt(0).setInteger("Version", 1);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertEquals(1, rewritten.getTagList("Transfers", 10).tagCount());
        assertEquals(1, rewritten.getTagList("Transfers", 10)
                .getCompoundTagAt(0).getInteger("Version"));
    }

    @Test
    void futureRootSchemaRemainsReadOnlyAndByteForByteSemanticallyPreserved() {
        NBTTagCompound future = new NBTTagCompound();
        future.setInteger("RootVersion", 99);
        future.setString("FutureField", "keep-me");
        MountSavedData data = new MountSavedData("test");

        data.readFromNBT(future);
        NBTTagCompound rewritten = data.writeToNBT(new NBTTagCompound());

        assertTrue(data.getRepository().isReadOnly());
        assertEquals(99, rewritten.getInteger("RootVersion"));
        assertEquals("keep-me", rewritten.getString("FutureField"));
        assertEquals(
                MountRepository.RegistrationStatus.READ_ONLY,
                register(data.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getStatus());
    }

    @Test
    void legacyRootMigratesToCurrentVersion() {
        MountSavedData data = new MountSavedData("test");
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setTag("Records", new NBTTagList());
        legacy.setTag("Players", new NBTTagList());

        data.readFromNBT(legacy);
        NBTTagCompound migrated = data.writeToNBT(new NBTTagCompound());

        assertTrue(data.isDirty());
        assertEquals(MountStoreCodec.CURRENT_ROOT_VERSION, migrated.getInteger("RootVersion"));
    }

    @Test
    void futureRecordIsRetainedAsUnavailableWithoutDamagingValidRecord() {
        MountSavedData source = new MountSavedData("test");
        MountRecord valid = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagList records = root.getTagList("Records", 10);
        MountId futureId = MountId.create();
        UUID futureOwner = UUID.randomUUID();
        NBTTagCompound futureRecord = new NBTTagCompound();
        futureRecord.setInteger("Version", 99);
        futureRecord.setString("MountId", futureId.toString());
        futureRecord.setString("OwnerId", futureOwner.toString());
        futureRecord.setInteger("FallbackOrdinal", 7);
        futureRecord.setLong("RegistrationOrder", 8L);
        futureRecord.setString("FutureField", "preserve");
        records.appendTag(futureRecord);
        root.setTag("Records", records);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(MountCondition.LIVING, restored.getRepository().find(valid.getMountId()).get().getCondition());
        assertEquals(MountCondition.INTEGRITY_BLOCKED, restored.getRepository().find(futureId).get().getCondition());
        assertEquals("preserve", rewritten.getTagList("Records", 10)
                .getCompoundTagAt(1).getString("FutureField"));
    }

    @Test
    void malformedRecordIsRetainedWhileValidRecordsRemainUsable() {
        MountSavedData source = new MountSavedData("test");
        MountRecord valid = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagList records = root.getTagList("Records", 10);
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setInteger("Version", 1);
        malformed.setString("MountId", "not-a-uuid");
        malformed.setString("Evidence", "retain");
        records.appendTag(malformed);
        root.setTag("Records", records);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertFalse(restored.getRepository().isReadOnly());
        assertTrue(restored.getRepository().find(valid.getMountId()).isPresent());
        assertEquals(2, rewritten.getTagList("Records", 10).tagCount());
        assertEquals("retain", rewritten.getTagList("Records", 10)
                .getCompoundTagAt(1).getString("Evidence"));
    }

    @Test
    void parseableMalformedRecordRemainsVisibleAsIntegrityBlocked() {
        MountSavedData source = new MountSavedData("test");
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        MountId malformedId = MountId.create();
        UUID ownerId = UUID.randomUUID();
        NBTTagCompound malformed = new NBTTagCompound();
        malformed.setInteger("Version", 1);
        malformed.setString("MountId", malformedId.toString());
        malformed.setString("OwnerId", ownerId.toString());
        malformed.setString("ProviderId", "not valid");
        malformed.setInteger("FallbackOrdinal", 3);
        malformed.setLong("RegistrationOrder", 4L);
        malformed.setString("Evidence", "retain-indexed");
        NBTTagList records = root.getTagList("Records", 10);
        records.appendTag(malformed);
        root.setTag("Records", records);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(
                MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(malformedId).get().getCondition());
        assertEquals(1, restored.getRepository().getOwnedRecords(ownerId).size());
        assertEquals("retain-indexed", rewritten.getTagList("Records", 10)
                .getCompoundTagAt(0).getString("Evidence"));
    }

    @Test
    void crossOwnerSavedSelectionIsPreservedButNeverActivated() {
        MountSavedData source = new MountSavedData("test");
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        register(source.getRepository(), firstOwner, UUID.randomUUID());
        MountRecord second = register(
                source.getRepository(), secondOwner, UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagList players = root.getTagList("Players", 10);
        for (int index = 0; index < players.tagCount(); index++) {
            NBTTagCompound player = players.getCompoundTagAt(index);
            if (firstOwner.toString().equals(player.getString("OwnerId"))) {
                player.setString("SelectedMountId", second.getMountId().toString());
            }
        }
        root.setTag("Players", players);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertFalse(restored.getRepository().inspectCollection(firstOwner).getSelectedMountId().isPresent());
        assertTrue(rewritten.toString().contains(second.getMountId().toString()));
        assertEquals(secondOwner, restored.getRepository().find(second.getMountId()).get().getOwnerId());
    }

    @Test
    void negativePersistedClockReachesClockAnomalyHandling() {
        NBTTagCompound root = new MountSavedData("test").writeToNBT(new NBTTagCompound());
        root.setLong("ActiveTick", -7L);
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        ActiveServerClock clock = new ActiveServerClock();

        ActiveTimeResult result = clock.restore(restored.getRepository().getActiveTick(), 0L);

        assertEquals(ActiveTimeResult.Status.NEGATIVE_INPUT, result.getStatus());
        assertEquals(0L, clock.now());
    }

    @Test
    void futurePlayerSchemaIsRetainedButNeverActivated() {
        MountSavedData source = new MountSavedData("test");
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        UUID owner = UUID.randomUUID();
        NBTTagCompound futurePlayer = new NBTTagCompound();
        futurePlayer.setInteger("Version", 99);
        futurePlayer.setString("OwnerId", owner.toString());
        futurePlayer.setLong("Revision", 41L);
        futurePlayer.setString("FutureField", "preserve-player");
        NBTTagList players = root.getTagList("Players", 10);
        players.appendTag(futurePlayer);
        root.setTag("Players", players);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(0L, restored.getRepository().inspectCollection(owner).getRevision());
        assertEquals("preserve-player", rewritten.getTagList("Players", 10)
                .getCompoundTagAt(0).getString("FutureField"));
    }

    @Test
    void futureVanillaProviderPayloadIsRetainedAndBlocked() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound raw = root.getTagList("Records", 10).getCompoundTagAt(0);
        raw.setInteger("ProviderPayloadVersion", 7);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("FutureField", "preserve-provider");
        raw.setTag("ProviderPayload", payload);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        restored.getRepository().reconcileProviderPayloads((providerId, providerPayload) ->
                providerPayload.getVersion() == 0
                        ? com.mahghuuuls.mountcollection.api.ProviderResult.success(providerPayload)
                        : com.mahghuuuls.mountcollection.api.ProviderResult.failure(
                                com.mahghuuuls.mountcollection.api.ProviderFailure.INVALID_STATE));
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(MountCondition.PROVIDER_UNAVAILABLE,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertEquals("preserve-provider", rewritten.getTagList("Records", 10)
                .getCompoundTagAt(0).getCompoundTag("ProviderPayload").getString("FutureField"));
    }

    @Test
    void duplicatePhysicalAssociationsAreBothQuarantinedOnLoad() {
        MountSavedData source = new MountSavedData("test");
        MountRecord first = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        MountRecord second = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagList records = root.getTagList("Records", 10);
        records.getCompoundTagAt(1).setString("PhysicalEntityId", first.getPhysicalEntityId().toString());

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(first.getMountId()).get().getCondition());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(second.getMountId()).get().getCondition());
        assertFalse(restored.getRepository().findByPhysicalEntity(first.getPhysicalEntityId()).isPresent());
    }

    @Test
    void representativeLargeCollectionRoundTripsWithoutAConfiguredCap() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 1025; index++) {
            assertEquals(MountRepository.RegistrationStatus.SUCCESS,
                    register(source.getRepository(), owner, UUID.randomUUID()).getStatus());
        }

        NBTTagCompound encoded = source.writeToNBT(new NBTTagCompound());
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);

        assertEquals(1025, restored.getRepository().inspectCollection(owner).getCount());
        assertEquals(1025L, restored.getRepository().inspectCollection(owner).getRevision());
        assertEquals(1025, encoded.getTagList("Records", 10).tagCount());
    }

    @Test
    void stalePersistedCountersCannotReuseOrdinalsOrRegistrationOrder() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        register(source.getRepository(), owner, UUID.randomUUID());
        MountRecord second = register(
                source.getRepository(), owner, UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.setLong("NextRegistrationOrder", 1L);
        NBTTagCompound player = root.getTagList("Players", 10).getCompoundTagAt(0);
        player.getTagList("Ordinals", 10).getCompoundTagAt(0).setInteger("Next", 1);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        MountRecord third = register(
                restored.getRepository(), owner, UUID.randomUUID()).getRecord().get();

        assertEquals(3, third.getFallbackOrdinal());
        assertEquals(second.getRegistrationOrder() + 1L, third.getRegistrationOrder());
    }

    @Test
    void selectedFutureRecordIsRetainedButUnavailable() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        MountRecord selected = register(
                source.getRepository(), owner, UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Records", 10).getCompoundTagAt(0).setInteger("Version", 99);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(selected.getMountId()).get().getCondition());
        assertEquals(selected.getMountId(), restored.getRepository().inspectCollection(owner).getSelectedMountId().get());
        assertFalse(restored.getRepository().prepareRecall(owner,
                selected.getMountId(), selected.getPhysicalEntityId()).isPresent());
    }

    @Test
    void missingProviderMakesOnlyItsRecordUnavailableAndCanRecoverLater() {
        MountSavedData source = new MountSavedData("test");
        UUID owner = UUID.randomUUID();
        MountRecord record = register(
                source.getRepository(), owner, UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Records", 10).getCompoundTagAt(0)
                .setString("ProviderId", "removedaddon:mounts");
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        restored.getRepository().reconcileProviderPayloads((providerId, payload) ->
                com.mahghuuuls.mountcollection.api.ProviderResult.failure(
                        com.mahghuuuls.mountcollection.api.ProviderFailure.UNSUPPORTED));

        assertEquals(MountCondition.PROVIDER_UNAVAILABLE,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().findByPhysicalEntity(record.getPhysicalEntityId()).isPresent());
        assertEquals(record.getMountId(), restored.getRepository().inspectCollection(owner).getSelectedMountId().get());
        assertFalse(restored.getRepository().prepareRecall(owner,
                record.getMountId(), record.getPhysicalEntityId()).isPresent());
        assertEquals(
                MountRepository.ReconciliationStatus.VERIFIED,
                restored.getRepository().reconcile(
                        record.getPhysicalEntityId(), record.getMountId(), record.getLastKnown()));
        assertEquals(MountCondition.PROVIDER_UNAVAILABLE,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertEquals(
                MountRepository.RegistrationStatus.DUPLICATE_ENTITY,
                register(restored.getRepository(), owner, record.getPhysicalEntityId()).getStatus());

        restored.getRepository().reconcileProviderPayloads((providerId, payload) ->
                com.mahghuuuls.mountcollection.api.ProviderResult.success(payload));
        assertEquals(MountCondition.LIVING,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().findByPhysicalEntity(record.getPhysicalEntityId()).isPresent());
    }

    @Test
    void wrongRootContainerTagTypeIsPreservedInReadOnlyMode() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("RootVersion", MountStoreCodec.CURRENT_ROOT_VERSION);
        root.setString("Records", "malformed-container");
        root.setTag("Players", new NBTTagList());
        MountSavedData restored = new MountSavedData("test");

        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertTrue(restored.getRepository().isReadOnly());
        assertEquals("malformed-container", rewritten.getString("Records"));
    }

    @Test
    void wrongRootListElementTypeIsPreservedInReadOnlyMode() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("RootVersion", MountStoreCodec.CURRENT_ROOT_VERSION);
        NBTTagList malformedRecords = new NBTTagList();
        malformedRecords.appendTag(new NBTTagString("malformed-element"));
        root.setTag("Records", malformedRecords);
        root.setTag("Players", new NBTTagList());
        MountSavedData restored = new MountSavedData("test");

        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertTrue(restored.getRepository().isReadOnly());
        assertEquals("malformed-element", rewritten.getTagList("Records", 8).getStringTagAt(0));
    }

    @Test
    void wrongRootScalarTypeIsPreservedInReadOnlyMode() {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("RootVersion", "not-a-number");
        root.setTag("Records", new NBTTagList());
        root.setTag("Players", new NBTTagList());
        MountSavedData restored = new MountSavedData("test");

        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertTrue(restored.getRepository().isReadOnly());
        assertEquals("not-a-number", rewritten.getString("RootVersion"));
    }

    @Test
    void everyPersistedTransferPhaseReloadsThroughTheProductionCodec() {
        for (TransferPhase expected : new TransferPhase[] {
                TransferPhase.PREPARED,
                TransferPhase.CANDIDATE_SPAWN_INTENT,
                TransferPhase.CANDIDATE_SPAWNED,
                TransferPhase.ASSOCIATED,
                TransferPhase.SOURCE_REMOVAL_INTENT,
                TransferPhase.SOURCE_REMOVED}) {
            MountSavedData source = new MountSavedData("test");
            MountRecord record = register(
                    source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
            TransferOperation operation = operation(record);
            assertEquals(MountRepository.TransferStatus.SUCCESS,
                    source.getRepository().beginTransfer(operation));
            if (expected.ordinal() >= TransferPhase.CANDIDATE_SPAWN_INTENT.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        source.getRepository().markCandidateSpawnIntent(operation.getOperationId()));
            }
            if (expected.ordinal() >= TransferPhase.CANDIDATE_SPAWNED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        source.getRepository().markCandidateSpawned(operation.getOperationId()));
            }
            if (expected.ordinal() >= TransferPhase.ASSOCIATED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        source.getRepository().associateTransferCandidate(operation.getOperationId()));
            }
            if (expected.ordinal() >= TransferPhase.SOURCE_REMOVAL_INTENT.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        source.getRepository().markSourceRemovalIntent(operation.getOperationId()));
            }
            if (expected.ordinal() >= TransferPhase.SOURCE_REMOVED.ordinal()) {
                assertEquals(MountRepository.TransferStatus.SUCCESS,
                        source.getRepository().markTransferSourceRemoved(operation.getOperationId()));
            }

            MountSavedData restored = new MountSavedData("test");
            restored.readFromNBT(source.writeToNBT(new NBTTagCompound()));

            assertEquals(expected,
                    restored.getRepository().findTransfer(operation.getOperationId()).get().getPhase());
            assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                    restored.getRepository().find(record.getMountId()).get().getCondition());
            assertEquals(
                    expected.ordinal() < TransferPhase.ASSOCIATED.ordinal()
                            ? operation.getSourceEntityId()
                            : operation.getCandidateEntityId(),
                    restored.getRepository().find(record.getMountId()).get().getPhysicalEntityId());
        }
    }

    @Test
    void wronglyTypedTransferFieldIsRetainedAndQuarantinesItsMount() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        root.getTagList("Transfers", 10).getCompoundTagAt(0)
                .setString("CooldownDeadline", "200");

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        NBTTagCompound rewritten = restored.writeToNBT(new NBTTagCompound());

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(8, rewritten.getTagList("Transfers", 10)
                .getCompoundTagAt(0).getTagId("CooldownDeadline"));
    }

    @Test
    void currentRootRequiresExactRevisionAndTransferContainerTypes() {
        MountSavedData source = new MountSavedData("test");
        NBTTagCompound valid = source.writeToNBT(new NBTTagCompound());

        for (String missing : new String[] {"StoreRevision", "Transfers"}) {
            NBTTagCompound malformed = valid.copy();
            malformed.removeTag(missing);
            MountSavedData restored = new MountSavedData("test");
            restored.readFromNBT(malformed);
            assertTrue(restored.getRepository().isReadOnly());
        }

        NBTTagCompound wrongRevision = valid.copy();
        wrongRevision.setInteger("StoreRevision", 0);
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(wrongRevision);
        assertTrue(restored.getRepository().isReadOnly());
    }

    @Test
    void everyRequiredTransferFieldIsMandatoryInTheCurrentSchema() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound valid = source.writeToNBT(new NBTTagCompound());

        for (String missing : new String[] {
                "Version", "OperationId", "MountId", "OwnerId", "SourceEntityId",
                "CandidateEntityId", "SourceSnapshot", "SourceEvidence",
                "DestinationEvidence", "CooldownDeadline", "CooldownDuration", "Phase"}) {
            NBTTagCompound malformed = valid.copy();
            malformed.getTagList("Transfers", 10).getCompoundTagAt(0).removeTag(missing);
            assertMalformedTransferQuarantined(malformed, record.getMountId());
        }
    }

    @Test
    void transferSnapshotIdentityEvidenceAndTimingMustMatchTheRecord() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound valid = source.writeToNBT(new NBTTagCompound());

        NBTTagCompound wrongType = valid.copy();
        wrongType.getTagList("Transfers", 10).getCompoundTagAt(0)
                .getCompoundTag("SourceSnapshot").setString("id", "minecraft:pig");
        assertSemanticTransferQuarantined(wrongType, operation);

        NBTTagCompound wrongSnapshotUuid = valid.copy();
        wrongSnapshotUuid.getTagList("Transfers", 10).getCompoundTagAt(0)
                .getCompoundTag("SourceSnapshot").setUniqueId("UUID", UUID.randomUUID());
        assertSemanticTransferQuarantined(wrongSnapshotUuid, operation);

        NBTTagCompound wrongEvidence = valid.copy();
        wrongEvidence.getTagList("Transfers", 10).getCompoundTagAt(0)
                .getCompoundTag("SourceEvidence").setDouble("X", 99.0D);
        assertSemanticTransferQuarantined(wrongEvidence, operation);

        NBTTagCompound excessiveDeadline = valid.copy();
        excessiveDeadline.getTagList("Transfers", 10).getCompoundTagAt(0)
                .setLong("CooldownDeadline", 201L);
        assertSemanticTransferQuarantined(excessiveDeadline, operation);
    }

    @Test
    void duplicateOperationIdentityQuarantinesEveryImplicatedKnownMount() {
        MountSavedData source = new MountSavedData("test");
        MountRecord first = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        MountRecord second = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(first);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound duplicate = root.getTagList("Transfers", 10)
                .getCompoundTagAt(0).copy();
        duplicate.setString("MountId", second.getMountId().toString());
        duplicate.setString("OwnerId", second.getOwnerId().toString());
        duplicate.setString("SourceEntityId", second.getPhysicalEntityId().toString());
        duplicate.setString("CandidateEntityId", UUID.randomUUID().toString());
        root.getTagList("Transfers", 10).appendTag(duplicate);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(first.getMountId()).get().getCondition());
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(second.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(2, restored.writeToNBT(new NBTTagCompound())
                .getTagList("Transfers", 10).tagCount());
    }

    @Test
    void malformedDuplicateOperationIdentityQuarantinesTheAcceptedOperation() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound malformed = root.getTagList("Transfers", 10)
                .getCompoundTagAt(0).copy();
        malformed.setInteger("Version", 99);
        malformed.setString("MountId", MountId.create().toString());
        malformed.setString("OwnerId", UUID.randomUUID().toString());
        malformed.setString("SourceEntityId", UUID.randomUUID().toString());
        malformed.setString("CandidateEntityId", UUID.randomUUID().toString());
        root.getTagList("Transfers", 10).appendTag(malformed);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(2, restored.writeToNBT(new NBTTagCompound())
                .getTagList("Transfers", 10).tagCount());
    }

    @Test
    void malformedOperationIdentityAlsoQuarantinesALaterValidDuplicate() {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        TransferOperation operation = operation(record);
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                source.getRepository().beginTransfer(operation));
        NBTTagCompound encoded = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound valid = encoded.getTagList("Transfers", 10)
                .getCompoundTagAt(0).copy();
        NBTTagCompound malformed = valid.copy();
        malformed.setInteger("Version", 99);
        malformed.setString("MountId", MountId.create().toString());
        malformed.setString("OwnerId", UUID.randomUUID().toString());
        malformed.setString("SourceEntityId", UUID.randomUUID().toString());
        malformed.setString("CandidateEntityId", UUID.randomUUID().toString());
        NBTTagList reordered = new NBTTagList();
        reordered.appendTag(malformed);
        reordered.appendTag(valid);
        encoded.setTag("Transfers", reordered);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(encoded);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
        assertEquals(2, restored.writeToNBT(new NBTTagCompound())
                .getTagList("Transfers", 10).tagCount());
    }

    private static TransferOperation operation(MountRecord record) {
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", "minecraft:horse");
        snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
        return new TransferOperation(
                UUID.randomUUID(),
                record.getMountId(),
                record.getOwnerId(),
                record.getPhysicalEntityId(),
                UUID.randomUUID(),
                record.getLastKnown(),
                new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                snapshot,
                200L,
                200L,
                TransferPhase.PREPARED,
                null);
    }

    private static void assertMalformedTransferQuarantined(
            NBTTagCompound root, MountId mountId) {
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(mountId).get().getCondition());
        assertTrue(restored.getRepository().getPendingTransfers().isEmpty());
    }

    private static void assertSemanticTransferQuarantined(
            NBTTagCompound root, TransferOperation operation) {
        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);
        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(operation.getMountId()).get().getCondition());
        assertEquals(TransferPhase.INTEGRITY_BLOCKED,
                restored.getRepository().findTransfer(operation.getOperationId()).get().getPhase());
    }

    private static MountRepository.RegistrationResult register(
            MountRepository repository, UUID owner, UUID physical) {
        return register(repository, owner, physical, MountCharacteristics.solidGround());
    }

    private static MountRepository.RegistrationResult register(
            MountRepository repository,
            UUID owner,
            UUID physical,
            MountCharacteristics characteristics) {
        ResourceLocation horse = new ResourceLocation("minecraft:horse");
        return repository.register(new MountRepository.RegistrationCandidate(
                owner,
                new ResourceLocation("mountcollection:vanilla"),
                horse,
                horse.toString(),
                physical,
                new LastKnownEvidence(0, 0.0, 64.0, 0.0),
                null,
                characteristics,
                new ProviderPayload(0, new NBTTagCompound())));
    }

    private static void assertCharacteristicRecordBlocked(
            java.util.function.Consumer<NBTTagCompound> mutation) {
        MountSavedData source = new MountSavedData("test");
        MountRecord record = register(
                source.getRepository(), UUID.randomUUID(), UUID.randomUUID()).getRecord().get();
        NBTTagCompound root = source.writeToNBT(new NBTTagCompound());
        NBTTagCompound raw = root.getTagList("Records", 10).getCompoundTagAt(0);
        mutation.accept(raw);

        MountSavedData restored = new MountSavedData("test");
        restored.readFromNBT(root);

        assertEquals(MountCondition.INTEGRITY_BLOCKED,
                restored.getRepository().find(record.getMountId()).get().getCondition());
    }
}
