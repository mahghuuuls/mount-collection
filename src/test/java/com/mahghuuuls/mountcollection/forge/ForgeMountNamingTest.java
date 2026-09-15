package com.mahghuuuls.mountcollection.forge;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.persistence.*;
import java.util.UUID;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ForgeMountNamingTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    @Test void unloadedRenameWaitsForExactLoadedIdentityAndDoesNotReadChunks() {
        Fixture f = new Fixture();
        f.repository.rename(f.owner, f.record.getMountId(), 1, "Renamed");
        f.loaded = false;
        f.naming.tick();
        assertEquals(1, f.lookups);
        assertTrue(f.current().getNaming().isPending());
        assertEquals("", f.pig.getCustomNameTag());
        f.loaded = true;
        EntityMountEvidence.attach(f.pig, MountId.create());
        f.naming.tick();
        assertEquals("", f.pig.getCustomNameTag());
        EntityMountEvidence.attach(f.pig, f.record.getMountId());
        f.naming.tick();
        assertEquals("Renamed", f.pig.getCustomNameTag());
        assertFalse(f.current().getNaming().isPending());
        assertEquals(0, f.world.chunkQueries);
        f.repository.rename(f.owner, f.record.getMountId(), f.repository.inspectCollection(f.owner).getRevision(), "");
        f.naming.tick();
        assertEquals("", f.pig.getCustomNameTag());
    }
    @Test void tagObservationWaitsForActualNativeMutationAndRejectsRacingRename() {
        Fixture f = new Fixture();
        f.naming.reconcile(f.pig);
        ItemStack tag = new ItemStack(Items.NAME_TAG); tag.setStackDisplayName("Tagged");
        f.player.setHeldItem(EnumHand.MAIN_HAND, tag);
        f.naming.onNameTag(new PlayerInteractEvent.EntityInteract(f.player, EnumHand.MAIN_HAND, f.pig));
        f.services.getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals("", f.current().getNaming().getCustomName(), "an event without native mutation is not success");
        f.naming.onNameTag(new PlayerInteractEvent.EntityInteract(f.player, EnumHand.MAIN_HAND, f.pig));
        assertEquals("", f.current().getNaming().getCustomName());
        Items.NAME_TAG.itemInteractionForEntity(tag, f.player, f.pig, EnumHand.MAIN_HAND);
        f.services.getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals("Tagged", f.current().getNaming().getCustomName());
        ItemStack race = new ItemStack(Items.NAME_TAG); race.setStackDisplayName("Native race");
        f.player.setHeldItem(EnumHand.MAIN_HAND, race);
        f.naming.onNameTag(new PlayerInteractEvent.EntityInteract(f.player, EnumHand.MAIN_HAND, f.pig));
        Items.NAME_TAG.itemInteractionForEntity(race, f.player, f.pig, EnumHand.MAIN_HAND);
        f.repository.rename(f.owner, f.record.getMountId(), f.repository.inspectCollection(f.owner).getRevision(), "Explicit");
        f.services.getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals("Explicit", f.current().getNaming().getCustomName());
        f.naming.tick(); assertEquals("Explicit", f.pig.getCustomNameTag());
    }
    @Test void successfulNonownerNativeTagSurvivesReconciliationWithoutGrantingCollectionPermission() {
        Fixture f = new Fixture();
        f.naming.reconcile(f.pig);
        EntityPlayer visitor = new EntityPlayer(f.world, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "visitor")) {
            public boolean isSpectator() { return false; }
            public boolean isCreative() { return false; }
        };
        Object selected = f.repository.inspectCollection(f.owner).getSelectedMountId();
        assertEquals(MountRepository.RenameStatus.NOT_OWNED, f.repository.rename(visitor.getUniqueID(),
                f.record.getMountId(), f.repository.inspectCollection(f.owner).getRevision(), "Forbidden"));
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.setStackDisplayName("Visitor tag");
        visitor.setHeldItem(EnumHand.MAIN_HAND, tag);
        f.naming.onNameTag(new PlayerInteractEvent.EntityInteract(visitor, EnumHand.MAIN_HAND, f.pig));
        assertTrue(Items.NAME_TAG.itemInteractionForEntity(tag, visitor, f.pig, EnumHand.MAIN_HAND));
        assertTrue(tag.isEmpty(), "vanilla consumed the successful tag");
        f.services.getLifecycleMutationExecutor().drainAtServerTickEnd();
        assertEquals("Visitor tag", f.current().getNaming().getCustomName());
        f.naming.reconcile(f.pig);
        assertEquals("Visitor tag", f.pig.getCustomNameTag());
        assertEquals(selected, f.repository.inspectCollection(f.owner).getSelectedMountId());
        assertFalse(f.current().getNaming().isPending());
        assertEquals(0, f.world.chunkQueries);
    }
    private static final class Fixture {
        final UUID owner = UUID.randomUUID();
        final TestWorld world = new TestWorld();
        final EntityPig pig = new EntityPig(world);
        final EntityPlayer player = new EntityPlayer(world, new com.mojang.authlib.GameProfile(owner, "owner")) {
            public boolean isSpectator() { return false; }
            public boolean isCreative() { return true; }
        };
        final MountRepository repository = new MountRepository();
        final MountRecord record = repository.register(new MountRepository.RegistrationCandidate(owner,
                new ResourceLocation("mountcollection:vanilla"), new ResourceLocation("minecraft:pig"),
                "minecraft:pig", pig.getUniqueID(), new LastKnownEvidence(0, 0, 64, 0), null)).getRecord().get();
        final MountCollectionServices services = new MountCollectionServices(
                new com.mahghuuuls.mountcollection.provider.ProviderRegistry(),
                new com.mahghuuuls.mountcollection.policy.ActiveServerClock(),
                new com.mahghuuuls.mountcollection.diagnostics.MountCollectionDiagnostics(LogManager.getLogger("naming-test")),
                new com.mahghuuuls.mountcollection.integration.inhibited.InhibitedIntegration());
        final ForgeMountNaming naming;
        boolean loaded = true;
        int lookups;
        Fixture() {
            services.activateRepository(repository);
            EntityMountEvidence.attach(pig, record.getMountId());
            naming = new ForgeMountNaming(services, id -> { lookups++; return loaded && id.equals(pig.getUniqueID()) ? pig : null; });
        }
        MountRecord current() { return repository.find(record.getMountId()).get(); }
    }
    private static final class TestWorld extends World {
        int chunkQueries;
        TestWorld() { super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), "test"),
                new WorldProviderSurface(), new Profiler(), false); }
        protected IChunkProvider createChunkProvider() { return null; }
        public net.minecraft.util.math.BlockPos getSpawnPoint() { return new net.minecraft.util.math.BlockPos(0, 64, 0); }
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { chunkQueries++; throw new AssertionError("naming queried a chunk"); }
    }
}
