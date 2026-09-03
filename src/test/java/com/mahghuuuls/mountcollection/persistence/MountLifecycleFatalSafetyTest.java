package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class MountLifecycleFatalSafetyTest {

    private static final ResourceLocation PROVIDER = new ResourceLocation("mountcollection", "test");
    private static final ResourceLocation HORSE = new ResourceLocation("minecraft", "horse");

    @Test
    void candidateIntentCloseRollbackAndQuarantineAcknowledgementFailureIsFatal() {
        Fixture fixture = candidateIntentFixture();
        fixture.gateway.candidatePresent = true;
        int[] commits = failAllAcknowledgements(fixture.repository);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.CANDIDATE_SPAWN_INTENT);
        assertEquals(3, commits[0]);
        assertEquals(1, fixture.gateway.removeCandidateCalls);
    }

    @Test
    void candidateContainmentRollbackAndQuarantineAcknowledgementFailureIsFatal() {
        Fixture fixture = candidateIntentFixture();
        fixture.gateway.candidatePresent = true;
        fixture.gateway.candidateCheckpoint = RecallWorldGateway.CheckpointStatus.FAILED;
        int[] commits = failAllAcknowledgements(fixture.repository);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.CANDIDATE_SPAWN_INTENT);
        assertEquals(2, commits[0]);
        assertEquals(1, fixture.gateway.removeCandidateCalls);
    }

    @Test
    void sourceIntentCloseAndQuarantineAcknowledgementFailureIsFatal() {
        Fixture fixture = sourceIntentFixture();
        fixture.gateway.sourcePresent = false;
        int[] commits = failAllAcknowledgements(fixture.repository);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), false, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.SOURCE_REMOVAL_INTENT);
        assertEquals(2, commits[0]);
        assertEquals(0, fixture.gateway.removeSourceCalls);
    }

    @Test
    void sourceIntentRollbackAndQuarantineAcknowledgementFailureIsFatal() {
        Fixture fixture = sourceIntentFixture();
        fixture.gateway.removeSourceResult =
                RecallWorldGateway.PhysicalAction.FAILED_RESTORED;
        int[] commits = failAllAcknowledgements(fixture.repository);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.SOURCE_REMOVAL_INTENT);
        assertEquals(2, commits[0]);
        assertEquals(1, fixture.gateway.removeSourceCalls);
    }

    @Test
    void sourceIntentUncertainCompensationQuarantineAcknowledgementFailureIsFatal() {
        Fixture fixture = sourceIntentFixture();
        fixture.gateway.removeSourceResult = RecallWorldGateway.PhysicalAction.FAILED;
        int[] commits = failAllAcknowledgements(fixture.repository);

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.SOURCE_REMOVAL_INTENT);
        assertEquals(1, commits[0]);
        assertEquals(1, fixture.gateway.removeSourceCalls);
    }

    @Test
    void unexpectedCandidateIntentInspectionExceptionIsFatal() {
        Fixture fixture = candidateIntentFixture();
        fixture.gateway.inspectFailure = new IllegalStateException("injected inspection failure");

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.CANDIDATE_SPAWN_INTENT);
    }

    @Test
    void unexpectedCandidateContainmentRemovalExceptionIsFatal() {
        Fixture fixture = candidateIntentFixture();
        fixture.gateway.candidatePresent = true;
        fixture.gateway.candidateCheckpoint = RecallWorldGateway.CheckpointStatus.FAILED;
        fixture.gateway.removeCandidateFailure =
                new IllegalStateException("injected candidate removal failure");

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.CANDIDATE_SPAWN_INTENT);
        assertEquals(1, fixture.gateway.removeCandidateCalls);
    }

    @Test
    void unexpectedSourceRemovalExceptionIsFatal() {
        Fixture fixture = sourceIntentFixture();
        fixture.gateway.removeSourceFailure =
                new IllegalStateException("injected source removal failure");

        FatalTransferSafetyException fatal = assertThrows(
                FatalTransferSafetyException.class,
                () -> fixture.service.reconcilePendingTransfer(
                        fixture.operation.getOperationId(), true, true));

        assertFatalIntentRetained(fixture, fatal, TransferPhase.SOURCE_REMOVAL_INTENT);
        assertEquals(1, fixture.gateway.removeSourceCalls);
    }

    private static Fixture candidateIntentFixture() {
        Fixture fixture = fixture();
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                fixture.repository.markCandidateSpawnIntent(fixture.operation.getOperationId()));
        return fixture;
    }

    private static Fixture sourceIntentFixture() {
        Fixture fixture = fixture();
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                fixture.repository.markCandidateSpawnIntent(fixture.operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                fixture.repository.markCandidateSpawned(fixture.operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                fixture.repository.associateTransferCandidate(fixture.operation.getOperationId()));
        assertEquals(MountRepository.TransferStatus.SUCCESS,
                fixture.repository.markSourceRemovalIntent(fixture.operation.getOperationId()));
        fixture.gateway.candidatePresent = true;
        return fixture;
    }

    private static Fixture fixture() {
        MountRepository repository = new MountRepository();
        UUID owner = UUID.randomUUID();
        MountRecord record = repository.register(new MountRepository.RegistrationCandidate(
                owner, PROVIDER, HORSE, HORSE.toString(), UUID.randomUUID(),
                new LastKnownEvidence(0, 1.0D, 64.0D, 1.0D), null)).getRecord().get();
        UUID candidateId = UUID.randomUUID();
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("id", HORSE.toString());
        snapshot.setUniqueId("UUID", record.getPhysicalEntityId());
        TransferOperation operation = new TransferOperation(
                UUID.randomUUID(), record.getMountId(), owner, record.getPhysicalEntityId(),
                candidateId, record.getLastKnown(),
                new LastKnownEvidence(1, 9.0D, 64.0D, 9.0D), snapshot,
                200L, 200L, TransferPhase.PREPARED, null);
        assertEquals(MountRepository.TransferStatus.SUCCESS, repository.beginTransfer(operation));
        FatalTestGateway gateway = new FatalTestGateway();
        ProviderRegistry providers = new ProviderRegistry();
        providers.freeze();
        MountLifecycleService service = new MountLifecycleService(
                repository, providers, MountLifecycleFatalSafetyTest::config,
                new NoOpDiagnostics(), new ActiveServerClock(),
                new InhibitedIntegration(), gateway);
        return new Fixture(repository, operation, gateway, service);
    }

    private static int[] failAllAcknowledgements(MountRepository repository) {
        int[] commits = {0};
        repository.setAcknowledgedPersistence(snapshot -> {
            commits[0]++;
            return false;
        });
        return commits;
    }

    private static void assertFatalIntentRetained(
            Fixture fixture,
            FatalTransferSafetyException fatal,
            TransferPhase expectedPhase) {
        assertEquals(fixture.operation.getOperationId(), fatal.getOperationId());
        assertEquals(expectedPhase, fatal.getPhase());
        assertEquals(expectedPhase, fixture.repository
                .findTransfer(fixture.operation.getOperationId()).get().getPhase());
        assertEquals(MountCondition.OPERATION_IN_PROGRESS, fixture.repository
                .find(fixture.operation.getMountId()).get().getCondition());
    }

    private static ValidatedMountConfig config() {
        return new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                200L, 4, 16, true, 6000L, true, false);
    }

    private static final class Fixture {
        private final MountRepository repository;
        private final TransferOperation operation;
        private final FatalTestGateway gateway;
        private final MountLifecycleService service;

        private Fixture(
                MountRepository repository,
                TransferOperation operation,
                FatalTestGateway gateway,
                MountLifecycleService service) {
            this.repository = repository;
            this.operation = operation;
            this.gateway = gateway;
            this.service = service;
        }
    }

    private static final class FatalTestGateway implements RecallWorldGateway {
        private boolean sourcePresent = true;
        private boolean candidatePresent;
        private CheckpointStatus candidateCheckpoint = CheckpointStatus.VERIFIED;
        private PhysicalAction removeSourceResult = PhysicalAction.SUCCESS;
        private RuntimeException inspectFailure;
        private RuntimeException removeCandidateFailure;
        private RuntimeException removeSourceFailure;
        private int removeCandidateCalls;
        private int removeSourceCalls;

        @Override
        public LocateResult locate(EntityPlayerMP player, MountRecord record) {
            return LocateResult.missing();
        }

        @Override
        public boolean providerSupports(Source source, com.mahghuuuls.mountcollection.api.MountProvider provider) {
            return false;
        }

        @Override
        public Optional<Destination> plan(
                EntityPlayerMP player,
                Source source,
                com.mahghuuuls.mountcollection.api.MountProvider provider,
                int normalRadius,
                int fallbackRadius) {
            return Optional.empty();
        }

        @Override
        public boolean commit(
                EntityPlayerMP player,
                Source source,
                Destination destination,
                com.mahghuuuls.mountcollection.api.MountProvider provider) {
            return false;
        }

        @Override
        public TransferEvidence inspectTransfer(TransferOperation operation) {
            if (inspectFailure != null) {
                throw inspectFailure;
            }
            return new TransferEvidence(
                    sourcePresent ? TransferEvidence.Presence.EXACT : TransferEvidence.Presence.MISSING,
                    candidatePresent ? TransferEvidence.Presence.EXACT : TransferEvidence.Presence.MISSING);
        }

        @Override
        public CheckpointStatus checkpointCandidate(
                TransferOperation operation, boolean operationMarkerExpected) {
            return candidateCheckpoint;
        }

        @Override
        public PhysicalAction removeCandidate(TransferOperation operation) {
            removeCandidateCalls++;
            if (removeCandidateFailure != null) {
                throw removeCandidateFailure;
            }
            candidatePresent = false;
            return PhysicalAction.SUCCESS;
        }

        @Override
        public CheckpointStatus checkpointCandidateAbsent(TransferOperation operation) {
            return candidatePresent ? CheckpointStatus.FAILED : CheckpointStatus.VERIFIED;
        }

        @Override
        public PhysicalAction removeSource(TransferOperation operation) {
            removeSourceCalls++;
            if (removeSourceFailure != null) {
                throw removeSourceFailure;
            }
            if (removeSourceResult == PhysicalAction.SUCCESS) {
                sourcePresent = false;
            }
            return removeSourceResult;
        }

        @Override
        public CheckpointStatus checkpointSourceAbsent(TransferOperation operation) {
            return sourcePresent ? CheckpointStatus.FAILED : CheckpointStatus.VERIFIED;
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
}
