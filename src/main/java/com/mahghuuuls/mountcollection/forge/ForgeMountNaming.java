package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.MountCondition;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Uses only loaded-world UUID indexes; never resolves a chunk for naming. */
public final class ForgeMountNaming {
    private final MountCollectionServices services;
    private final java.util.function.Function<UUID, Entity> loadedLookup;
    private int cursor;
    public ForgeMountNaming(MountCollectionServices services) { this(services, ForgeMountNaming::findLoaded); }
    ForgeMountNaming(MountCollectionServices services, java.util.function.Function<UUID, Entity> loadedLookup) {
        this.services = services; this.loadedLookup = loadedLookup;
    }

    public void reset() { cursor = 0; }
    public void tick() {
        MountRepository repository = services.getActiveRepository().orElse(null);
        if (repository == null || services.getActiveServerClock().now() % 20 != 0) { return; }
        List<MountRecord> candidates = repository.getNamingCandidates();
        if (candidates.isEmpty()) { cursor = 0; return; }
        for (int count = 0; count < Math.min(16, candidates.size()); count++) {
            MountRecord record = candidates.get(Math.floorMod(cursor++, candidates.size()));
            Entity entity = loadedLookup.apply(record.getPhysicalEntityId());
            if (entity != null) { reconcile(entity); }
        }
    }
    static Entity findLoaded(UUID id) {
        for (WorldServer world : DimensionManager.getWorlds()) {
            Entity entity = world.getEntityFromUuid(id);
            if (entity != null) { return entity; }
        }
        return null;
    }
    private MountRecord verified(MountRepository repository, Entity entity) {
        if (entity == null || entity.world == null || entity.world.isRemote || entity.isDead || repository.isReadOnly()) { return null; }
        MountRecord record = repository.findByPhysicalEntity(entity.getUniqueID()).orElse(null);
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
        return record != null && record.getCondition() == MountCondition.LIVING
                && record.getEntityTypeId().equals(EntityList.getKey(entity))
                && evidence.getStatus() == EntityMountEvidence.Status.VALID
                && record.getMountId().equals(evidence.getMountId().orElse(null)) ? record : null;
    }
    public void reconcile(Entity entity) {
        MountRepository repository = services.getActiveRepository().orElse(null);
        if (repository == null) { return; }
        MountRecord record = verified(repository, entity);
        if (record == null) { return; }
        String name = record.getNaming().getCustomName();
        if (name == null) {
            MountRepository.RenameStatus result = repository.observeNativeName(record.getMountId(), entity.getUniqueID(),
                    record.getNaming().getRevision(), entity.getCustomNameTag());
            if (result == MountRepository.RenameStatus.SUCCESS) {
                repository.find(record.getMountId()).ifPresent(saved -> diagnostic("name_initial_observation", saved));
            }
        } else {
            if (!name.equals(entity.getCustomNameTag())) { entity.setCustomNameTag(name); }
            if (name.equals(entity.getCustomNameTag()) && record.getNaming().isPending()) {
                if (repository.acknowledgeNameApplied(record.getMountId(), entity.getUniqueID(), record.getNaming().getRevision())) {
                    diagnostic("name_applied", record);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onNameTag(PlayerInteractEvent.EntityInteract event) {
        if (event.getWorld().isRemote || event.isCanceled()) { return; }
        ItemStack stack = event.getItemStack();
        if (stack.getItem() != Items.NAME_TAG || !stack.hasDisplayName()) { return; }
        MountRepository repository = services.getActiveRepository().orElse(null);
        if (repository == null) { return; }
        Entity target = event.getTarget();
        MountRecord record = verified(repository, target);
        // Native interaction permissions belong to Minecraft; collection actions remain owner-only.
        if (record == null || record.getNaming().isPending()) { return; }
        String before = target.getCustomNameTag();
        String expected = stack.getDisplayName();
        UUID physical = target.getUniqueID();
        // END execution follows native ItemNameTag mutation; only immutable identity/text is retained.
        services.submitLifecycleMutation(() -> {
            if (services.getActiveRepository().orElse(null) != repository) { return; }
            Entity after = loadedLookup.apply(physical);
            MountRecord current = verified(repository, after);
            if (current != null && current.getMountId().equals(record.getMountId())
                    && !before.equals(after.getCustomNameTag()) && expected.equals(after.getCustomNameTag())) {
                MountRepository.RenameStatus result = repository.observeNativeName(record.getMountId(), physical, record.getNaming().getRevision(), expected);
                if (result == MountRepository.RenameStatus.SUCCESS) {
                    repository.find(record.getMountId()).ifPresent(saved -> diagnostic("name_tag_observed", saved));
                }
            }
        });
    }
    private void diagnostic(String event, MountRecord record) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("mount", record.getMountId().toString());
        fields.put("name_revision", Long.toString(record.getNaming().getRevision()));
        services.getDiagnostics().detail(com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.COLLECTION, event, fields);
    }
}
