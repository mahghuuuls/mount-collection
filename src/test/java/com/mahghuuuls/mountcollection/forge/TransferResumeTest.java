package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.*;
import static com.mahghuuuls.mountcollection.forge.TransferDevelopmentControls.ResumeResult.*;

import com.mahghuuuls.mountcollection.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class TransferResumeTest {
    private final MountRepository repository = new MountRepository();
    private final AtomicBoolean development = new AtomicBoolean(true);
    private final TransferDevelopmentControls controls = new TransferDevelopmentControls(development::get);
    private final List<Runnable> queued = new ArrayList<>();
    private final List<UUID> resumed = new ArrayList<>();

    private TransferDevelopmentControls.ResumeResult submit(String id) {
        return controls.queueTransferResume(id, () -> repository, queued::add,
                operation -> resumed.add(operation.getOperationId()));
    }

    @Test void gatesEnvironmentUuidRepositoryAndPhaseBeforeQueueing() {
        UUID id = operation(false);
        development.set(false);
        assertEquals(UNAVAILABLE, submit(id.toString()));
        development.set(true);
        assertEquals(INVALID_ID, submit("not-a-uuid"));
        assertEquals(INVALID_ID, submit("1-1-1-1-1"));
        assertEquals(MISSING, submit(UUID.randomUUID().toString()));
        assertEquals(WRONG_PHASE, submit(id.toString()));
        assertEquals(UNAVAILABLE, controls.queueTransferResume(id.toString(), () -> null,
                queued::add, ignored -> fail("must not resume")));
        assertTrue(queued.isEmpty());
    }

    @Test void queuesOnlyExactOperationAndDoesNotRunInline() {
        UUID first = operation(true), second = operation(true);
        assertEquals(QUEUED, submit(first.toString()));
        assertTrue(resumed.isEmpty());
        queued.get(0).run();
        assertEquals(java.util.Collections.singletonList(first), resumed);
        assertEquals(TransferPhase.SOURCE_REMOVED, repository.findTransfer(second).get().getPhase());
    }

    @Test void reportsRejectedExecutorAdmissionWithoutRunning() {
        UUID id = operation(true);
        assertEquals(QUEUE_REJECTED, controls.queueTransferResume(id.toString(), () -> repository,
                ignored -> false, ignored -> fail("must not resume")));
    }

    @Test void rechecksMissingAndBlockedOperationAtExecution() {
        UUID removed = operation(true), blocked = operation(true);
        assertEquals(QUEUED, submit(removed.toString()));
        assertEquals(QUEUED, submit(blocked.toString()));
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.finishTransfer(removed));
        repository.blockTransfer(blocked, "test conflict");
        queued.forEach(Runnable::run);
        assertTrue(resumed.isEmpty());
        assertEquals(WRONG_PHASE, submit(blocked.toString()));
    }

    @Test void queuedWorkCannotCrossRepositoryOrDevelopmentLifetime() {
        UUID id = operation(true);
        MountRepository[] active = {repository};
        assertEquals(QUEUED, controls.queueTransferResume(id.toString(), () -> active[0],
                queued::add, ignored -> fail("stale repository")));
        active[0] = new MountRepository();
        queued.get(0).run();
        assertEquals(QUEUED, submit(id.toString()));
        development.set(false);
        queued.get(1).run();
        assertTrue(resumed.isEmpty());
    }

    private UUID operation(boolean sourceRemoved) {
        ResourceLocation horse = new ResourceLocation("minecraft:horse");
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                UUID.randomUUID(), new ResourceLocation("mountcollection:vanilla"), horse,
                horse.toString(), UUID.randomUUID(), new LastKnownEvidence(0, 1, 64, 1), null))
                .getRecord().get();
        UUID id = UUID.randomUUID();
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", horse.toString());
        TransferOperation operation = new TransferOperation(id, record.getMountId(), record.getOwnerId(),
                record.getPhysicalEntityId(), UUID.randomUUID(), record.getLastKnown(),
                new LastKnownEvidence(-1, 1, 64, 1), snapshot, 0, 0, TransferPhase.PREPARED, null);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        if (sourceRemoved) {
            assertEquals(MountRepository.TransferStatus.SUCCESS, repository.markCandidateSpawnIntent(id));
            assertEquals(MountRepository.TransferStatus.SUCCESS, repository.markCandidateSpawned(id));
            assertEquals(MountRepository.TransferStatus.SUCCESS, repository.associateTransferCandidate(id));
            assertEquals(MountRepository.TransferStatus.SUCCESS, repository.markSourceRemovalIntent(id));
            assertEquals(MountRepository.TransferStatus.SUCCESS, repository.markTransferSourceRemoved(id));
        }
        return id;
    }
}
