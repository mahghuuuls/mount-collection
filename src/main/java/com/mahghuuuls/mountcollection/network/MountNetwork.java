package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.Tags;
import com.mahghuuuls.mountcollection.forge.MountCollectionServices;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;
import com.mahghuuuls.mountcollection.collection.CollectionService;
import net.minecraftforge.fml.common.FMLCommonHandler;
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
    private final CollectionSessions collections = new CollectionSessions(
            owner -> services.consumeCollectionTimeout(owner));
    private final CollectionIngress collectionIngress = new CollectionIngress();
    private Consumer<IMessage> clientCollectionReceiver = ignored -> { };
    private java.util.function.BiConsumer<ExperienceProtocol, Object> clientProtocolReceiver = (message, connection) -> { };
    private final Map<UUID, ContextualSession> contextualSessions = new java.util.HashMap<>();
    private UUID clientSession;
    private long clientSequence;

    public void preInitialize(MountCollectionServices services) {
        if (this.services != null) {
            throw new IllegalStateException("network already initialized");
        }
        this.services = Objects.requireNonNull(services, "services");
        channel.registerMessage(
                new ContextualIntentHandler(), ContextualIntentMessage.class, 0, Side.SERVER);
        channel.registerMessage((IMessageHandler<CollectionIntent, IMessage>) (message, context) -> {
            EntityPlayerMP player = context.getServerHandler().player;
            try {
            collectionIngress.dispatch(player.getUniqueID(), message, System.nanoTime(), task -> {
                if (message.getAction() == CollectionIntent.Action.ABANDON) {
                    if (!services.submitLifecycleMutation(task)) { throw new java.util.concurrent.RejectedExecutionException(); }
                } else { player.getServerWorld().addScheduledTask(task); }
            }, admitted -> {
                if (!currentPlayer(player) || !protocolReady(player)) { return; }
                try {
                    CollectionService service = services.getActiveRepository()
                            .map(repository -> new CollectionService(repository, services.getProviderRegistry(),
                                    services.getActiveConfig().orElseThrow(() -> new IllegalStateException("server configuration unavailable")))).orElse(null);
                    collections.request(player.getUniqueID(), admitted, services.getActiveServerClock().now(),
                            service, this::sendCollection, confirmed -> abandon(player.getUniqueID(), confirmed));
                } catch (FatalTransferSafetyException fatal) {
                    throw fatal;
                } catch (RuntimeException failure) {
                    collections.remove(player.getUniqueID());
                    sendCollection(player.getUniqueID(), new CollectionReply(admitted.getSession(), CollectionReply.Result.UNAVAILABLE));
                }
            });
            } catch (java.util.concurrent.RejectedExecutionException unavailable) {
                channel.sendTo(new CollectionReply(message.getSession(), CollectionReply.Result.UNAVAILABLE), player);
            }
            return null;
        }, CollectionIntent.class, 1, Side.SERVER);
        channel.registerMessage((IMessageHandler<CollectionHeader, IMessage>) (message, context) -> {
            clientCollectionReceiver.accept(message); return null;
        }, CollectionHeader.class, 2, Side.CLIENT);
        channel.registerMessage((IMessageHandler<CollectionPage, IMessage>) (message, context) -> {
            clientCollectionReceiver.accept(message); return null;
        }, CollectionPage.class, 3, Side.CLIENT);
        channel.registerMessage((IMessageHandler<CollectionReply, IMessage>) (message, context) -> {
            clientCollectionReceiver.accept(message); return null;
        }, CollectionReply.class, 4, Side.CLIENT);
        channel.registerMessage((IMessageHandler<ExperienceProtocol, IMessage>) (message, context) -> {
            clientProtocolReceiver.accept(message, context.netHandler);
            return null;
        }, ExperienceProtocol.class, 5, Side.CLIENT);
        channel.registerMessage((IMessageHandler<ExperienceProtocolAck, IMessage>) (message, context) -> {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                ContextualSession session = contextualSessions.get(player.getUniqueID());
                if (session != null && currentPlayer(player)) { session.acknowledge(message.getSession()); }
            });
            return null;
        }, ExperienceProtocolAck.class, 6, Side.SERVER);
    }

    public void setClientProtocolReceiver(java.util.function.BiConsumer<ExperienceProtocol, Object> receiver) {
        clientProtocolReceiver = Objects.requireNonNull(receiver);
    }
    public void acceptClientProtocol(ExperienceProtocol message) {
        clientSession = message.getSession();
        clientSequence = 0L;
        channel.sendToServer(new ExperienceProtocolAck(clientSession));
    }
    public void clearClientProtocol() { clientSession = null; clientSequence = 0L; }

    public void playerLoggedIn(EntityPlayerMP player) {
        ContextualSession session = new ContextualSession();
        contextualSessions.put(player.getUniqueID(), session);
        channel.sendTo(new ExperienceProtocol(session.id()), player);
    }
    private boolean currentPlayer(EntityPlayerMP player) {
        return player.isEntityAlive() && player.connection.getNetworkManager().isChannelOpen()
                && player.getServer().getPlayerList().getPlayerByUUID(player.getUniqueID()) == player;
    }
    private boolean protocolReady(EntityPlayerMP player) {
        ContextualSession session = contextualSessions.get(player.getUniqueID());
        return session != null && session.isReady();
    }

    public void setClientCollectionReceiver(Consumer<IMessage> receiver) {
        clientCollectionReceiver = Objects.requireNonNull(receiver, "receiver");
    }

    private CollectionReply.Result abandon(UUID owner, CollectionIntent intent) {
        com.mahghuuuls.mountcollection.lifecycle.MountLifecycleService lifecycle =
                services.getLifecycleService().orElse(null);
        if (lifecycle == null) { return CollectionReply.Result.UNAVAILABLE; }
        switch (lifecycle.abandon(owner, intent.getTarget(), intent.getRevision())) {
            case SUCCESS: return CollectionReply.Result.ABANDONED;
            case PENDING: return CollectionReply.Result.ABANDONMENT_PENDING;
            case STALE: return CollectionReply.Result.STALE;
            case NOT_OWNED: return CollectionReply.Result.NOT_OWNED;
            case READ_ONLY: return CollectionReply.Result.READ_ONLY;
            case BUSY: return CollectionReply.Result.BUSY;
            case SAVE_FAILED: return CollectionReply.Result.SAVE_FAILED;
            default: return CollectionReply.Result.UNAVAILABLE;
        }
    }
    public void sendCollectionIntent(CollectionIntent intent) { channel.sendToServer(intent); }
    public void collectionTick() {
        collections.tick(services.getActiveServerClock().now(), this::sendCollection);
        services.getExperience().retainPending(request -> services.getActiveRepository().map(repository ->
                repository.findTransfer(request).isPresent() || repository.findRestoration(request).isPresent()).orElse(false));
    }
    public void clearCollectionSessions() {
        collections.clear();
        collectionIngress.clear();
        contextualSessions.clear();
    }
    private void sendCollection(UUID owner, IMessage message) {
        net.minecraft.server.MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        EntityPlayerMP player = server == null ? null : server.getPlayerList().getPlayerByUUID(owner);
        if (player != null && player.connection.getNetworkManager().isChannelOpen()) {
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("owner", owner.toString());
            String event;
            if (message instanceof CollectionHeader) {
                CollectionHeader header = ((CollectionHeader) message).withDetailed(services.getDiagnostics().isDetailedEnabled());
                message = header;
                event = "collection_header";
                fields.put("snapshot", header.getSnapshot().toString());
                fields.put("entries", Integer.toString(header.getTotal()));
                fields.put("pages", Integer.toString(header.getPages()));
            } else if (message instanceof CollectionPage) {
                CollectionPage page = (CollectionPage) message;
                event = "collection_page";
                fields.put("snapshot", page.getSnapshot().toString());
                fields.put("index", Integer.toString(page.getIndex()));
                fields.put("entries", Integer.toString(page.getEntries().size()));
            } else {
                CollectionReply reply = (CollectionReply) message;
                event = "collection_result";
                fields.put("snapshot", reply.getSession().toString());
                fields.put("result", reply.getResult().name());
            }
            services.getDiagnostics().detail(com.mahghuuuls.mountcollection.diagnostics.DiagnosticCategory.COLLECTION, event, fields);
            channel.sendTo(message, player);
        }
    }

    public void sendContextualIntent() {
        sendContextualIntent(true);
    }

    public void sendContextualIntent(boolean automaticRiding) {
        if (clientSession != null && clientSequence < Long.MAX_VALUE) {
            channel.sendToServer(new ContextualIntentMessage(clientSession, ++clientSequence, automaticRiding));
        }
    }

    public void playerLoggedOut(EntityPlayerMP player) {
        services.clearCollectionTimeout(player.getUniqueID());
        intentGate.remove(player.getUniqueID());
        contextualSessions.remove(player.getUniqueID());
        services.getExperience().removeOwner(player.getUniqueID());
        collections.remove(player.getUniqueID());
        collectionIngress.remove(player.getUniqueID());
    }

    public void invalidatePlayerExperience(UUID owner) {
        ContextualSession session = contextualSessions.get(owner);
        if (session != null) { session.invalidatePending(); }
        services.getExperience().removeOwner(owner);
    }

    private final class ContextualIntentHandler
            implements IMessageHandler<ContextualIntentMessage, IMessage> {

        @Override
        public IMessage onMessage(ContextualIntentMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handleOnServerThread(player, message));
            return null;
        }
    }

    private void handleOnServerThread(EntityPlayerMP player, ContextualIntentMessage message) {
        ContextualSession session = contextualSessions.get(player.getUniqueID());
        if (!currentPlayer(player) || session == null || !session.admit(message)) { return; }
        long activeTick = services.getActiveServerClock().now();
        if (!intentGate.acquire(player.getUniqueID(), activeTick)) {
            return;
        }
        int dimension = player.dimension;
        long generation = session.generation();
        if (!services.submitLifecycleMutation(() -> {
            if (currentPlayer(player) && player.dimension == dimension
                    && session.generation() == generation
                    && contextualSessions.get(player.getUniqueID()) == session) {
                executeContextualIntent(player, session, dimension, message.isAutomaticRiding());
            }
        })) {
            player.sendMessage(new TextComponentTranslation(
                    ContextualOutcome.Status.INTERNAL_FAILURE.getTranslationKey()));
        }
    }

    private void executeContextualIntent(EntityPlayerMP player, ContextualSession session, int dimension,
            boolean automaticRiding) {
        UUID request = UUID.randomUUID();
        boolean[] boardingFailureReported = {false};
        long generation = session.generation();
        if (!services.getExperience().admit(request, player.getUniqueID(),
                () -> currentPlayer(player) && player.dimension == dimension
                        && session.generation() == generation
                        && contextualSessions.get(player.getUniqueID()) == session, completion -> {
                    if (automaticRiding && !services.boardArrived(player, completion)) {
                        boardingFailureReported[0] = true;
                        player.sendMessage(new TextComponentTranslation("mountcollection.message.boarding_failed"));
                    }
                }, () -> automaticRiding
                        ? com.mahghuuuls.mountcollection.lifecycle.RecoveryAdmission.automatic(player)
                        : com.mahghuuuls.mountcollection.lifecycle.RecoveryAdmission.optOut())) {
            player.sendMessage(new TextComponentTranslation(ContextualOutcome.Status.TEMPORARILY_UNAVAILABLE.getTranslationKey()));
            return;
        }
        ContextualOutcome outcome = executeLifecycleIntent(() -> services.getLifecycleService()
                .map(service -> service.handleContextualIntent(player, request, automaticRiding))
                .orElseGet(() -> ContextualOutcome.failure(
                        ContextualOutcome.Status.INTERNAL_FAILURE)));
        boolean pending = services.getActiveRepository().map(repository ->
                repository.findTransfer(request).isPresent() || repository.findRestoration(request).isPresent()).orElse(false);
        if (!pending) { services.getExperience().cancel(request); }
        if (!boardingFailureReported[0] || outcome.getStatus() != ContextualOutcome.Status.RECALLED) {
            player.sendMessage(new TextComponentTranslation(outcome.getStatus().getTranslationKey()));
        }
    }

    static ContextualOutcome executeLifecycleIntent(Supplier<ContextualOutcome> intent) {
        Objects.requireNonNull(intent, "intent");
        try {
            return intent.get();
        } catch (FatalTransferSafetyException fatal) {
            throw fatal;
        } catch (RuntimeException exception) {
            return ContextualOutcome.failure(ContextualOutcome.Status.INTERNAL_FAILURE);
        }
    }
}
