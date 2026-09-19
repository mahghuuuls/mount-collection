package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.lifecycle.ExperienceCompletion;
import com.mahghuuuls.mountcollection.persistence.EntityMountEvidence;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.network.play.server.SPacketSoundEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/** Native output only; the coordinator has already consumed live completion eligibility. */
final class ForgeExperienceDelivery {
    void deliver(ExperienceCompletion completion) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        EntityPlayerMP player = server == null ? null
                : server.getPlayerList().getPlayerByUUID(completion.getOwnerId());
        if (player == null || !player.connection.getNetworkManager().isChannelOpen()) { return; }
        if (completion.getKind() == ExperienceCompletion.Kind.REGISTERED) {
            player.connection.sendPacket(new SPacketSoundEffect(SoundEvents.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, player.posX, player.posY, player.posZ, 1.0F, 1.0F));
            return;
        }
        WorldServer world = server.getWorld(completion.getLocation().getDimensionId());
        if (world == null || player.getServerWorld() != world) { return; }
        // Never load a chunk for a cosmetic effect or guess another representative.
        Entity mount = world.getEntityFromUuid(completion.getEntityId());
        if (mount == null || mount.isDead
                || !EntityMountEvidence.read(mount).getMountId().filter(completion.getMountId()::equals).isPresent()) {
            return;
        }
        world.spawnParticle(EnumParticleTypes.SPELL_INSTANT, false,
                mount.posX, mount.posY + mount.height * 0.5D, mount.posZ, 16,
                Math.min(1.0D, mount.width * 0.5D), Math.min(1.0D, mount.height * 0.4D),
                Math.min(1.0D, mount.width * 0.5D), 0.02D);
    }
}
