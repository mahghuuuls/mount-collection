package com.mahghuuuls.mountcollection.forge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

/** Server-native relocation boundary; implementations hide transport and recipient lifetime. */
public interface RelocationOutput {
    boolean ready(Entity entity, double x, double y, double z);
    void relocated(Entity entity);
    boolean visibleTo(Entity entity, EntityPlayerMP player);
}
