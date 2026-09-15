package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.diagnostics.*;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.lifecycle.*;
import com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService.AbandonmentResult;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual lifecycle, repository, codec and acknowledgement adapter with controlled world/I/O edges. */
final class AbandonmentTest {
    private static final ResourceLocation PROVIDER = new ResourceLocation("mountcollection:vanilla");
    private static final ResourceLocation TYPE = new ResourceLocation("minecraft:horse");
    private static final LastKnownEvidence LOCATION = new LastKnownEvidence(0, 2, 64, 3);

    @Test void successRemovesOnlyTrackingAndRetainsOrdinalCooldownAndOtherEntries() {
        Fixture f = new Fixture();
        MountRecord other = f.register();
        f.repository.select(f.owner, f.record.getMountId(), f.revision());
        f.repository.prepareRecall(f.owner, f.record.getMountId(), f.record.getPhysicalEntityId()).get()
                .complete(LOCATION, 88, 88);
        long order = f.repository.snapshot().nextRegistrationOrder;
        assertEquals(AbandonmentResult.SUCCESS, f.request());
        assertFalse(f.world.marker);
        assertEquals(1, f.world.clears);
        assertEquals(1, f.world.closed);
        assertFalse(f.repository.find(f.record.getMountId()).isPresent());
        assertFalse(f.repository.findByPhysicalEntity(f.record.getPhysicalEntityId()).isPresent());
        assertTrue(f.repository.find(other.getMountId()).isPresent());
        assertFalse(f.repository.inspectCollection(f.owner).getSelectedMountId().isPresent());
        assertEquals(88, f.repository.getRecallCooldownDeadline(f.owner));
        MountRecord next = f.register();
        assertEquals(order, next.getRegistrationOrder());
        assertEquals(3, next.getFallbackOrdinal());
        assertEquals(AbandonmentResult.NOT_OWNED, f.request());
        assertEquals(1, f.world.clears);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void initialAcknowledgementFailureDurablyRollsBackBeforeAnyMarkerChange(boolean diskChanged) {
        Fixture f = new Fixture();
        f.io.failAt = 1; f.io.persistFailedWrite = diskChanged;
        assertEquals(AbandonmentResult.SAVE_FAILED, f.request());
        assertTrue(f.world.marker); assertEquals(0, f.world.clears);
        assertEquals(2, f.io.writes);
        MountRepository loaded = f.restart();
        assertFalse(loaded.isReadOnly());
        assertEquals(MountCondition.LIVING, loaded.find(f.record.getMountId()).get().getCondition());
        assertTrue(loaded.getPendingAbandonments().isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void finalReadBackFailureRestoresAcknowledgedPendingForEitherDiskResult(boolean diskChanged) {
        Fixture f = new Fixture();
        f.io.failAt = 2; f.io.persistFailedWrite = diskChanged;
        assertEquals(AbandonmentResult.PENDING, f.request());
        assertFalse(f.world.marker);
        assertEquals(3, f.io.writes);
        MountRepository loaded = f.restart();
        assertEquals(1, loaded.getPendingAbandonments().size());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS, loaded.find(f.record.getMountId()).get().getCondition());
        assertEquals(MountRepository.ReconciliationStatus.VERIFIED,
                loaded.reconcile(f.record.getPhysicalEntityId(), null, LOCATION));
        World world = new World(loaded); world.marker = false;
        service(loaded, world, true).reconcilePendingAbandonments();
        assertEquals(0, loaded.getTotalRecordCount());
        assertEquals(1, world.clears, "restart must perform a fresh fence even with absent marker");
        assertFalse(world.bounded);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedSafeStateAcknowledgementEscapesInsteadOfReturningOrdinaryFailure(boolean afterPhysical) {
        Fixture f = new Fixture();
        f.io.failAt = afterPhysical ? 2 : 1; f.io.failThereafter = true;
        FatalTransferSafetyException fatal = assertThrows(FatalTransferSafetyException.class, f::request);
        assertTrue(fatal.boundedDiagnosticDetail().contains("ABANDONMENT"));
        assertEquals(afterPhysical ? 1 : 0, f.world.clears);
    }

    @Test void fenceFailureBlocksCompetingActionsButAllowsSelectionThenIdempotentRetry() {
        Fixture f = new Fixture(); MountRecord other = f.register();
        f.repository.select(f.owner, f.record.getMountId(), f.revision());
        f.world.fence = RecallWorldGateway.CheckpointStatus.FAILED;
        assertEquals(AbandonmentResult.PENDING, f.request());
        UUID operation = f.repository.getPendingAbandonments().get(0).getOperationId();
        assertEquals(MountRepository.RenameStatus.BUSY,
                f.repository.rename(f.owner, f.record.getMountId(), f.revision(), "No"));
        assertFalse(f.repository.removeForNormalDeath(f.record.getPhysicalEntityId()));
        assertFalse(f.repository.prepareRecall(f.owner, f.record.getMountId(), f.record.getPhysicalEntityId()).isPresent());
        assertEquals(MountRepository.SelectionStatus.SUCCESS, f.repository.select(f.owner, other.getMountId(), f.revision()));
        f.world.fence = RecallWorldGateway.CheckpointStatus.VERIFIED;
        f.world.beforeFence = () -> assertEquals(operation, f.repository.getPendingAbandonments().get(0).getOperationId());
        assertEquals(AbandonmentResult.SUCCESS, f.request());
        assertEquals(other.getMountId(), f.repository.inspectCollection(f.owner).getSelectedMountId().get());
    }

    @Test void staleForeignUnavailableAndNonlivingRequestsDoNotCreateAnIntent() {
        Fixture f = new Fixture();
        assertEquals(AbandonmentResult.STALE, f.service.abandon(f.owner, f.record.getMountId(), 0));
        assertEquals(AbandonmentResult.NOT_OWNED, f.service.abandon(UUID.randomUUID(), f.record.getMountId(), f.revision()));
        assertEquals(0, f.world.lookups);
        assertEquals(AbandonmentResult.UNAVAILABLE, service(f.repository, f.world, false)
                .abandon(f.owner, f.record.getMountId(), f.revision()));
        f.world.valid = false;
        assertEquals(AbandonmentResult.UNAVAILABLE, f.request());
        f.world.valid = true;
        f.repository.enterRecovery(f.owner, f.record.getMountId(), f.record.getPhysicalEntityId(),
                new RecoveryState(f.record.getPhysicalEntityId(), LOCATION, new ProviderPayload(0, new NBTTagCompound()), 0, 0));
        assertEquals(AbandonmentResult.UNAVAILABLE, f.request());
        assertEquals(0, f.world.clears);
        assertTrue(f.repository.getPendingAbandonments().isEmpty());
    }

    @Test void missingOrConflictingPendingEvidenceNeverFinalizesAndLoadedHintIsReverified() {
        Fixture f = new Fixture(); f.world.fence = RecallWorldGateway.CheckpointStatus.FAILED;
        assertEquals(AbandonmentResult.PENDING, f.request());
        f.world.valid = false;
        f.service.reconcileAbandonment(f.record.getMountId(), new LastKnownEvidence(-1, 8, 64, 9));
        assertEquals(LOCATION, f.repository.getPendingAbandonments().get(0).getLocation());
        assertEquals(1, f.repository.getTotalRecordCount());
        f.world.valid = true; f.world.location = new LastKnownEvidence(-1, 8, 64, 9);
        f.world.fence = RecallWorldGateway.CheckpointStatus.VERIFIED;
        f.service.reconcileAbandonment(f.record.getMountId(), f.world.location);
        assertEquals(0, f.repository.getTotalRecordCount());
        assertFalse(f.world.bounded);
    }

    @Test void migrationAndUnknownDuplicateOrConflictingOperationsPreserveAuthority() {
        Fixture f = new Fixture();
        NBTTagCompound legacy = f.data.writeToNBT(new NBTTagCompound());
        legacy.setInteger("RootVersion", 7); legacy.removeTag("Abandonments");
        MountSavedData old = new MountSavedData(); old.readFromNBT(legacy);
        assertFalse(old.getRepository().isReadOnly());
        assertEquals(1, old.getRepository().getTotalRecordCount());
        f.world.fence = RecallWorldGateway.CheckpointStatus.FAILED; f.request();
        NBTTagCompound root = f.data.writeToNBT(new NBTTagCompound());
        for (int corruption = 0; corruption < 4; corruption++) {
            NBTTagCompound malformed = root.copy();
            NBTTagCompound op = malformed.getTagList("Abandonments", 10).getCompoundTagAt(0);
            if (corruption == 0) { op.setInteger("Version", 999); }
            if (corruption == 1) { malformed.getTagList("Abandonments", 10).appendTag(op.copy()); }
            if (corruption == 2) { op.setString("PhysicalId", UUID.randomUUID().toString()); }
            if (corruption == 3) { malformed.getTagList("Transfers", 10).appendTag(op.copy()); }
            MountSavedData preserved = new MountSavedData(); preserved.readFromNBT(malformed);
            assertTrue(preserved.getRepository().isReadOnly(), "corruption " + corruption);
            assertEquals(malformed, preserved.writeToNBT(new NBTTagCompound()));
        }
    }

    @Test void cleanupFailureDoesNotMisreportAcknowledgedRemoval() {
        Fixture f = new Fixture();
        f.world.failClose = true;
        assertEquals(AbandonmentResult.SUCCESS, f.request());
        assertEquals(0, f.repository.getTotalRecordCount());
        assertEquals(0, f.restart().getTotalRecordCount());
    }

    private static MountLifecycleService service(MountRepository repository, World world, boolean available) {
        ProviderRegistry providers = new ProviderRegistry();
        if (available) { providers.register(new MountProvider() {
            public ResourceLocation getProviderId() { return PROVIDER; }
            public boolean supports(Entity entity) { return true; }
            public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) {
                return ProviderResult.success(new RegistrationProfile(TYPE, TYPE.toString()));
            }
        }); }
        providers.freeze();
        return new MountLifecycleService(repository, providers, () -> null, new DiagnosticSink() {
            public void detail(DiagnosticCategory c, String e, Map<String, String> f) {}
            public void essentialWarning(String c, String v, String f) {}
            public void essentialLifecycleWarning(String e, String d) {}
        }, new ActiveServerClock(), new InhibitedIntegration(), world);
    }
    private static final class Fixture {
        final UUID owner = UUID.randomUUID();
        final MountSavedData data = new MountSavedData();
        final MountRepository repository = data.getRepository();
        final MountRecord record = register();
        final Io io = new Io();
        final World world = new World(repository);
        final MountLifecycleService service = service(repository, world, true);
        Fixture() {
            repository.setAcknowledgedPersistence(new AcknowledgedMountStore(data, io)::commit);
        }
        MountRecord register() {
            return repository.register(new MountRepository.RegistrationCandidate(owner, PROVIDER, TYPE,
                    TYPE.toString(), UUID.randomUUID(), LOCATION, null)).getRecord().get();
        }
        long revision() { return repository.inspectCollection(owner).getRevision(); }
        AbandonmentResult request() { return service.abandon(owner, record.getMountId(), revision()); }
        MountRepository restart() {
            MountSavedData restored = new MountSavedData();
            restored.readFromNBT(io.disk.getCompoundTag("data")); return restored.getRepository();
        }
    }
    private static final class Io implements AcknowledgedMountStore.StoreIo {
        NBTTagCompound disk = new NBTTagCompound();
        int writes; int failAt = -1; boolean persistFailedWrite; boolean failThereafter;
        boolean failing() { return writes == failAt || failThereafter && writes >= failAt; }
        public void write(NBTTagCompound root) {
            writes++;
            if (!failing() || persistFailedWrite) { disk = root.copy(); }
        }
        public NBTTagCompound read() throws IOException {
            if (failing()) { throw new IOException("injected read-back failure"); }
            return disk.copy();
        }
    }
    private static final class World implements RecallWorldGateway {
        final MountRepository repository;
        boolean marker = true; boolean valid = true; boolean bounded;
        boolean failClose;
        int clears; int closed; int lookups;
        LastKnownEvidence location = LOCATION;
        CheckpointStatus fence = CheckpointStatus.VERIFIED;
        Runnable beforeFence = () -> {};
        World(MountRepository repository) { this.repository = repository; }
        public Optional<AbandonmentTarget> locateAbandonment(MountRecord record, MountProvider provider,
                boolean boundedRetrieval, boolean pending, LastKnownEvidence hint) {
            lookups++; bounded = boundedRetrieval;
            return Optional.of(new AbandonmentTarget() {
                public LastKnownEvidence location() { return location; }
                public boolean verify() { return valid; }
                public CheckpointStatus clearAndFence(BooleanSupplier authority) {
                    assertTrue(authority.getAsBoolean());
                    assertEquals(MountCondition.OPERATION_IN_PROGRESS, repository.find(record.getMountId()).get().getCondition());
                    beforeFence.run(); clears++; marker = false; return fence;
                }
                public void close() {
                    closed++;
                    if (failClose) { throw new IllegalStateException("injected cleanup failure"); }
                }
            });
        }
        public LocateResult locate(EntityPlayerMP p, MountRecord r) { throw new AssertionError("recall lookup used"); }
        public boolean providerSupports(Source s, MountProvider p) { throw new AssertionError(); }
        public Optional<Destination> plan(EntityPlayerMP p, Source s, MountCharacteristics c, int n, int f) { throw new AssertionError(); }
        public boolean commit(EntityPlayerMP p, Source s, Destination d, MountProvider provider) { throw new AssertionError(); }
    }
}
