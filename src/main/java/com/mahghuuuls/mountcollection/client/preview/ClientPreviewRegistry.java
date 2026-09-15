package com.mahghuuuls.mountcollection.client.preview;

import com.mahghuuuls.mountcollection.api.MountPreview;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/** Frozen client factories; unavailable or incompatible factories produce an absent preview. */
public final class ClientPreviewRegistry {
    private final Map<ResourceLocation, ClientPreviewProvider> providers = new HashMap<>();
    private boolean frozen;
    public void register(ResourceLocation id, ClientPreviewProvider provider) {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(provider, "provider");
        if (frozen) { throw new IllegalStateException("preview registry frozen"); }
        if (providers.putIfAbsent(id, provider) != null) { throw new IllegalArgumentException("duplicate preview provider"); }
    }
    public void freeze() { frozen = true; }
    EntityLivingBase create(MountPreview data, World world) {
        if (!frozen) { throw new IllegalStateException("preview registry not frozen"); }
        if (data == null || world == null || !world.isRemote) { return null; }
        ClientPreviewProvider provider = providers.get(data.getProviderId());
        if (provider == null) { return null; }
        try {
            EntityLivingBase entity = provider.create(data, world);
            if (entity == null || entity.world != world || entity.isDead || world.loadedEntityList.contains(entity)
                    || entity instanceof net.minecraft.entity.player.EntityPlayer
                    || !Float.isFinite(entity.width) || !Float.isFinite(entity.height)
                    || entity.width <= 0 || entity.height <= 0 || entity.width > 64 || entity.height > 64) { return null; }
            return entity;
        } catch (RuntimeException | LinkageError failure) { return null; }
    }
}
