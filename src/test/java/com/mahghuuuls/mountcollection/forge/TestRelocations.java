package com.mahghuuuls.mountcollection.forge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

/** Explicit no-transport fixture for tests that exercise only server placement/boarding. */
final class TestRelocations implements RelocationOutput {
    public boolean ready(Entity entity,double x,double y,double z) { return true; }
    public void relocated(Entity entity) { }
    public boolean visibleTo(Entity entity,EntityPlayerMP player) { return true; }
}
