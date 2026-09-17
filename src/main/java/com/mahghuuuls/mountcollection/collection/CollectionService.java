package com.mahghuuuls.mountcollection.collection;

import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-thread collection authority and explicitly allowlisted presentation. */
public final class CollectionService {
    private final MountRepository repository;
    private final com.mahghuuuls.mountcollection.provider.ProviderRegistry providers;
    private final com.mahghuuuls.mountcollection.policy.ValidatedMountConfig config;

    public CollectionService(MountRepository repository) {
        this(repository, null);
    }
    public CollectionService(MountRepository repository, com.mahghuuuls.mountcollection.provider.ProviderRegistry providers) {
        this(repository, providers, null);
    }
    public CollectionService(MountRepository repository, com.mahghuuuls.mountcollection.provider.ProviderRegistry providers,
            com.mahghuuuls.mountcollection.policy.ValidatedMountConfig config) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.providers = providers;
        this.config = config;
    }

    public CollectionView snapshot(UUID owner, long activeTick) {
        Objects.requireNonNull(owner, "owner");
        if (activeTick < 0) { throw new IllegalArgumentException("negative active tick"); }
        synchronized (repository) {
            MountRepository.CollectionInspection collection = repository.inspectCollection(owner);
            List<CollectionView.Entry> entries = new ArrayList<>();
            for (MountRecord record : repository.getOwnedRecords(owner)) {
                CollectionView.State state;
                switch (record.getCondition()) {
                    case LIVING: state = CollectionView.State.LIVING; break;
                    case RECOVERING: state = CollectionView.State.RECOVERING; break;
                    case READY_FOR_RECALL: state = CollectionView.State.READY; break;
                    case OPERATION_IN_PROGRESS: state = CollectionView.State.BUSY; break;
                    case PROVIDER_UNAVAILABLE: state = CollectionView.State.PROVIDER_UNAVAILABLE; break;
                    default: state = CollectionView.State.INTEGRITY_UNAVAILABLE;
                }
                long remaining = record.getRecoveryState() == null ? 0L
                        : Math.min(record.getRecoveryState().getDuration(),
                                Math.max(0L, record.getRecoveryState().getDeadline() - activeTick));
                entries.add(new CollectionView.Entry(record.getMountId(), record.getFallbackTypeKey(),
                        record.getFallbackOrdinal(), record.getRegistrationOrder(), state,
                        remaining, record.getCharacteristics(), record.getNaming().getCustomName() == null
                                ? "" : record.getNaming().getCustomName(), preview(record),
                        config != null && !config.allowsSummoning(record.getEntityTypeId(), record.getCharacteristics())));
            }
            return new CollectionView(collection.getRevision(),
                    collection.getSelectedMountId().orElse(null), entries);
        }
    }

    public MountRepository.SelectionStatus select(UUID owner, MountId target, long selectionRevision) {
        // Ownership, retained state and idempotence are revalidated atomically;
        // presentation lifecycle changes do not change the meaning of Select.
        return repository.select(owner, target, selectionRevision);
    }
    private com.mahghuuuls.mountcollection.api.MountPreview preview(MountRecord record) {
        if (providers == null || (record.getCondition() != com.mahghuuuls.mountcollection.persistence.MountCondition.LIVING
                && record.getCondition() != com.mahghuuuls.mountcollection.persistence.MountCondition.RECOVERING
                && record.getCondition() != com.mahghuuuls.mountcollection.persistence.MountCondition.READY_FOR_RECALL)) { return null; }
        try {
            com.mahghuuuls.mountcollection.api.MountProvider provider = providers.find(record.getProviderId()).orElse(null);
            com.mahghuuuls.mountcollection.api.MountPreview result = provider == null ? null : provider.describePreview(record.getEntityTypeId());
            return result != null && record.getProviderId().equals(result.getProviderId())
                    && record.getEntityTypeId().equals(result.getEntityType()) ? result : null;
        } catch (RuntimeException | LinkageError invalid) { return null; }
    }
    public MountRepository.RenameStatus rename(UUID owner, MountId target, long revision, String input) {
        return repository.rename(owner, target, revision, input);
    }
}
