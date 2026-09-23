package com.mahghuuuls.mountcollection.provider.vanilla;

import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real native data-manager/getter adapter tests, not a live world or mounting test. */
final class VanillaBoardingAdapterTest {
    private final VanillaMountProvider provider = new VanillaMountProvider();
    private final UUID rider = UUID.randomUUID();

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    static Stream<AbstractHorse> horses() {
        TestWorld world = new TestWorld();
        return Stream.of(new EntityHorse(world), new EntityDonkey(world), new EntityMule(world),
                new EntityLlama(world), new EntitySkeletonHorse(world), new EntityZombieHorse(world));
    }

    @ParameterizedTest @MethodSource("horses")
    void everyBuiltInHorseUsesItsNativeOffsetAndAgeTamingRules(AbstractHorse horse) {
        horse.setHorseTamed(true);
        horse.setGrowingAge(0);
        ProviderResult<SeatEnvelope> result = provider.describeBoarding(horse, rider);
        assertTrue(result.isSuccess());
        SeatEnvelope seat = result.getValue().get();
        assertEquals(horse.getMountedYOffset(), seat.getMinY());
        if (horse instanceof EntityLlama) {
            assertEquals(horse.height * .67D, seat.getMinY());
            assertEquals(seat.getMinY(), seat.getMaxY());
        } else {
            assertTrue(seat.getMaxY() >= horse.getMountedYOffset() + .15D);
        }
        horse.setGrowingAge(-24000);
        assertFalse(provider.describeBoarding(horse, rider).isSuccess());
        horse.setGrowingAge(0);
        horse.setHorseTamed(false);
        assertFalse(provider.describeBoarding(horse, rider).isSuccess());
        horse.setHorseTamed(true);
        horse.setDead();
        assertFalse(provider.describeBoarding(horse, rider).isSuccess());
    }

    @Test void pigUsesSaddleAndActualScaledNativeOffset() {
        EntityPig pig = new EntityPig(new TestWorld());
        assertFalse(provider.describeBoarding(pig, rider).isSuccess());
        pig.setSaddled(true);
        assertTrue(provider.describeBoarding(pig, rider).isSuccess());
        pig.setGrowingAge(-24000);
        // Native pig interaction permits a saddled child; do not invent a horse-only age rule.
        assertEquals(pig.getMountedYOffset(),
                provider.describeBoarding(pig, rider).getValue().get().getMinY());
    }

    @Test void unsupportedEntityAndMissingCallerFailClosed() {
        assertFalse(provider.describeBoarding(new EntityBoat(null), rider).isSuccess());
        assertFalse(provider.describeBoarding(null, rider).isSuccess());
        assertFalse(provider.describeBoarding(new EntityPig(null), null).isSuccess());
    }

    private static final class TestWorld extends net.minecraft.world.World {
        TestWorld() {
            super(new net.minecraft.world.storage.SaveHandlerMP(),
                    new net.minecraft.world.storage.WorldInfo(new net.minecraft.world.WorldSettings(
                            0, net.minecraft.world.GameType.CREATIVE, false, false,
                            net.minecraft.world.WorldType.DEFAULT), "boarding"),
                    new net.minecraft.world.WorldProviderSurface(), new net.minecraft.profiler.Profiler(), false);
        }
        protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
        public net.minecraft.util.math.BlockPos getSpawnPoint() { return net.minecraft.util.math.BlockPos.ORIGIN; }
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            throw new AssertionError("Boarding declaration must not query chunks");
        }
    }
}
