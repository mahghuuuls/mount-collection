package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.mahghuuuls.mountcollection.collection.CollectionService;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import org.junit.jupiter.api.Test;

final class CollectionSessionsTest {
    @Test void abandonmentRequiresCompletedMatchingSessionBeforeCallingLifecycle() {
        UUID owner = UUID.randomUUID(); UUID id = UUID.randomUUID();
        MountRecord record = register(owner);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<CollectionIntent, CollectionReply.Result> abandon = intent -> {
            calls.incrementAndGet(); assertEquals(record.getMountId(), intent.getTarget());
            return CollectionReply.Result.ABANDONMENT_PENDING;
        };
        open(owner, id, 0);
        CollectionIntent valid = new CollectionIntent(CollectionIntent.Action.ABANDON, id, 1, record.getMountId());
        sessions.request(owner, valid, 5, service, output, abandon);
        assertEquals(0, calls.get());
        sessions.tick(6, output);
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.ABANDON,
                UUID.randomUUID(), 1, record.getMountId()), 10, service, output, abandon);
        assertEquals(0, calls.get());
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.ABANDON,
                id, 0, record.getMountId()), 15, service, output, abandon);
        assertEquals(0, calls.get());
        sessions.request(owner, valid, 20, service, output, abandon);
        assertEquals(1, calls.get());
        assertTrue(sent.stream().anyMatch(message -> message instanceof CollectionReply
                && ((CollectionReply) message).getResult() == CollectionReply.Result.ABANDONMENT_PENDING));
    }
    private final MountRepository repository = new MountRepository();
    private final CollectionService service = new CollectionService(repository);
    private final CollectionSessions sessions = new CollectionSessions();
    private final List<IMessage> sent = new ArrayList<>();
    private final BiConsumer<UUID, IMessage> output = (owner, message) -> sent.add(message);
    private MountRecord register(UUID owner) {
        return repository.register(new MountRepository.RegistrationCandidate(owner,
                new ResourceLocation("mountcollection:vanilla"), new ResourceLocation("minecraft:horse"),
                "minecraft:horse", UUID.randomUUID(), new LastKnownEvidence(0, 0, 64, 0), null))
                .getRecord().get();
    }
    private void open(UUID owner, UUID id, long tick) {
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.OPEN, id, 0, null), tick, service, output);
    }
    @Test void rateLimitingSupersessionCloseAndLogoutPreserveOneOwnerStream() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 130; i++) { register(owner); }
        UUID first = UUID.randomUUID(); UUID next = UUID.randomUUID();
        open(owner, first, 0); open(owner, next, 1);
        assertEquals(CollectionReply.Result.RATE_LIMITED, ((CollectionReply) sent.get(1)).getResult());
        sessions.tick(2, output);
        assertEquals(first, ((CollectionPage) sent.get(2)).getSnapshot());
        open(owner, next, 20);
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.CLOSE, first, 0, null), 21, service, output);
        assertEquals(1, sessions.activeCount());
        sessions.tick(21, output);
        assertEquals(next, ((CollectionPage) sent.get(sent.size() - 1)).getSnapshot());
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.CLOSE, next, 0, null), 22, service, output);
        assertEquals(0, sessions.activeCount());
        sessions.remove(owner); open(owner, first, 23);
        assertEquals(1, sessions.activeCount());
        sessions.clear(); assertEquals(0, sessions.activeCount());
    }
    @Test void incompleteForgedAndStaleSelectionsCannotMutateAuthority() {
        UUID owner = UUID.randomUUID(); UUID id = UUID.randomUUID();
        MountRecord first = register(owner); MountRecord second = register(owner);
        open(owner, id, 0);
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.SELECT, id, 2, first.getMountId()), 5, service, output);
        assertEquals(CollectionReply.Result.STALE, ((CollectionReply) sent.get(sent.size() - 1)).getResult());
        assertEquals(second.getMountId(), repository.inspectCollection(owner).getSelectedMountId().get());
        sessions.tick(6, output);
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.SELECT, UUID.randomUUID(), 2, first.getMountId()), 10, service, output);
        assertEquals(CollectionReply.Result.STALE, ((CollectionReply) sent.get(sent.size() - 1)).getResult());
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.SELECT, id, 2, first.getMountId()), 15, service, output);
        assertEquals(first.getMountId(), repository.inspectCollection(owner).getSelectedMountId().get());
        sessions.tick(16, output);
        MountRecord foreign = register(UUID.randomUUID());
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.SELECT, id, 3, foreign.getMountId()), 20, service, output);
        assertTrue(sent.stream().anyMatch(message -> message instanceof CollectionReply
                && ((CollectionReply) message).getResult() == CollectionReply.Result.NOT_OWNED));
        assertEquals(first.getMountId(), repository.inspectCollection(owner).getSelectedMountId().get());
    }
    @Test void globalPageBudgetIsFairAndIdleSessionsExpire() {
        for (int i = 0; i < 9; i++) {
            UUID owner = UUID.randomUUID(); register(owner); open(owner, UUID.randomUUID(), 0);
        }
        sent.clear();
        sessions.tick(1, output); assertEquals(4, sent.size());
        sessions.tick(2, output); assertEquals(8, sent.size());
        sessions.tick(3, output); assertEquals(9, sent.size());
        assertEquals(9, sent.stream().map(message -> ((CollectionPage) message).getSnapshot()).distinct().count());
        sessions.tick(604, output); assertEquals(0, sessions.activeCount());
    }
    @Test void emptyCollectionHasNoPagesAndCompletedStreamDoesNotResend() {
        UUID owner = UUID.randomUUID(); UUID id = UUID.randomUUID();
        open(owner, id, 0);
        assertEquals(0, ((CollectionHeader) sent.get(0)).getPages());
        sessions.tick(1, output); assertEquals(1, sent.size());
        assertFalse(sent.stream().anyMatch(message -> message instanceof CollectionPage));
    }
    @Test void renameUsesCurrentOwnerSessionAndRefreshesWithoutSelecting() {
        UUID owner = UUID.randomUUID(); UUID id = UUID.randomUUID();
        MountRecord first = register(owner); MountRecord second = register(owner);
        open(owner, id, 0); sessions.tick(1, output);
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.RENAME, id, 2, first.getMountId(), "New name"),
                5, service, output);
        assertEquals("New name", repository.find(first.getMountId()).get().getNaming().getCustomName());
        assertEquals(second.getMountId(), repository.inspectCollection(owner).getSelectedMountId().get());
        assertTrue(sent.stream().anyMatch(message -> message instanceof CollectionReply
                && ((CollectionReply) message).getResult() == CollectionReply.Result.RENAMED));
        sessions.tick(6, output);
        CollectionPage page = (CollectionPage) sent.get(sent.size() - 1);
        assertEquals("New name", page.getEntries().get(0).getCustomName());
        sessions.request(owner, new CollectionIntent(CollectionIntent.Action.RENAME, id, 2, first.getMountId(), "Stale"),
                10, service, output);
        assertEquals(CollectionReply.Result.STALE, ((CollectionReply) sent.get(sent.size() - 1)).getResult());
        assertEquals("New name", repository.find(first.getMountId()).get().getNaming().getCustomName());
    }
}
