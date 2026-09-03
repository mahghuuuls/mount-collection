package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ActiveTimeResult;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountStoreCodecTest {

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
        assertFalse(restored.getRepository().inspectCollection(owner).getSelectedMountId().isPresent());
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
        assertFalse(restored.getRepository().inspectCollection(owner).getSelectedMountId().isPresent());
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
        ResourceLocation horse = new ResourceLocation("minecraft:horse");
        return repository.register(new MountRepository.RegistrationCandidate(
                owner,
                new ResourceLocation("mountcollection:vanilla"),
                horse,
                horse.toString(),
                physical,
                new LastKnownEvidence(0, 0.0, 64.0, 0.0),
                null));
    }
}
