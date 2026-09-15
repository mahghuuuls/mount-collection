package com.mahghuuuls.mountcollection.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import java.util.UUID;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class CollectionServiceTest {
    @Test void optionalPreviewProviderIsIsolatedFromCollectionAuthority() {
        MountRepository repository = new MountRepository(); UUID owner = UUID.randomUUID(); register(repository, owner);
        com.mahghuuuls.mountcollection.provider.ProviderRegistry providers = new com.mahghuuuls.mountcollection.provider.ProviderRegistry();
        providers.register(new com.mahghuuuls.mountcollection.provider.vanilla.VanillaMountProvider()); providers.freeze();
        CollectionView view = new CollectionService(repository, providers).snapshot(owner, 0);
        org.junit.jupiter.api.Assertions.assertNotNull(view.getEntries().get(0).getPreview());
        com.mahghuuuls.mountcollection.provider.ProviderRegistry broken = new com.mahghuuuls.mountcollection.provider.ProviderRegistry();
        broken.register(new com.mahghuuuls.mountcollection.api.MountProvider() {
            public ResourceLocation getProviderId() { return new ResourceLocation("mountcollection:vanilla"); }
            public boolean supports(net.minecraft.entity.Entity entity) { return false; }
            public com.mahghuuuls.mountcollection.api.ProviderResult<com.mahghuuuls.mountcollection.api.RegistrationProfile>
                    validateRegistration(net.minecraft.entity.Entity entity, UUID player) { throw new AssertionError("not registration"); }
            public com.mahghuuuls.mountcollection.api.MountPreview describePreview(ResourceLocation type) { throw new IllegalArgumentException("oversized"); }
        }); broken.freeze();
        CollectionView fallback = new CollectionService(repository, broken).snapshot(owner, 0);
        org.junit.jupiter.api.Assertions.assertNull(fallback.getEntries().get(0).getPreview());
        assertEquals(view.getSelected(), fallback.getSelected()); assertEquals(view.getSelectionRevision(), fallback.getSelectionRevision());
    }
    @Test void projectionIsOwnerScopedFrozenAndNeverClearsQuarantineOnSelection() {
        MountRepository repository = new MountRepository();
        CollectionService service = new CollectionService(repository);
        UUID owner = UUID.randomUUID();
        MountRecord first = register(repository, owner);
        MountRecord second = register(repository, owner);
        MountRecord other = register(repository, UUID.randomUUID());
        CollectionView frozen = service.snapshot(owner, 0L);
        assertEquals(2, frozen.getEntries().size());
        assertEquals(second.getMountId(), frozen.getSelected());
        assertThrows(UnsupportedOperationException.class, () -> frozen.getEntries().clear());
        repository.reportMalformedEvidence(first.getPhysicalEntityId());
        assertEquals(CollectionView.State.LIVING, frozen.getEntries().get(0).getState());
        assertEquals(CollectionView.State.INTEGRITY_UNAVAILABLE, service.snapshot(owner, 0L).getEntries().get(0).getState());
        assertEquals(MountRepository.SelectionStatus.NOT_OWNED,
                service.select(owner, other.getMountId(), frozen.getSelectionRevision()));
        assertEquals(MountRepository.SelectionStatus.SUCCESS,
                service.select(owner, first.getMountId(), frozen.getSelectionRevision()));
        assertEquals(CollectionView.State.INTEGRITY_UNAVAILABLE, service.snapshot(owner, 0L).getEntries().get(0).getState());
        assertEquals(first.getMountId(), service.snapshot(owner, 0L).getSelected());
        assertEquals(second.getMountId(), frozen.getSelected());
    }

    private MountRecord register(MountRepository repository, UUID owner) {
        return repository.register(new MountRepository.RegistrationCandidate(owner,
                new ResourceLocation("mountcollection:vanilla"), new ResourceLocation("minecraft:horse"),
                "minecraft:horse", UUID.randomUUID(), new LastKnownEvidence(0, 0, 64, 0), null))
                .getRecord().get();
    }
}
