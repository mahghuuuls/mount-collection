package com.mahghuuuls.mountcollection.client.preview;

import com.mahghuuuls.mountcollection.api.MountPreview;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

/** Client-only extension. Return a fresh detached entity; never spawn, tick or return a gameplay entity. */
@FunctionalInterface
public interface ClientPreviewProvider {
    EntityLivingBase create(MountPreview presentation, World clientWorld);
}
