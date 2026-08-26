package com.mahghuuuls.mountcollection.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.ActiveServerClock;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration;
import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
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
        ProviderRegistry providers = new ProviderRegistry();
        providers.register(new TestProvider());
        providers.freeze();
        return new MountLifecycleService(
                repository, providers, MountLifecycleServiceTest::config,
                new NoOpDiagnostics(), clock, new InhibitedIntegration(), gateway);
    }

    private static ValidatedMountConfig config() {
        return new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                200L, 4, 16, true, 6000L, true, false);
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
                MountProvider provider, int normalRadius, int fallbackRadius) {
            planCalls++;
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

    private static final class NoOpDiagnostics implements DiagnosticSink {
        @Override
        public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {}

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}
    }
}
