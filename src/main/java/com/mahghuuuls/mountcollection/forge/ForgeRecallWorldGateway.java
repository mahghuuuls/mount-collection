package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.api.PlacementSupport;
import com.mahghuuuls.mountcollection.api.PreparationSupport;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.lifecycle.RecallWorldGateway;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.policy.PlacementSearch;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/** Minecraft 1.12.2-specific bounded lookup and same-world placement adapter. */
public final class ForgeRecallWorldGateway implements RecallWorldGateway {

    private final PlacementSearch placementSearch = new PlacementSearch();

    @Override
    public LocateResult locate(EntityPlayerMP player, MountRecord record) {
        if (record.getPhysicalEntityId() == null || record.getLastKnown() == null
                || record.getLastKnown().getDimensionId() != player.dimension) {
            return LocateResult.missing();
        }
        WorldServer world = player.getServerWorld();
        Entity entity = world.getEntityFromUuid(record.getPhysicalEntityId());
        Chunk loadedForLookup = null;
        if (entity == null) {
            LastKnownEvidence evidence = record.getLastKnown();
            int chunkX = ((int) Math.floor(evidence.getX())) >> 4;
            int chunkZ = ((int) Math.floor(evidence.getZ())) >> 4;
            ChunkProviderServer chunks = world.getChunkProvider();
            Chunk alreadyLoaded = chunks.getLoadedChunk(chunkX, chunkZ);
            Chunk bounded = alreadyLoaded == null ? chunks.loadChunk(chunkX, chunkZ) : alreadyLoaded;
            if (bounded == null) {
                return LocateResult.missing();
            }
            if (alreadyLoaded == null) {
                loadedForLookup = bounded;
            }
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
            MountProvider provider,
            int normalRadius,
            int fallbackRadius) {
        Entity entity = entity(source);
        PlacementProfile profile = placementProfile(provider, entity);
        if (profile == null || profile == PlacementProfile.PROVIDER_SPECIFIC) {
            return Optional.empty();
        }
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

    private static boolean safe(
            WorldServer world, Entity entity, PlacementProfile profile, double x, double y, double z) {
        if (y < 1.0D || y + entity.height >= world.getHeight()) {
            return false;
        }
        AxisAlignedBB candidate = entity.getEntityBoundingBox().offset(
                x - entity.posX, y - entity.posY, z - entity.posZ);
        if (!world.getWorldBorder().contains(candidate)
                || !allLoaded(world, candidate)
                || !world.getCollisionBoxes(entity, candidate).isEmpty()
                || !world.checkNoEntityCollision(candidate, entity)
                || containsHazard(world, candidate)) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        if (profile == PlacementProfile.WATER) {
            return world.isMaterialInBB(candidate, Material.WATER)
                    && !world.isMaterialInBB(candidate, Material.LAVA);
        }
        IBlockState support = world.getBlockState(feet.down());
        return support.isTopSolid()
                && !isHazard(support)
                && !world.containsAnyLiquid(candidate);
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

    private static boolean containsHazard(WorldServer world, AxisAlignedBB box) {
        BlockPos min = new BlockPos(box.minX, box.minY - 1.0D, box.minZ);
        BlockPos max = new BlockPos(box.maxX, box.maxY, box.maxZ);
        for (BlockPos pos : BlockPos.getAllInBoxMutable(min, max)) {
            if (isHazard(world.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHazard(IBlockState state) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        return material == Material.LAVA
                || material == Material.FIRE
                || block == Blocks.FIRE
                || block == Blocks.CACTUS
                || block == Blocks.MAGMA;
    }

    private static PlacementProfile placementProfile(MountProvider provider, Entity entity) {
        if (!(provider instanceof PlacementSupport)) {
            return PlacementProfile.SOLID_GROUND;
        }
        try {
            ProviderResult<PlacementProfile> result =
                    ((PlacementSupport) provider).getPlacementProfile(entity);
            return result != null && result.isSuccess() && result.getValue().isPresent()
                    ? result.getValue().get()
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
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
