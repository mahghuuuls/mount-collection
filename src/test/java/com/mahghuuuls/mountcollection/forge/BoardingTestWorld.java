package com.mahghuuuls.mountcollection.forge;

/** Native entity containers only; no chunk/collision simulation is claimed. */
final class BoardingTestWorld extends net.minecraft.world.World {
    BoardingTestWorld() {
        super(new net.minecraft.world.storage.SaveHandlerMP(),
                new net.minecraft.world.storage.WorldInfo(new net.minecraft.world.WorldSettings(
                        0, net.minecraft.world.GameType.CREATIVE, false, false,
                        net.minecraft.world.WorldType.DEFAULT), "boarding"),
                new net.minecraft.world.WorldProviderSurface(), new net.minecraft.profiler.Profiler(), false);
    }
    protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
    public net.minecraft.util.math.BlockPos getSpawnPoint() { return net.minecraft.util.math.BlockPos.ORIGIN; }
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { throw new AssertionError("unexpected world query"); }
}
