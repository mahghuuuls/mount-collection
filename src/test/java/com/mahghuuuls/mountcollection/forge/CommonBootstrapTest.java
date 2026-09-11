package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class CommonBootstrapTest {

    @Test
    void relationshipExceptionStillContainsTheCapturedSourceAndRaisesFatalSignal() {
        EntityBoat mount = new EntityBoat(null) {
            @Override public void removePassengers() { throw new IllegalStateException("injected"); }
        };
        org.junit.jupiter.api.Assertions.assertThrows(
                com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException.class,
                () -> CommonBootstrap.completeProtectedDeath(mount));
        assertTrue(mount.isDead);
        assertTrue(mount.captureDrops);
    }

    @BeforeAll
    static void initializeMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void protectedDeathCapturesDropsEmittedAfterTheEventAdapterReturns() {
        EntityBoat mount = new EntityBoat(null);
        mount.capturedDrops.add(new EntityItem(null));

        CommonBootstrap.completeProtectedDeath(mount);
        EntityItem attemptedDrop = mount.entityDropItem(
                new ItemStack(Items.SADDLE), 0.0F);

        assertTrue(mount.isDead);
        assertTrue(mount.captureDrops);
        assertEquals(1, mount.capturedDrops.size());
        assertSame(attemptedDrop, mount.capturedDrops.get(0));
        assertEquals(Items.SADDLE,
                mount.capturedDrops.get(0).getItem().getItem());
    }
}
