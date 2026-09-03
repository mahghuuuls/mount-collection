package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AcknowledgedMountStoreTest {

    @Test
    void exactRevisionAndOperationFactsAcknowledgeTheMutation() {
        Fixture fixture = new Fixture(Fault.NONE);

        assertEquals(
                MountRepository.TransferStatus.SUCCESS,
                fixture.repository.beginTransfer(fixture.operation));

        assertEquals(1L, fixture.repository.getStoreRevision());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS,
                fixture.repository.find(fixture.record.getMountId()).get().getCondition());
        assertTrue(fixture.repository.findTransfer(fixture.operation.getOperationId()).isPresent());
        assertFalse(fixture.savedData.isDirty());
    }

    @Test
    void everyUnacknowledgedWriteResultRollsBackFactsAndRestoresDirtyState() {
        for (Fault fault : new Fault[] {
                Fault.NO_WRITE, Fault.STALE_REVISION, Fault.WRONG_FACTS,
                Fault.WRITE_FAILURE, Fault.READ_FAILURE}) {
            Fixture fixture = new Fixture(fault);

            assertEquals(
                    MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                    fixture.repository.beginTransfer(fixture.operation), fault.name());
            assertEquals(1L, fixture.repository.getStoreRevision(), fault.name());
            assertEquals(MountCondition.LIVING,
                    fixture.repository.find(fixture.record.getMountId()).get().getCondition(),
                    fault.name());
            assertFalse(
                    fixture.repository.findTransfer(fixture.operation.getOperationId()).isPresent(),
                    fault.name());
            assertTrue(fixture.savedData.isDirty(), fault.name());
            assertEquals(1, fixture.failures.size(), fault.name());
            assertEquals(expectedStage(fault), fixture.failures.get(0).stage, fault.name());
        }
    }

    @Test
    void developmentAcknowledgementFaultIsOneShotAndDoesNotTouchTheStore() {
        AtomicBoolean armed = new AtomicBoolean(true);
        Fixture fixture = new Fixture(Fault.NONE, () -> armed.getAndSet(false));

        assertEquals(
                MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                fixture.repository.beginTransfer(fixture.operation));
        assertEquals(0, fixture.io.writeCount);
        assertEquals(0, fixture.io.readCount);
        assertTrue(fixture.savedData.isDirty());
        assertEquals(AcknowledgedMountStore.FailureStage.DEVELOPMENT_FAULT,
                fixture.failures.get(0).stage);
        assertEquals("injected", fixture.failures.get(0).cause);

        assertEquals(
                MountRepository.TransferStatus.SUCCESS,
                fixture.repository.beginTransfer(fixture.operation));
        assertEquals(1, fixture.io.writeCount);
        assertEquals(1, fixture.io.readCount);
        assertEquals(1, fixture.failures.size());
    }

    @Test
    void failureDiagnosticsExposeOnlyBoundedStageAndExceptionType() {
        Fixture writeFailure = new Fixture(Fault.WRITE_FAILURE);
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                writeFailure.repository.beginTransfer(writeFailure.operation));
        assertEquals("IOException", writeFailure.failures.get(0).cause);
        assertFalse(writeFailure.failures.get(0).cause.contains("injected write failure"));

        Fixture readFailure = new Fixture(Fault.READ_FAILURE);
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                readFailure.repository.beginTransfer(readFailure.operation));
        assertEquals("IOException", readFailure.failures.get(0).cause);

        Fixture mismatch = new Fixture(Fault.WRONG_FACTS);
        assertEquals(MountRepository.TransferStatus.PERSISTENCE_FAILURE,
                mismatch.repository.beginTransfer(mismatch.operation));
        assertEquals("read_back_not_equal", mismatch.failures.get(0).cause);
    }

    @Test
    void realCompressedFileIsFinalizedSyncedAndReadBack(@TempDir Path temporaryDirectory) {
        MountSavedData savedData = new MountSavedData("test");
        MountRepository repository = savedData.getRepository();
        repository.register(new MountRepository.RegistrationCandidate(
                UUID.randomUUID(),
                new ResourceLocation("mountcollection:vanilla"),
                new ResourceLocation("minecraft:horse"),
                "minecraft:horse",
                UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D),
                null));
        File target = temporaryDirectory.resolve("mountcollection_mounts.dat").toFile();
        List<Failure> failures = new ArrayList<>();
        AcknowledgedMountStore store = new AcknowledgedMountStore(
                target,
                savedData,
                () -> false,
                (stage, cause) -> failures.add(new Failure(stage, cause)));

        assertTrue(store.commit(repository.snapshot()));
        assertTrue(target.isFile());
        assertTrue(target.length() > 0L);
        assertFalse(new File(
                target.getParentFile(),
                target.getName() + ".mountcollection.tmp").exists());
        assertFalse(savedData.isDirty());
        assertTrue(failures.isEmpty());
    }

    private static AcknowledgedMountStore.FailureStage expectedStage(Fault fault) {
        switch (fault) {
            case WRITE_FAILURE:
                return AcknowledgedMountStore.FailureStage.STORE_WRITE;
            case READ_FAILURE:
                return AcknowledgedMountStore.FailureStage.READ_BACK;
            default:
                return AcknowledgedMountStore.FailureStage.CONTENT_MISMATCH;
        }
    }

    private enum Fault {
        NONE,
        NO_WRITE,
        STALE_REVISION,
        WRONG_FACTS,
        WRITE_FAILURE,
        READ_FAILURE
    }

    private static final class Fixture {
        private final MountSavedData savedData = new MountSavedData("test");
        private final MountRepository repository = savedData.getRepository();
        private final MountRecord record;
        private final TransferOperation operation;
        private final FaultIo io;
        private final List<Failure> failures = new ArrayList<>();

        private Fixture(Fault fault) {
            this(fault, () -> false);
        }

        private Fixture(Fault fault, BooleanSupplier acknowledgementFault) {
            UUID owner = UUID.randomUUID();
            record = repository.register(new MountRepository.RegistrationCandidate(
                    owner,
                    new ResourceLocation("mountcollection:vanilla"),
                    new ResourceLocation("minecraft:horse"),
                    "minecraft:horse",
                    UUID.randomUUID(),
                    new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D),
                    null)).getRecord().get();
            NBTTagCompound snapshot = new NBTTagCompound();
            snapshot.setString("id", "minecraft:horse");
            snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
            operation = new TransferOperation(
                    UUID.randomUUID(),
                    record.getMountId(),
                    owner,
                    record.getPhysicalEntityId(),
                    UUID.randomUUID(),
                    record.getLastKnown(),
                    new LastKnownEvidence(1, 8.0D, 64.0D, 8.0D),
                    snapshot,
                    200L,
                    200L,
                    TransferPhase.PREPARED,
                    null);
            io = new FaultIo(fault);
            AcknowledgedMountStore store = new AcknowledgedMountStore(
                    savedData,
                    io,
                    acknowledgementFault,
                    (stage, cause) -> failures.add(new Failure(stage, cause)));
            repository.setAcknowledgedPersistence(store::commit);
        }
    }

    private static final class Failure {
        private final AcknowledgedMountStore.FailureStage stage;
        private final String cause;

        private Failure(AcknowledgedMountStore.FailureStage stage, String cause) {
            this.stage = stage;
            this.cause = cause;
        }
    }

    private static final class FaultIo implements AcknowledgedMountStore.StoreIo {
        private final Fault fault;
        private NBTTagCompound persisted = new NBTTagCompound();
        private int writeCount;
        private int readCount;

        private FaultIo(Fault fault) {
            this.fault = fault;
        }

        @Override
        public void write(NBTTagCompound root) throws IOException {
            writeCount++;
            if (fault == Fault.WRITE_FAILURE) {
                throw new IOException("injected write failure");
            }
            if (fault == Fault.NO_WRITE) {
                return;
            }
            persisted = root.copy();
            if (fault == Fault.STALE_REVISION) {
                NBTTagCompound data = persisted.getCompoundTag("data");
                data.setLong("StoreRevision", Math.max(0L, data.getLong("StoreRevision") - 1L));
            } else if (fault == Fault.WRONG_FACTS) {
                persisted.getCompoundTag("data").removeTag("Transfers");
            }
        }

        @Override
        public NBTTagCompound read() throws IOException {
            readCount++;
            if (fault == Fault.READ_FAILURE) {
                throw new IOException("injected read failure");
            }
            return persisted.copy();
        }
    }
}
