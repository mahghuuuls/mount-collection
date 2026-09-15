package com.mahghuuuls.mountcollection.client.preview;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.api.MountPreview;
import com.mahghuuuls.mountcollection.persistence.MountId;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.*;
import org.junit.jupiter.api.*;

final class PreviewSessionTest {
    private static final ResourceLocation ID = new ResourceLocation("test:preview");
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    private MountPreview data(int version) { return new MountPreview(ID, new ResourceLocation("minecraft:pig"), version, new byte[0]); }
    @Test void factoriesAreFrozenAndFailuresAreCachedUntilIdentityChanges() {
        ClientPreviewRegistry registry = new ClientPreviewRegistry(); int[] calls = {0};
        registry.register(ID, (data, world) -> { calls[0]++; throw new IllegalStateException("broken provider"); });
        assertThrows(IllegalArgumentException.class, () -> registry.register(ID, (data, world) -> null));
        registry.freeze(); assertThrows(IllegalStateException.class, () -> registry.register(new ResourceLocation("test:other"), (d,w) -> null));
        PreviewSession session = new PreviewSession(registry); TestWorld world = new TestWorld(); MountId id = MountId.create();
        assertNull(session.prepare(id, data(1), world)); assertNull(session.prepare(id, data(1), world)); assertEquals(1, calls[0]);
        session.prepare(id, data(2), world); assertEquals(2, calls[0]);
        session.rotate(30); assertEquals(55, session.getYaw()); session.clear(); assertEquals(25, session.getYaw());
        session.prepare(id, data(2), world); assertEquals(3, calls[0]);
    }
    @Test void representationIsDetachedAndClearedOnWorldChangeOrRenderFailure() {
        ClientPreviewRegistry registry = new ClientPreviewRegistry(); int[] calls = {0};
        registry.register(ID, (data, world) -> { calls[0]++; return new EntityPig(world); }); registry.freeze();
        PreviewSession session = new PreviewSession(registry); TestWorld world = new TestWorld(); MountId id = MountId.create();
        Object first = session.prepare(id, data(1), world); assertNotNull(first);
        assertSame(first, session.prepare(id, data(1), world)); assertTrue(world.loadedEntityList.isEmpty());
        session.failed(); assertNull(session.prepare(id, data(1), world)); assertEquals(1, calls[0]);
        assertNotNull(session.prepare(id, data(1), new TestWorld())); assertEquals(2, calls[0]);
        assertNull(session.prepare(id, data(1), null));
    }
    @Test void existingWorldEntityAndUnsupportedVersionDoNotBecomePreviews() {
        TestWorld world = new TestWorld(); EntityPig pig = new EntityPig(world); world.loadedEntityList.add(pig);
        ClientPreviewRegistry registry = new ClientPreviewRegistry(); registry.register(ID, (d,w) -> pig); registry.freeze();
        assertNull(registry.create(data(1), world));
        assertNull(new VanillaPreviewProvider().create(data(99), world));
        assertNotNull(new VanillaPreviewProvider().create(data(1), world));
    }
    private static final class TestWorld extends World {
        TestWorld() { super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), "preview"),
                new WorldProviderSurface(), new Profiler(), true); }
        protected IChunkProvider createChunkProvider() { return null; }
        public net.minecraft.util.math.BlockPos getSpawnPoint() { return net.minecraft.util.math.BlockPos.ORIGIN; }
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { throw new AssertionError("preview queried a chunk"); }
    }
}
