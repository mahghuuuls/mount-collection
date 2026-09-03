package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.Tags;
import com.mahghuuuls.mountcollection.forge.MountCollectionServices;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import java.util.Objects;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class MountNetwork {

    private final SimpleNetworkWrapper channel = NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID);
    private final IntentGate intentGate = new IntentGate();
    private MountCollectionServices services;

    public void preInitialize(MountCollectionServices services) {
        if (this.services != null) {
            throw new IllegalStateException("network already initialized");
        }
        this.services = Objects.requireNonNull(services, "services");
        channel.registerMessage(
                new ContextualIntentHandler(), ContextualIntentMessage.class, 0, Side.SERVER);
    }

    public void sendContextualIntent() {
        channel.sendToServer(new ContextualIntentMessage());
    }

    public void playerLoggedOut(EntityPlayerMP player) {
        intentGate.remove(player.getUniqueID());
    }

    private final class ContextualIntentHandler
            implements IMessageHandler<ContextualIntentMessage, IMessage> {

        @Override
        public IMessage onMessage(ContextualIntentMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handleOnServerThread(player));
            return null;
        }
    }

    private void handleOnServerThread(EntityPlayerMP player) {
        long activeTick = services.getActiveServerClock().now();
        if (!intentGate.acquire(player.getUniqueID(), activeTick)) {
            return;
        }
        if (!services.submitLifecycleMutation(() -> executeContextualIntent(player))) {
            player.sendMessage(new TextComponentTranslation(
                    ContextualOutcome.Status.INTERNAL_FAILURE.getTranslationKey()));
        }
    }

    private void executeContextualIntent(EntityPlayerMP player) {
        ContextualOutcome outcome;
        try {
            outcome = services.getLifecycleService()
                    .map(service -> service.handleContextualIntent(player))
                    .orElseGet(() -> ContextualOutcome.failure(
                            ContextualOutcome.Status.INTERNAL_FAILURE));
        } catch (FatalTransferSafetyException fatal) {
            throw fatal;
        } catch (RuntimeException exception) {
            outcome = ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE);
        }
        player.sendMessage(new TextComponentTranslation(outcome.getStatus().getTranslationKey()));
    }
}
