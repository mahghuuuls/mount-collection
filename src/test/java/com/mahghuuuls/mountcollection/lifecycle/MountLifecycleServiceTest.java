package com.mahghuuuls.mountcollection.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import com.mahghuuuls.mountcollection.provider.ProviderRegistry;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
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

    private static final class NoOpDiagnostics implements DiagnosticSink {
        @Override
        public void detail(DiagnosticCategory category, String event, Map<String, String> fields) {}

        @Override
        public void essentialWarning(String category, String rejectedValue, String fallback) {}
    }
}
