package com.mahghuuuls.mountcollection.provider.vanilla;

import com.mahghuuuls.mountcollection.Tags;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderPayload;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RecoverySupport;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityDonkey;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityLlama;
import net.minecraft.entity.passive.EntityMule;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySkeletonHorse;
import net.minecraft.entity.passive.EntityZombieHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

public final class VanillaMountProvider implements MountProvider, RecoverySupport {

    public static final ResourceLocation ID = new ResourceLocation(Tags.MOD_ID, "vanilla");

    @Override
    public ResourceLocation getProviderId() {
        return ID;
    }

    @Override
    public boolean supports(Entity entity) {
        return entity != null && kindOfClass(entity.getClass()) != VanillaEligibility.Kind.UNSUPPORTED;
    }

    @Override
    public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID playerId) {
        if (entity == null || playerId == null) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }
        VanillaEligibility.Kind kind = kindOfClass(entity.getClass());
        boolean riddenByPlayer = entity.getControllingPassenger() != null
                && playerId.equals(entity.getControllingPassenger().getUniqueID());
        boolean tame = entity instanceof AbstractHorse && ((AbstractHorse) entity).isTame();
        UUID ownerId = entity instanceof AbstractHorse
                ? ((AbstractHorse) entity).getOwnerUniqueId()
                : null;
        ProviderFailure failure = VanillaEligibility.validate(
                kind, tame, ownerId, playerId, riddenByPlayer);
        if (failure != null) {
            return ProviderResult.failure(failure);
        }
        ResourceLocation entityTypeId = EntityList.getKey(entity);
        if (entityTypeId == null) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }
        return ProviderResult.success(new RegistrationProfile(
                entityTypeId, entityTypeId.toString(), MountCharacteristics.solidGround()));
    }

    @Override
    public ProviderResult<ProviderPayload> capturePersistentState(Entity mount) {
        if (!supports(mount)) {
            return ProviderResult.failure(ProviderFailure.UNSUPPORTED);
        }
        try {
            NBTTagCompound entityData = new NBTTagCompound();
            mount.writeToNBT(entityData);
            NBTTagCompound payload = new NBTTagCompound();
            payload.setTag("Entity", entityData);
            return ProviderResult.success(new ProviderPayload(1, payload));
        } catch (RuntimeException exception) {
            return ProviderResult.failure(ProviderFailure.INTERNAL_ERROR);
        }
    }

    @Override
    public ProviderResult<Void> applyPersistentState(Entity mount, ProviderPayload payload) {
        if (!supports(mount) || payload == null || payload.getVersion() != 1) {
            return ProviderResult.failure(ProviderFailure.INVALID_STATE);
        }
        try {
            NBTTagCompound data = payload.copyData();
            if (!data.hasKey("Entity", 10)) {
                return ProviderResult.failure(ProviderFailure.INVALID_STATE);
            }
            mount.readFromNBT(data.getCompoundTag("Entity"));
            return ProviderResult.success();
        } catch (RuntimeException exception) {
            return ProviderResult.failure(ProviderFailure.INTERNAL_ERROR);
        }
    }

    static VanillaEligibility.Kind kindOfClass(Class<?> type) {
        if (type == EntityHorse.class
                || type == EntityDonkey.class
                || type == EntityMule.class
                || type == EntityLlama.class) {
            return VanillaEligibility.Kind.OWNED_HORSE;
        }
        if (type == EntitySkeletonHorse.class || type == EntityZombieHorse.class) {
            return VanillaEligibility.Kind.UNDEAD_HORSE;
        }
        if (type == EntityPig.class) {
            return VanillaEligibility.Kind.RIDDEN_PIG;
        }
        return VanillaEligibility.Kind.UNSUPPORTED;
    }
}
