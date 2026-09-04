package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.api.PreparationSupport;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory;
import com.mahghuuuls.mountcollection.diagnostics.DiagnosticSink;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mahghuuuls.mountcollection.policy.PlacementSearch;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;

/** Minecraft 1.12.2-specific bounded lookup and same-world placement adapter. */
public final class ForgeRecallWorldGateway implements RecallWorldGateway {

    interface ChunkPersistence {
        void drain() throws Exception;
        void save(WorldServer world, Chunk chunk) throws Exception;
        NBTTagCompound read(WorldServer world, Chunk chunk) throws Exception;
    }

    interface SourceRemovalAccess {
        Object getVehicle();
        boolean isRiding();
        boolean isRiding(Object vehicle);
        void dismount();
        boolean startRiding(Object vehicle);
        boolean isLeashed();
        Object getLeashHolder();
        void clearLeash();
        void restoreLeash(Object holder);
        boolean isLeashedTo(Object holder);
        void remove();
        boolean isRemoved();
        void dropLead();
    }

    interface CandidateRemovalAccess {
        boolean isExact();
        void remove();
        boolean isRemoved();
    }

    interface PlacementProbe {
        boolean isWithinVerticalBounds();
        boolean isInsideWorldBorder();
        boolean areChunksLoaded();
        boolean isBlockCollisionFree();
        boolean isEntityCollisionFree();
        boolean isHazardFree();
        boolean doesCompleteVolumeMatch(PlacementProfile profile);
        boolean hasSolidSupport();
        boolean containsLiquid();
    }

    private final ChunkPersistence chunkPersistence;
    private final DiagnosticSink diagnostics;
    private final LongSupplier nanoTime;
    private final BooleanSupplier physicalFencePostDrainFault;
    private final Predicate<TransferPhase> pauseControl;
    private final Consumer<TransferPhase> acknowledgedPhaseControl;

    public ForgeRecallWorldGateway(DiagnosticSink diagnostics) {
        this(diagnostics, () -> false, ignored -> false, ignored -> {});
    }

    public ForgeRecallWorldGateway(
            DiagnosticSink diagnostics,
            BooleanSupplier physicalFencePostDrainFault,
            Predicate<TransferPhase> pauseControl,
            Consumer<TransferPhase> acknowledgedPhaseControl) {
        this(
                new AnvilChunkPersistence(),
                diagnostics,
                System::nanoTime,
                physicalFencePostDrainFault,
                pauseControl,
                acknowledgedPhaseControl);
    }

    ForgeRecallWorldGateway(ChunkPersistence chunkPersistence) {
        this(chunkPersistence, null, System::nanoTime, () -> false, ignored -> false, ignored -> {});
    }

    ForgeRecallWorldGateway(
            ChunkPersistence chunkPersistence,
            DiagnosticSink diagnostics,
            LongSupplier nanoTime) {
        this(chunkPersistence, diagnostics, nanoTime, () -> false, ignored -> false, ignored -> {});
    }

    ForgeRecallWorldGateway(
            ChunkPersistence chunkPersistence,
            DiagnosticSink diagnostics,
            LongSupplier nanoTime,
            BooleanSupplier physicalFencePostDrainFault,
            Predicate<TransferPhase> pauseControl) {
        this(
                chunkPersistence,
                diagnostics,
                nanoTime,
                physicalFencePostDrainFault,
                pauseControl,
                ignored -> {});
    }

    ForgeRecallWorldGateway(
            ChunkPersistence chunkPersistence,
            DiagnosticSink diagnostics,
            LongSupplier nanoTime,
            BooleanSupplier physicalFencePostDrainFault,
            Predicate<TransferPhase> pauseControl,
            Consumer<TransferPhase> acknowledgedPhaseControl) {
        this.chunkPersistence = java.util.Objects.requireNonNull(
                chunkPersistence, "chunkPersistence");
        this.diagnostics = diagnostics;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        this.physicalFencePostDrainFault = java.util.Objects.requireNonNull(
                physicalFencePostDrainFault, "physicalFencePostDrainFault");
        this.pauseControl = java.util.Objects.requireNonNull(pauseControl, "pauseControl");
        this.acknowledgedPhaseControl = java.util.Objects.requireNonNull(
                acknowledgedPhaseControl, "acknowledgedPhaseControl");
    }

    private final PlacementSearch placementSearch = new PlacementSearch();

    @Override
    public LocateResult locate(EntityPlayerMP player, MountRecord record) {
        if (record.getPhysicalEntityId() == null || record.getLastKnown() == null) {
            return LocateResult.missing();
        }
        WorldServer world = resolveWorld(record.getLastKnown().getDimensionId());
        if (world == null) {
            return LocateResult.missing();
        }
        Entity entity = world.getEntityFromUuid(record.getPhysicalEntityId());
        Chunk loadedForLookup = null;
        if (entity == null) {
            ChunkAccess access = loadExistingChunk(world, record.getLastKnown());
            if (access.status == TransferEvidence.Presence.UNAVAILABLE) {
                return LocateResult.unavailable();
            }
            if (access.status == TransferEvidence.Presence.MISSING) {
                return LocateResult.missing();
            }
            loadedForLookup = access.release ? access.chunk : null;
            // WorldServer may reject an entity during the chunk-load join event. Only the world's
            // authoritative UUID index proves that the entity actually joined the active world.
            entity = world.getEntityFromUuid(record.getPhysicalEntityId());
        }
        if (loadedForLookup != null) {
            world.getChunkProvider().queueUnload(loadedForLookup);
        }
        if (entity == null || entity.isDead) {
            return LocateResult.missing();
        }
        if (!isExactLivingEntity(entity, record)) {
            return LocateResult.integrityConflict();
        }
        return LocateResult.found(new Source(
                entity.getUniqueID(), entity.dimension, entity.isBeingRidden(), entity));
    }

    @Override
    public boolean providerSupports(Source source, MountProvider provider) {
        try {
            return provider.supports(entity(source));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public Optional<Destination> plan(
            EntityPlayerMP player,
            Source source,
            MountCharacteristics characteristics,
            int normalRadius,
            int fallbackRadius) {
        Entity entity = entity(source);
        PlacementProfile profile = characteristics.getPlacementProfile();
        BlockPos origin = player.getPosition();
        return placementSearch.find(normalRadius, fallbackRadius, (dx, dy, dz) -> safe(
                        player.getServerWorld(),
                        entity,
                        profile,
                        origin.getX() + dx + 0.5D,
                        origin.getY() + dy,
                        origin.getZ() + dz + 0.5D))
                .map(offset -> new Destination(new LastKnownEvidence(
                        player.dimension,
                        origin.getX() + offset.getX() + 0.5D,
                        origin.getY() + offset.getY(),
                        origin.getZ() + offset.getZ() + 0.5D)));
    }

    @Override
    public boolean commit(
            EntityPlayerMP player, Source source, Destination destination, MountProvider provider) {
        Entity entity = entity(source);
        if (entity.isDead || entity.isBeingRidden() || entity.dimension != player.dimension) {
            return false;
        }
        if (provider instanceof PreparationSupport) {
            ProviderResult<Void> prepared;
            try {
                prepared = ((PreparationSupport) provider).prepareForPlacement(entity);
            } catch (RuntimeException exception) {
                return false;
            }
            if (prepared == null || !prepared.isSuccess()) {
                return false;
            }
        }
        if (entity.isRiding()) {
            entity.dismountRidingEntity();
            if (entity.isRiding()) {
                return false;
            }
        }
        if (entity instanceof EntityLiving && ((EntityLiving) entity).getLeashed()) {
            ((EntityLiving) entity).clearLeashed(true, true);
        }
        LastKnownEvidence target = destination.getEvidence();
        float yaw = (float) (Math.toDegrees(Math.atan2(
                player.posZ - target.getZ(), player.posX - target.getX())) - 90.0D);
        entity.setLocationAndAngles(target.getX(), target.getY(), target.getZ(), yaw, entity.rotationPitch);
        entity.motionX = 0.0D;
        entity.motionY = 0.0D;
        entity.motionZ = 0.0D;
        entity.fallDistance = 0.0F;
        if (entity instanceof EntityCreature) {
            ((EntityCreature) entity).getNavigator().clearPath();
        }
        return true;
    }

    @Override
    public Optional<TransferPlan> captureTransfer(
            EntityPlayerMP player,
            Source source,
            Destination destination,
            MountProvider provider) {
        Entity entity = entity(source);
        if (player == null || provider == null || entity.isDead || entity.isBeingRidden()) {
            return Optional.empty();
        }
        Optional<NBTTagCompound> captured = captureSourceSnapshot(source);
        if (!captured.isPresent()) {
            return Optional.empty();
        }
        NBTTagCompound sourceSnapshot = captured.get();
        ResourceLocation entityType = EntityList.getKey(entity);
        WorldServer destinationWorld = resolveWorld(destination.getEvidence().getDimensionId());
        if (destinationWorld == null) {
            return Optional.empty();
        }
        UUID candidateId = UUID.randomUUID();
        NBTTagCompound candidateSnapshot = sourceSnapshot.copy();
        normalizeCandidateSnapshot(candidateSnapshot, candidateId, destination.getEvidence());
        Entity candidate;
        try {
            candidate = EntityList.createEntityFromNBT(candidateSnapshot, destinationWorld);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (candidate == null || !candidateId.equals(candidate.getUniqueID())) {
            return Optional.empty();
        }
        positionCandidate(candidate, player, destination.getEvidence());
        if (!providerSupports(new Source(
                candidate.getUniqueID(), candidate.dimension, candidate.isBeingRidden(), candidate), provider)
                || !prepareCandidate(candidate, provider)) {
            return Optional.empty();
        }
        NBTTagCompound preparedSnapshot = new NBTTagCompound();
        if (!candidate.writeToNBTAtomically(preparedSnapshot)
                || !entityType.toString().equals(preparedSnapshot.getString("id"))) {
            return Optional.empty();
        }
        // The durable snapshot remains tied to the source identity. Spawning assigns the
        // operation's separate candidate identity after the intent has been acknowledged.
        preparedSnapshot.setUniqueId("UUID", source.getPhysicalEntityId());
        return Optional.of(new TransferPlan(
                candidateId,
                new LastKnownEvidence(entity.dimension, entity.posX, entity.posY, entity.posZ),
                preparedSnapshot));
    }

    Optional<NBTTagCompound> captureSourceSnapshot(Source source) {
        Entity entity = entity(source);
        if (entity.isDead || entity.isBeingRidden()) {
            return Optional.empty();
        }
        NBTTagCompound snapshot = new NBTTagCompound();
        if (!entity.writeToNBTAtomically(snapshot)) {
            return Optional.empty();
        }
        ResourceLocation entityType = EntityList.getKey(entity);
        return entityType != null && entityType.toString().equals(snapshot.getString("id"))
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    @Override
    public CandidateAction spawnCandidate(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        if (world == null) {
            return CandidateAction.UNAVAILABLE;
        }
        Entity existing = world.getEntityFromUuid(operation.getCandidateEntityId());
        if (existing != null) {
            return isExactCandidate(existing, operation)
                    ? CandidateAction.SUCCESS
                    : CandidateAction.CONFLICT;
        }
        NBTTagCompound raw = operation.copySourceSnapshot();
        LastKnownEvidence target = operation.getDestinationEvidence();
        normalizeCandidateSnapshot(raw, operation.getCandidateEntityId(), target);
        Entity candidate;
        try {
            candidate = EntityList.createEntityFromNBT(raw, world);
        } catch (RuntimeException exception) {
            return CandidateAction.FAILED;
        }
        if (candidate == null || !operation.getCandidateEntityId().equals(candidate.getUniqueID())) {
            return CandidateAction.FAILED;
        }
        candidate.setLocationAndAngles(
                target.getX(), target.getY(), target.getZ(),
                candidate.rotationYaw, candidate.rotationPitch);
        candidate.motionX = 0.0D;
        candidate.motionY = 0.0D;
        candidate.motionZ = 0.0D;
        candidate.fallDistance = 0.0F;
        EntityMountEvidence.attachTransferCandidate(
                candidate, operation.getMountId(), operation.getOperationId());
        if (!world.spawnEntity(candidate)) {
            return CandidateAction.FAILED;
        }
        Entity indexed = world.getEntityFromUuid(operation.getCandidateEntityId());
        return indexed == candidate && isExactCandidate(indexed, operation)
                ? CandidateAction.SUCCESS
                : CandidateAction.CONFLICT;
    }

    @Override
    public TransferEvidence inspectTransfer(TransferOperation operation) {
        return inspectTransfer(operation, true, true);
    }

    @Override
    public TransferEvidence inspectTransfer(
            TransferOperation operation, boolean inspectSource, boolean inspectCandidate) {
        WorldServer sourceWorld = resolveWorld(operation.getSourceEvidence().getDimensionId());
        WorldServer destinationWorld = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        InspectedEntity source = inspectSource
                ? inspect(sourceWorld, operation.getSourceEvidence(),
                        operation.getSourceEntityId(), operation, false)
                : InspectedEntity.of(TransferEvidence.Presence.NOT_INSPECTED);
        InspectedEntity candidate = inspectCandidate
                ? inspect(destinationWorld, operation.getDestinationEvidence(),
                        operation.getCandidateEntityId(), operation, true)
                : InspectedEntity.of(TransferEvidence.Presence.NOT_INSPECTED);
        return new TransferEvidence(
                source.presence, candidate.presence, source.actualEvidence, candidate.actualEvidence);
    }

    @Override
    public PhysicalAction validateSourceRemoval(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getSourceEvidence().getDimensionId());
        if (world == null) {
            return PhysicalAction.UNAVAILABLE;
        }
        Entity source = world.getEntityFromUuid(operation.getSourceEntityId());
        if (source == null
                || !matchesTransferEntity(source, operation, operation.getSourceEntityId())
                || source.isBeingRidden()) {
            return PhysicalAction.CONFLICT;
        }
        if (source instanceof EntityLiving
                && ((EntityLiving) source).getLeashed()
                && ((EntityLiving) source).getLeashHolder() == null) {
            return PhysicalAction.UNAVAILABLE;
        }
        return PhysicalAction.SUCCESS;
    }

    @Override
    public CheckpointStatus checkpointCandidate(
            TransferOperation operation, boolean operationMarkerExpected) {
        return checkpointCandidateChunk(operation, operationMarkerExpected);
    }

    @Override
    public CheckpointStatus checkpointCandidateAbsent(TransferOperation operation) {
        return checkpointCandidateAbsentChunk(operation);
    }

    @Override
    public PhysicalAction removeSource(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getSourceEvidence().getDimensionId());
        if (world == null) {
            return PhysicalAction.UNAVAILABLE;
        }
        ChunkAccess access = loadExistingChunk(world, operation.getSourceEvidence());
        if (access.status != TransferEvidence.Presence.EXACT) {
            return access.status == TransferEvidence.Presence.MISSING
                    ? PhysicalAction.CONFLICT
                    : PhysicalAction.UNAVAILABLE;
        }
        try {
            final Entity source = world.getEntityFromUuid(operation.getSourceEntityId());
            if (source == null) {
                return PhysicalAction.SUCCESS;
            }
            if (!matchesTransferEntity(source, operation, operation.getSourceEntityId())
                    || source.isBeingRidden()) {
                return PhysicalAction.CONFLICT;
            }
            final EntityLiving living = source instanceof EntityLiving
                    ? (EntityLiving) source
                    : null;
            return removeSourceWithCompensation(new SourceRemovalAccess() {
                @Override
                public Object getVehicle() {
                    return source.getRidingEntity();
                }

                @Override
                public boolean isRiding() {
                    return source.isRiding();
                }

                @Override
                public boolean isRiding(Object vehicle) {
                    return source.getRidingEntity() == vehicle;
                }

                @Override
                public void dismount() {
                    source.dismountRidingEntity();
                }

                @Override
                public boolean startRiding(Object vehicle) {
                    return source.startRiding((Entity) vehicle, true);
                }

                @Override
                public boolean isLeashed() {
                    return living != null && living.getLeashed();
                }

                @Override
                public Object getLeashHolder() {
                    return living == null ? null : living.getLeashHolder();
                }

                @Override
                public void clearLeash() {
                    living.clearLeashed(true, false);
                }

                @Override
                public void restoreLeash(Object holder) {
                    living.setLeashHolder((Entity) holder, true);
                }

                @Override
                public boolean isLeashedTo(Object holder) {
                    return living != null
                            && living.getLeashed()
                            && living.getLeashHolder() == holder;
                }

                @Override
                public void remove() {
                    world.removeEntityDangerously(source);
                }

                @Override
                public boolean isRemoved() {
                    return world.getEntityFromUuid(operation.getSourceEntityId()) == null
                            || source.isDead;
                }

                @Override
                public void dropLead() {
                    source.entityDropItem(new ItemStack(Items.LEAD), 0.0F);
                }
            });
        } finally {
            access.release(world);
        }
    }

    static PhysicalAction removeSourceWithCompensation(SourceRemovalAccess source) {
        Object originalVehicle;
        boolean originallyLeashed;
        Object originalLeashHolder;
        try {
            originalVehicle = source.getVehicle();
            originallyLeashed = source.isLeashed();
            originalLeashHolder = originallyLeashed ? source.getLeashHolder() : null;
        } catch (RuntimeException exception) {
            return PhysicalAction.UNAVAILABLE;
        }
        if (originallyLeashed && originalLeashHolder == null) {
            return PhysicalAction.UNAVAILABLE;
        }

        try {
            if (originalVehicle != null) {
                source.dismount();
                if (source.isRiding()) {
                    return restoredFailure(
                            source, originalVehicle, originallyLeashed, originalLeashHolder);
                }
            }
            if (originallyLeashed) {
                source.clearLeash();
                if (source.isLeashed()) {
                    return restoredFailure(
                            source, originalVehicle, true, originalLeashHolder);
                }
            }
            source.remove();
        } catch (RuntimeException exception) {
            return removalExceptionOutcome(
                    source, originalVehicle, originallyLeashed, originalLeashHolder);
        }

        if (removedSafely(source)) {
            dropLeadAfterRemoval(source, originallyLeashed);
            return PhysicalAction.SUCCESS;
        }
        return restoredFailure(
                source, originalVehicle, originallyLeashed, originalLeashHolder);
    }

    private static PhysicalAction removalExceptionOutcome(
            SourceRemovalAccess source,
            Object originalVehicle,
            boolean originallyLeashed,
            Object originalLeashHolder) {
        if (removedSafely(source)) {
            dropLeadAfterRemoval(source, originallyLeashed);
            return PhysicalAction.SUCCESS;
        }
        return restoredFailure(
                source, originalVehicle, originallyLeashed, originalLeashHolder);
    }

    private static boolean removedSafely(SourceRemovalAccess source) {
        try {
            return source.isRemoved();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void dropLeadAfterRemoval(
            SourceRemovalAccess source, boolean originallyLeashed) {
        if (!originallyLeashed) {
            return;
        }
        try {
            source.dropLead();
        } catch (RuntimeException ignored) {
            // Source removal has committed; a failed item drop cannot make restoration safe.
        }
    }

    private static PhysicalAction restoredFailure(
            SourceRemovalAccess source,
            Object originalVehicle,
            boolean originallyLeashed,
            Object originalLeashHolder) {
        try {
            if (originalVehicle != null && !source.isRiding(originalVehicle)) {
                if (!source.startRiding(originalVehicle)) {
                    return PhysicalAction.FAILED;
                }
            }
            if (originallyLeashed && !source.isLeashedTo(originalLeashHolder)) {
                source.restoreLeash(originalLeashHolder);
            }
            boolean vehicleRestored = originalVehicle == null
                    ? !source.isRiding()
                    : source.isRiding(originalVehicle);
            boolean leashRestored = originallyLeashed
                    ? source.isLeashedTo(originalLeashHolder)
                    : !source.isLeashed();
            return vehicleRestored && leashRestored
                    ? PhysicalAction.FAILED_RESTORED
                    : PhysicalAction.FAILED;
        } catch (RuntimeException exception) {
            return PhysicalAction.FAILED;
        }
    }

    @Override
    public PhysicalAction removeCandidate(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        if (world == null) {
            return PhysicalAction.UNAVAILABLE;
        }
        ChunkAccess access = loadExistingChunk(world, operation.getDestinationEvidence());
        if (access.status != TransferEvidence.Presence.EXACT) {
            return access.status == TransferEvidence.Presence.MISSING
                    ? PhysicalAction.SUCCESS
                    : PhysicalAction.UNAVAILABLE;
        }
        final Entity candidate = world.getEntityFromUuid(operation.getCandidateEntityId());
        return removeCandidateWithRelease(
                candidate == null ? null : new CandidateRemovalAccess() {
                    @Override
                    public boolean isExact() {
                        return isExactCandidate(candidate, operation);
                    }

                    @Override
                    public void remove() {
                        world.removeEntityDangerously(candidate);
                    }

                    @Override
                    public boolean isRemoved() {
                        return world.getEntityFromUuid(operation.getCandidateEntityId()) == null
                                || candidate.isDead;
                    }
                },
                () -> access.release(world));
    }

    static PhysicalAction removeCandidateWithRelease(
            CandidateRemovalAccess candidate, Runnable release) {
        try {
            if (candidate == null) {
                return PhysicalAction.SUCCESS;
            }
            if (!candidate.isExact()) {
                return PhysicalAction.CONFLICT;
            }
            candidate.remove();
            return candidate.isRemoved() ? PhysicalAction.SUCCESS : PhysicalAction.FAILED;
        } finally {
            release.run();
        }
    }

    @Override
    public PhysicalAction clearCandidateOperationMarker(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        Entity candidate = world == null
                ? null
                : world.getEntityFromUuid(operation.getCandidateEntityId());
        if (!isExactCandidate(candidate, operation)) {
            return world == null ? PhysicalAction.UNAVAILABLE : PhysicalAction.CONFLICT;
        }
        EntityMountEvidence.clearTransferOperation(candidate);
        return isFinalCandidate(candidate, operation)
                ? PhysicalAction.SUCCESS
                : PhysicalAction.FAILED;
    }

    @Override
    public CheckpointStatus checkpointSourceAbsent(TransferOperation operation) {
        return checkpointSourceChunk(operation);
    }

    @Override
    public boolean pauseAfterPhase(TransferPhase phase) {
        return !phase.isActionIntent()
                && (pauseControl.test(phase)
                || net.minecraftforge.fml.relauncher.FMLLaunchHandler.isDeobfuscatedEnvironment()
                && phase.name().equalsIgnoreCase(
                        System.getProperty("mountcollection.dev.transferPauseAfter", "")));
    }

    @Override
    public void transferPhaseAcknowledged(TransferPhase phase) {
        acknowledgedPhaseControl.accept(phase);
    }

    private static boolean safe(
            WorldServer world, Entity entity, PlacementProfile profile, double x, double y, double z) {
        AxisAlignedBB candidate = entity.getEntityBoundingBox().offset(
                x - entity.posX, y - entity.posY, z - entity.posZ);
        BlockPos feet = new BlockPos(x, y, z);
        return safe(profile, new PlacementProbe() {
            @Override
            public boolean isWithinVerticalBounds() {
                return y >= 1.0D && y + entity.height < world.getHeight();
            }

            @Override
            public boolean isInsideWorldBorder() {
                return world.getWorldBorder().contains(candidate);
            }

            @Override
            public boolean areChunksLoaded() {
                return allLoaded(world, candidate);
            }

            @Override
            public boolean isBlockCollisionFree() {
                return world.getCollisionBoxes(entity, candidate).isEmpty();
            }

            @Override
            public boolean isEntityCollisionFree() {
                return world.checkNoEntityCollision(candidate, entity);
            }

            @Override
            public boolean isHazardFree() {
                return !containsHazard(world, candidate, profile);
            }

            @Override
            public boolean doesCompleteVolumeMatch(PlacementProfile requiredProfile) {
                Material required = requiredProfile == PlacementProfile.WATER
                        ? Material.WATER
                        : Material.LAVA;
                return PlacementVolume.allCellsMatch(candidate,
                        (cellX, cellY, cellZ) -> world.getBlockState(
                                new BlockPos(cellX, cellY, cellZ)).getMaterial() == required);
            }

            @Override
            public boolean hasSolidSupport() {
                IBlockState support = world.getBlockState(feet.down());
                return support.isTopSolid() && !isHazard(support);
            }

            @Override
            public boolean containsLiquid() {
                return world.containsAnyLiquid(candidate);
            }
        });
    }

    static boolean safe(PlacementProfile profile, PlacementProbe probe) {
        if (!probe.isWithinVerticalBounds()
                || !probe.isInsideWorldBorder()
                || !probe.areChunksLoaded()
                || !probe.isBlockCollisionFree()
                || !probe.isEntityCollisionFree()
                || !probe.isHazardFree()) {
            return false;
        }
        if (profile == PlacementProfile.WATER || profile == PlacementProfile.LAVA) {
            return probe.doesCompleteVolumeMatch(profile);
        }
        return probe.hasSolidSupport() && !probe.containsLiquid();
    }

    private static boolean allLoaded(WorldServer world, AxisAlignedBB box) {
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                if (!world.isBlockLoaded(new BlockPos(x, 64, z), false)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsHazard(
            WorldServer world, AxisAlignedBB box, PlacementProfile profile) {
        BlockPos min = new BlockPos(box.minX, box.minY - 1.0D, box.minZ);
        BlockPos max = new BlockPos(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.getAllInBoxMutable(min, max)) {
            if (isHazard(world.getBlockState(pos), profile)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHazard(IBlockState state) {
        return isHazard(state, PlacementProfile.SOLID_GROUND);
    }

    private static boolean isHazard(IBlockState state, PlacementProfile profile) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        return (material == Material.LAVA && profile != PlacementProfile.LAVA)
                || material == Material.FIRE
                || block == Blocks.FIRE
                || block == Blocks.CACTUS
                || block == Blocks.MAGMA;
    }

    static boolean prepareCandidate(Entity entity, MountProvider provider) {
        if (!(provider instanceof PreparationSupport)) {
            return true;
        }
        try {
            ProviderResult<Void> result = ((PreparationSupport) provider).prepareForPlacement(entity);
            return result != null && result.isSuccess();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void normalizeCandidateSnapshot(
            NBTTagCompound raw, UUID candidateId, LastKnownEvidence target) {
        raw.setUniqueId("UUID", candidateId);
        raw.removeTag("Dimension");
        raw.removeTag("Passengers");
        raw.removeTag("Riding");
        raw.removeTag("Leash");
        raw.setBoolean("Leashed", false);
        raw.setTag("Pos", doubles(target.getX(), target.getY(), target.getZ()));
        raw.setTag("Motion", doubles(0.0D, 0.0D, 0.0D));
    }

    private static void positionCandidate(
            Entity candidate, EntityPlayerMP player, LastKnownEvidence target) {
        float yaw = (float) (Math.toDegrees(Math.atan2(
                player.posZ - target.getZ(), player.posX - target.getX())) - 90.0D);
        candidate.setLocationAndAngles(
                target.getX(), target.getY(), target.getZ(), yaw, candidate.rotationPitch);
        candidate.motionX = 0.0D;
        candidate.motionY = 0.0D;
        candidate.motionZ = 0.0D;
        candidate.fallDistance = 0.0F;
    }

    private static InspectedEntity inspect(
            WorldServer world,
            LastKnownEvidence evidence,
            UUID entityId,
            TransferOperation operation,
            boolean candidate) {
        if (world == null) {
            return InspectedEntity.of(TransferEvidence.Presence.UNAVAILABLE);
        }
        Entity entity = world.getEntityFromUuid(entityId);
        if (entity != null && !entity.isDead) {
            return inspectLoadedEntity(entity, operation, candidate);
        }
        ChunkAccess access = loadExistingChunk(world, evidence);
        if (access.status != TransferEvidence.Presence.EXACT) {
            return InspectedEntity.of(access.status);
        }
        entity = world.getEntityFromUuid(entityId);
        if (entity == null || entity.isDead) {
            access.release(world);
            return InspectedEntity.of(TransferEvidence.Presence.MISSING);
        }
        InspectedEntity result = inspectLoadedEntity(entity, operation, candidate);
        access.release(world);
        return result;
    }

    private static InspectedEntity inspectLoadedEntity(
            Entity entity, TransferOperation operation, boolean candidate) {
        TransferEvidence.Presence presence;
        if (candidate && isExactCandidate(entity, operation)) {
            presence = TransferEvidence.Presence.EXACT;
        } else if (candidate && isFinalCandidate(entity, operation)) {
            presence = TransferEvidence.Presence.FINALIZED;
        } else if (!candidate
                && matchesTransferEntity(entity, operation, operation.getSourceEntityId())) {
            presence = TransferEvidence.Presence.EXACT;
        } else {
            presence = TransferEvidence.Presence.CONFLICT;
        }
        LastKnownEvidence actual = presence == TransferEvidence.Presence.EXACT
                        || presence == TransferEvidence.Presence.FINALIZED
                ? new LastKnownEvidence(entity.dimension, entity.posX, entity.posY, entity.posZ)
                : null;
        return new InspectedEntity(presence, actual);
    }

    private static boolean isExactCandidate(Entity entity, TransferOperation operation) {
        return matchesTransferEntity(entity, operation, operation.getCandidateEntityId())
                && EntityMountEvidence.readTransfer(entity).getOperationId()
                        .map(operation.getOperationId()::equals)
                        .orElse(false);
    }

    private static boolean isFinalCandidate(Entity entity, TransferOperation operation) {
        return matchesTransferEntity(entity, operation, operation.getCandidateEntityId())
                && EntityMountEvidence.readTransfer(entity).getStatus()
                        == EntityMountEvidence.Status.NONE;
    }

    private static boolean matchesMount(Entity entity, MountId mountId, UUID entityId) {
        if (entity == null || entity.isDead || !entityId.equals(entity.getUniqueID())) {
            return false;
        }
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
        return evidence.getStatus() == EntityMountEvidence.Status.VALID
                && evidence.getMountId().map(mountId::equals).orElse(false);
    }

    private static boolean matchesTransferEntity(
            Entity entity, TransferOperation operation, UUID entityId) {
        if (!matchesMount(entity, operation.getMountId(), entityId)) {
            return false;
        }
        String expectedType = operation.copySourceSnapshot().getString("id");
        ResourceLocation actualType = EntityList.getKey(entity);
        return actualType != null && actualType.toString().equals(expectedType);
    }

    private static WorldServer resolveWorld(int dimensionId) {
        WorldServer world = DimensionManager.getWorld(dimensionId, true);
        if (world != null || !DimensionManager.isDimensionRegistered(dimensionId)) {
            return world;
        }
        try {
            DimensionManager.initDimension(dimensionId);
            return DimensionManager.getWorld(dimensionId, true);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static ChunkAccess loadExistingChunk(
            WorldServer world, LastKnownEvidence evidence) {
        int chunkX = ((int) Math.floor(evidence.getX())) >> 4;
        int chunkZ = ((int) Math.floor(evidence.getZ())) >> 4;
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk loaded = provider.getLoadedChunk(chunkX, chunkZ);
        if (loaded != null) {
            return ChunkAccess.available(loaded, false);
        }
        try {
            if (!provider.chunkLoader.isChunkGeneratedAt(chunkX, chunkZ)) {
                return ChunkAccess.missing();
            }
            Chunk fromDisk = provider.loadChunk(chunkX, chunkZ);
            return fromDisk == null
                    ? ChunkAccess.unavailable()
                    : ChunkAccess.available(fromDisk, true);
        } catch (RuntimeException exception) {
            return ChunkAccess.unavailable();
        }
    }

    private CheckpointStatus checkpointCandidateChunk(
            TransferOperation operation, boolean markerExpected) {
        WorldServer world = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        if (world == null) {
            return CheckpointStatus.UNAVAILABLE;
        }
        ChunkAccess access = loadExistingChunk(world, operation.getDestinationEvidence());
        if (access.status == TransferEvidence.Presence.MISSING) {
            return CheckpointStatus.FAILED;
        }
        if (access.status != TransferEvidence.Presence.EXACT) {
            return CheckpointStatus.UNAVAILABLE;
        }
        try {
            PhysicalFenceAttempt attempt = attemptSaveDrainAndRead(world, access.chunk);
            CheckpointStatus result = CheckpointStatus.FAILED;
            if (attempt.diskChunk != null) {
                DiskEntityStatus status = inspectSavedEntity(
                        attempt.diskChunk, operation.getCandidateEntityId(), operation, true);
                if (status == DiskEntityStatus.CONFLICT) {
                    result = CheckpointStatus.INTEGRITY_CONFLICT;
                } else if ((markerExpected && status == DiskEntityStatus.EXACT)
                        || (!markerExpected && status == DiskEntityStatus.FINALIZED)) {
                    result = CheckpointStatus.VERIFIED;
                }
            }
            recordFenceDiagnostic(
                    FenceDiagnosticContext.from(
                            operation,
                            markerExpected ? "candidate_present" : "candidate_finalized",
                            world.provider.getDimension(),
                            access.chunk.x,
                            access.chunk.z),
                    attempt,
                    result);
            return result;
        } finally {
            access.release(world);
        }
    }

    private CheckpointStatus checkpointSourceChunk(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getSourceEvidence().getDimensionId());
        if (world == null) {
            return CheckpointStatus.UNAVAILABLE;
        }
        ChunkAccess access = loadExistingChunk(world, operation.getSourceEvidence());
        if (access.status == TransferEvidence.Presence.MISSING) {
            return CheckpointStatus.INTEGRITY_CONFLICT;
        }
        if (access.status != TransferEvidence.Presence.EXACT) {
            return CheckpointStatus.UNAVAILABLE;
        }
        try {
            PhysicalFenceAttempt attempt = attemptSaveDrainAndRead(world, access.chunk);
            CheckpointStatus result = CheckpointStatus.FAILED;
            if (attempt.diskChunk != null) {
                DiskEntityStatus status = inspectSavedEntity(
                        attempt.diskChunk, operation.getSourceEntityId(), operation, false);
                if (status == DiskEntityStatus.MISSING) {
                    result = CheckpointStatus.VERIFIED;
                } else if (status == DiskEntityStatus.CONFLICT) {
                    result = CheckpointStatus.INTEGRITY_CONFLICT;
                }
            }
            recordFenceDiagnostic(
                    FenceDiagnosticContext.from(
                            operation,
                            "source_absent",
                            world.provider.getDimension(),
                            access.chunk.x,
                            access.chunk.z),
                    attempt,
                    result);
            return result;
        } finally {
            access.release(world);
        }
    }

    private CheckpointStatus checkpointCandidateAbsentChunk(TransferOperation operation) {
        WorldServer world = resolveWorld(operation.getDestinationEvidence().getDimensionId());
        if (world == null) {
            return CheckpointStatus.UNAVAILABLE;
        }
        ChunkAccess access = loadExistingChunk(world, operation.getDestinationEvidence());
        if (access.status == TransferEvidence.Presence.MISSING) {
            return operation.getPhase() == TransferPhase.PREPARED
                    ? CheckpointStatus.VERIFIED
                    : CheckpointStatus.INTEGRITY_CONFLICT;
        }
        if (access.status != TransferEvidence.Presence.EXACT) {
            return CheckpointStatus.UNAVAILABLE;
        }
        try {
            PhysicalFenceAttempt attempt = attemptSaveDrainAndRead(world, access.chunk);
            CheckpointStatus result = CheckpointStatus.FAILED;
            if (attempt.diskChunk != null) {
                DiskEntityStatus status = inspectSavedEntity(
                        attempt.diskChunk, operation.getCandidateEntityId(), operation, true);
                if (status == DiskEntityStatus.MISSING) {
                    result = CheckpointStatus.VERIFIED;
                } else if (status == DiskEntityStatus.CONFLICT) {
                    result = CheckpointStatus.INTEGRITY_CONFLICT;
                }
            }
            recordFenceDiagnostic(
                    FenceDiagnosticContext.from(
                            operation,
                            "candidate_absent",
                            world.provider.getDimension(),
                            access.chunk.x,
                            access.chunk.z),
                    attempt,
                    result);
            return result;
        } finally {
            access.release(world);
        }
    }

    NBTTagCompound saveDrainAndRead(WorldServer world, Chunk chunk) {
        return attemptSaveDrainAndRead(world, chunk).diskChunk;
    }

    PhysicalFenceAttempt attemptSaveDrainAndRead(WorldServer world, Chunk chunk) {
        Long preDrainNanos = null;
        Long postDrainNanos = null;
        long started = nanoTime.getAsLong();
        try {
            if (world != null && !world.isCallingFromMinecraftThread()) {
                return PhysicalFenceAttempt.failed(
                        null, null, "wrong_thread");
            }
            chunkPersistence.drain();
            preDrainNanos = elapsedNanos(started, nanoTime.getAsLong());
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return PhysicalFenceAttempt.failed(
                    elapsedNanos(started, nanoTime.getAsLong()), null, "pre_drain");
        }
        try {
            chunkPersistence.save(world, chunk);
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return PhysicalFenceAttempt.failed(preDrainNanos, null, "enqueue");
        }
        if (physicalFencePostDrainFault.getAsBoolean()) {
            return PhysicalFenceAttempt.failed(
                    preDrainNanos, null, "injected_post_drain");
        }
        started = nanoTime.getAsLong();
        try {
            chunkPersistence.drain();
            postDrainNanos = elapsedNanos(started, nanoTime.getAsLong());
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return PhysicalFenceAttempt.failed(
                    preDrainNanos,
                    elapsedNanos(started, nanoTime.getAsLong()),
                    "post_drain");
        }
        try {
            NBTTagCompound diskChunk = chunkPersistence.read(world, chunk);
            return diskChunk == null
                    ? PhysicalFenceAttempt.failed(preDrainNanos, postDrainNanos, "disk_read")
                    : PhysicalFenceAttempt.succeeded(
                            diskChunk, preDrainNanos, postDrainNanos);
        } catch (Exception exception) {
            restoreInterrupt(exception);
            return PhysicalFenceAttempt.failed(preDrainNanos, postDrainNanos, "disk_read");
        }
    }

    void recordFenceDiagnostic(
            FenceDiagnosticContext context,
            PhysicalFenceAttempt attempt,
            CheckpointStatus result) {
        if (diagnostics == null) {
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("correlation", context.correlation);
        fields.put("mount", context.mount);
        fields.put("phase", context.phase);
        fields.put("fence", context.fence);
        fields.put("dimension", Integer.toString(context.dimension));
        fields.put("chunk_x", Integer.toString(context.chunkX));
        fields.put("chunk_z", Integer.toString(context.chunkZ));
        fields.put("pre_drain_nanos", durationValue(attempt.preDrainNanos));
        fields.put("post_drain_nanos", durationValue(attempt.postDrainNanos));
        fields.put("persistence_stage", attempt.failureStage);
        fields.put("result", result.name());
        diagnostics.detail(DiagnosticCategory.LIFECYCLE, "transfer_physical_fence", fields);
    }

    private static String durationValue(Long duration) {
        return duration == null ? "not_completed" : Long.toString(duration);
    }

    private static long elapsedNanos(long started, long finished) {
        return Math.max(0L, finished - started);
    }

    private static void restoreInterrupt(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    static final class PhysicalFenceAttempt {
        private final NBTTagCompound diskChunk;
        private final Long preDrainNanos;
        private final Long postDrainNanos;
        private final String failureStage;

        private PhysicalFenceAttempt(
                NBTTagCompound diskChunk,
                Long preDrainNanos,
                Long postDrainNanos,
                String failureStage) {
            this.diskChunk = diskChunk;
            this.preDrainNanos = preDrainNanos;
            this.postDrainNanos = postDrainNanos;
            this.failureStage = failureStage;
        }

        private static PhysicalFenceAttempt succeeded(
                NBTTagCompound diskChunk, Long preDrainNanos, Long postDrainNanos) {
            return new PhysicalFenceAttempt(
                    diskChunk, preDrainNanos, postDrainNanos, "complete");
        }

        private static PhysicalFenceAttempt failed(
                Long preDrainNanos, Long postDrainNanos, String failureStage) {
            return new PhysicalFenceAttempt(
                    null, preDrainNanos, postDrainNanos, failureStage);
        }
    }

    static final class FenceDiagnosticContext {
        private final String correlation;
        private final String mount;
        private final String phase;
        private final String fence;
        private final int dimension;
        private final int chunkX;
        private final int chunkZ;

        FenceDiagnosticContext(
                String correlation,
                String mount,
                String phase,
                String fence,
                int dimension,
                int chunkX,
                int chunkZ) {
            this.correlation = correlation;
            this.mount = mount;
            this.phase = phase;
            this.fence = fence;
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private static FenceDiagnosticContext from(
                TransferOperation operation,
                String fence,
                int dimension,
                int chunkX,
                int chunkZ) {
            return new FenceDiagnosticContext(
                    operation.getOperationId().toString(),
                    operation.getMountId().toString(),
                    operation.getPhase().name(),
                    fence,
                    dimension,
                    chunkX,
                    chunkZ);
        }
    }

    private static final class AnvilChunkPersistence implements ChunkPersistence {
        @Override
        public void drain() throws Exception {
            ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        }

        @Override
        public void save(WorldServer world, Chunk chunk) throws Exception {
            world.getChunkProvider().chunkLoader.saveChunk(world, chunk);
        }

        @Override
        public NBTTagCompound read(WorldServer world, Chunk chunk) throws Exception {
            ChunkProviderServer provider = world.getChunkProvider();
            if (!(provider.chunkLoader instanceof AnvilChunkLoader)) {
                throw new IOException("targeted chunk loader is not Anvil-backed");
            }
            Object[] loaded = ((AnvilChunkLoader) provider.chunkLoader)
                    .loadChunk__Async(world, chunk.x, chunk.z);
            return loaded == null ? null : (NBTTagCompound) loaded[1];
        }
    }

    private static final class InspectedEntity {
        final TransferEvidence.Presence presence;
        final LastKnownEvidence actualEvidence;

        private InspectedEntity(
                TransferEvidence.Presence presence, LastKnownEvidence actualEvidence) {
            this.presence = presence;
            this.actualEvidence = actualEvidence;
        }

        static InspectedEntity of(TransferEvidence.Presence presence) {
            return new InspectedEntity(presence, null);
        }
    }

    private static DiskEntityStatus inspectSavedEntity(
            NBTTagCompound chunkRoot,
            UUID expectedId,
            TransferOperation operation,
            boolean candidate) {
        if (!chunkRoot.hasKey("Level", 10)) {
            return DiskEntityStatus.CONFLICT;
        }
        NBTTagCompound level = chunkRoot.getCompoundTag("Level");
        if (!level.hasKey("Entities", 9)) {
            return DiskEntityStatus.CONFLICT;
        }
        NBTTagList entities = level.getTagList("Entities", 10);
        NBTTagCompound matched = null;
        for (int index = 0; index < entities.tagCount(); index++) {
            NBTTagCompound raw = entities.getCompoundTagAt(index);
            if (raw.hasUniqueId("UUID") && expectedId.equals(raw.getUniqueId("UUID"))) {
                if (matched != null) {
                    return DiskEntityStatus.CONFLICT;
                }
                matched = raw;
            }
        }
        if (matched == null) {
            return DiskEntityStatus.MISSING;
        }
        if (!matched.hasKey("id", 8)
                || !operation.copySourceSnapshot().getString("id").equals(matched.getString("id"))) {
            return DiskEntityStatus.CONFLICT;
        }
        EntityMountEvidence.ReadResult mountEvidence = EntityMountEvidence.readSavedEntity(matched);
        if (mountEvidence.getStatus() != EntityMountEvidence.Status.VALID
                || !mountEvidence.getMountId().map(operation.getMountId()::equals).orElse(false)) {
            return DiskEntityStatus.CONFLICT;
        }
        if (!candidate) {
            return DiskEntityStatus.EXACT;
        }
        EntityMountEvidence.TransferReadResult transferEvidence =
                EntityMountEvidence.readSavedTransfer(matched);
        if (transferEvidence.getStatus() == EntityMountEvidence.Status.MALFORMED) {
            return DiskEntityStatus.CONFLICT;
        }
        if (transferEvidence.getStatus() == EntityMountEvidence.Status.NONE) {
            return DiskEntityStatus.FINALIZED;
        }
        return transferEvidence.getOperationId()
                        .map(operation.getOperationId()::equals)
                        .orElse(false)
                ? DiskEntityStatus.EXACT
                : DiskEntityStatus.CONFLICT;
    }

    private enum DiskEntityStatus {
        EXACT,
        FINALIZED,
        MISSING,
        CONFLICT
    }

    private static final class ChunkAccess {
        private final TransferEvidence.Presence status;
        private final Chunk chunk;
        private final boolean release;

        private ChunkAccess(TransferEvidence.Presence status, Chunk chunk, boolean release) {
            this.status = status;
            this.chunk = chunk;
            this.release = release;
        }

        private static ChunkAccess available(Chunk chunk, boolean release) {
            return new ChunkAccess(TransferEvidence.Presence.EXACT, chunk, release);
        }

        private static ChunkAccess missing() {
            return new ChunkAccess(TransferEvidence.Presence.MISSING, null, false);
        }

        private static ChunkAccess unavailable() {
            return new ChunkAccess(TransferEvidence.Presence.UNAVAILABLE, null, false);
        }

        private void release(WorldServer world) {
            if (release && chunk != null) {
                world.getChunkProvider().queueUnload(chunk);
            }
        }
    }

    private static NBTTagList doubles(double first, double second, double third) {
        NBTTagList list = new NBTTagList();
        list.appendTag(new NBTTagDouble(first));
        list.appendTag(new NBTTagDouble(second));
        list.appendTag(new NBTTagDouble(third));
        return list;
    }

    private static boolean isExactLivingEntity(Entity entity, MountRecord record) {
        if (entity == null || entity.isDead
                || !record.getPhysicalEntityId().equals(entity.getUniqueID())
                || !record.getEntityTypeId().equals(EntityList.getKey(entity))) {
            return false;
        }
        EntityMountEvidence.ReadResult evidence = EntityMountEvidence.read(entity);
        return evidence.getStatus() == EntityMountEvidence.Status.VALID
                && evidence.getMountId().isPresent()
                && record.getMountId().equals(evidence.getMountId().get());
    }

    private static Entity entity(Source source) {
        return (Entity) source.getImplementationHandle();
    }
}
