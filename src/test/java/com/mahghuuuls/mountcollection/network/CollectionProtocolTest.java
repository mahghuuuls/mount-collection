package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CollectionProtocolTest {
    @Test void namedPresentationRoundTripsSupplementaryCharactersAndRejectsInvalidLengths() {
        String name = String.join("", Collections.nCopies(32, "\ud83d\udc0e"));
        CollectionView.Entry row = new CollectionView.Entry(MountId.create(), "horse", 1, 1,
                CollectionView.State.LIVING, 0, MountCharacteristics.solidGround(), name);
        ByteBuf bytes = Unpooled.buffer();
        new CollectionPage(UUID.randomUUID(), 7, 0, Collections.singletonList(row)).toBytes(bytes);
        CollectionPage decoded = new CollectionPage(); decoded.fromBytes(bytes.duplicate());
        assertEquals(name, decoded.getEntries().get(0).getCustomName());
        // Prefix + fixed original entry fields + the five-byte type key precede name length.
        int nameOffset = 31 + 41 + 5;
        bytes.setShort(nameOffset, 129);
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(bytes.duplicate()));
        bytes.setShort(nameOffset, 128); bytes.setByte(nameOffset + 2, 255);
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(bytes.duplicate()));
        bytes.release();
    }
    @Test void renameWireRejectsOversizeLengthAndMalformedUtf8ButLeavesContentValidationToServer() {
        ByteBuf bytes = Unpooled.buffer();
        new CollectionIntent(CollectionIntent.Action.RENAME, UUID.randomUUID(), 3, MountId.create(), "\n").toBytes(bytes);
        CollectionIntent decoded = new CollectionIntent(); decoded.fromBytes(bytes.duplicate());
        assertEquals("\n", decoded.getName(), "content rejection must return ordinary INVALID_NAME feedback");
        bytes.setShort(42, 257);
        assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
        bytes.setShort(42, 1); bytes.setByte(44, 255);
        assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
        bytes.release();
        assertThrows(IllegalArgumentException.class, () -> new CollectionIntent(CollectionIntent.Action.RENAME,
                UUID.randomUUID(), 3, MountId.create(), String.join("", Collections.nCopies(257, "x"))));
    }
    @Test void intentCodecRoundTripsAndRejectsEveryTruncationAndInvalidAction() {
        UUID session = UUID.randomUUID();
        for (CollectionIntent.Action action : CollectionIntent.Action.values()) {
            MountId target = action == CollectionIntent.Action.SELECT || action == CollectionIntent.Action.RENAME
                    || action == CollectionIntent.Action.ABANDON ? MountId.create() : null;
            long revision = target == null ? 0 : 27;
            ByteBuf bytes = Unpooled.buffer();
            new CollectionIntent(action, session, revision, target,
                    action == CollectionIntent.Action.RENAME ? "Horse" : null).toBytes(bytes);
            CollectionIntent decoded = new CollectionIntent();
            decoded.fromBytes(bytes.duplicate());
            assertEquals(action, decoded.getAction());
            assertEquals(session, decoded.getSession());
            assertEquals(revision, decoded.getRevision());
            assertEquals(target, decoded.getTarget());
            for (int length = 0; length < bytes.readableBytes(); length++) {
                ByteBuf truncated = bytes.slice(0, length);
                assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(truncated));
            }
            bytes.setLong(18, -1);
            assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
            bytes.setLong(18, revision);
            bytes.setByte(1, 255);
            assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
            bytes.setByte(1, action.ordinal());
            bytes.setByte(0, 255);
            assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
            bytes.setByte(0, CollectionPage.VERSION);
            bytes.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> new CollectionIntent().fromBytes(bytes.duplicate()));
            bytes.release();
        }
    }

    @Test void replyCodecRejectsInvalidResultsVersionsAndLengths() {
        UUID session = UUID.randomUUID();
        for (CollectionReply.Result result : CollectionReply.Result.values()) {
            ByteBuf bytes = Unpooled.buffer();
            new CollectionReply(session, result).toBytes(bytes);
            CollectionReply decoded = new CollectionReply();
            decoded.fromBytes(bytes.duplicate());
            assertEquals(session, decoded.getSession());
            assertEquals(result, decoded.getResult());
            for (int length = 0; length < 18; length++) {
                ByteBuf truncated = bytes.slice(0, length);
                assertThrows(IllegalArgumentException.class, () -> new CollectionReply().fromBytes(truncated));
            }
            bytes.setByte(17, 255);
            assertThrows(IllegalArgumentException.class, () -> new CollectionReply().fromBytes(bytes.duplicate()));
            bytes.setByte(17, result.ordinal());
            bytes.setByte(0, 255);
            assertThrows(IllegalArgumentException.class, () -> new CollectionReply().fromBytes(bytes.duplicate()));
            bytes.setByte(0, CollectionPage.VERSION);
            bytes.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> new CollectionReply().fromBytes(bytes.duplicate()));
            bytes.release();
        }
    }

    @Test void diagnosticsDefaultOffAndRoundTripWithoutChangingSelection() {
        UUID session = UUID.randomUUID();
        MountId selected = MountId.create();
        CollectionHeader header = new CollectionHeader(session, 7, 1, 1, selected);
        assertFalse(header.isDetailed());
        for (boolean enabled : new boolean[] {false, true}) {
            ByteBuf bytes = Unpooled.buffer();
            header.withDetailed(enabled).toBytes(bytes);
            CollectionHeader decoded = new CollectionHeader();
            decoded.fromBytes(bytes);
            assertEquals(enabled, decoded.isDetailed());
            assertEquals(selected, decoded.getSelected());
            assertEquals(session, decoded.getSnapshot());
            bytes.release();
        }
        assertFalse(header.isDetailed());
    }

    @Test void headerRejectsTruncationBadFlagsAndImpossibleTotals() {
        ByteBuf bytes = Unpooled.buffer();
        new CollectionHeader(UUID.randomUUID(), 1L, 1, 1, MountId.create()).toBytes(bytes);
        for (int length = 0; length < bytes.readableBytes(); length++) {
            ByteBuf truncated = bytes.copy(0, length);
            assertThrows(IllegalArgumentException.class, () -> new CollectionHeader().fromBytes(truncated));
            truncated.release();
        }
        bytes.setByte(33, 4);
        assertThrows(IllegalArgumentException.class, () -> new CollectionHeader().fromBytes(bytes));
        bytes.release();
        assertThrows(IllegalArgumentException.class, () -> new CollectionHeader(UUID.randomUUID(), 0, 1, 2, null));
    }

    @Test void byteLimitAndUnicodeAreEnforcedAtProductionCodecBoundary() {
        ByteBuf oversized = Unpooled.buffer(CollectionPage.MAX_BYTES + 1);
        oversized.writeZero(CollectionPage.MAX_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(oversized));
        oversized.release();
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage(UUID.randomUUID(), 0L, 0,
                Collections.singletonList(entry(1, "\uD800"))));
        String key = String.join("", Collections.nCopies(128, "\u99ac"));
        List<CollectionView.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 130; i++) { entries.add(entry(i, key)); }
        List<CollectionPage> pages = CollectionPage.split(UUID.randomUUID(), new CollectionView(0, null, entries));
        assertEquals(3, pages.size());
        for (CollectionPage page : pages) {
            ByteBuf bytes = Unpooled.buffer();
            page.toBytes(bytes);
            assertTrue(bytes.readableBytes() <= CollectionPage.MAX_BYTES);
            CollectionPage decoded = new CollectionPage();
            decoded.fromBytes(bytes);
            assertEquals(key, decoded.getEntries().get(0).getTypeKey());
            bytes.release();
        }
    }

    @Test void changedRevisionAndMissingSelectedEntryRejectAssembly() {
        UUID id = UUID.randomUUID();
        CollectionAssembly assembly = new CollectionAssembly();
        assembly.begin(new CollectionHeader(id, 1, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(new CollectionPage(id, 2, 0,
                Collections.singletonList(entry(1, "horse")))));
        assembly.begin(new CollectionHeader(id, 1, 1, 1, MountId.create()));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(new CollectionPage(id, 1, 0,
                Collections.singletonList(entry(1, "horse")))));
        assertFalse(assembly.isComplete());
    }
    private CollectionView.Entry entry(int ordinal, String key) {
        return new CollectionView.Entry(MountId.create(), key, ordinal, ordinal,
                CollectionView.State.RECOVERING, 200L,
                new MountCharacteristics(PlacementProfile.WATER, EnumSet.of(MountTrait.FLYING)));
    }
    @Test void roundTripAndOutOfOrderAssemblyRetainEveryEntryBeyondOnePage() {
        List<CollectionView.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 10000; i++) { entries.add(entry(i, "minecraft:horse")); }
        CollectionView source = new CollectionView(12L, entries.get(8888).getId(), entries);
        UUID id = UUID.randomUUID();
        List<CollectionPage> pages = CollectionPage.split(id, source);
        assertEquals(157, pages.size());
        CollectionHeader sourceHeader = new CollectionHeader(id, 12L, entries.size(), pages.size(), source.getSelected());
        ByteBuf headerBytes = Unpooled.buffer();
        sourceHeader.toBytes(headerBytes);
        CollectionHeader header = new CollectionHeader();
        header.fromBytes(headerBytes);
        headerBytes.release();
        CollectionAssembly assembly = new CollectionAssembly();
        assembly.begin(header);
        for (int i = pages.size() - 1; i >= 0; i--) {
            ByteBuf bytes = Unpooled.buffer();
            pages.get(i).toBytes(bytes);
            assertTrue(bytes.readableBytes() <= CollectionPage.MAX_BYTES);
            CollectionPage decoded = new CollectionPage();
            decoded.fromBytes(bytes);
            bytes.release();
            assembly.accept(decoded);
        }
        assertTrue(assembly.isComplete());
        CollectionView restored = assembly.finish();
        assertEquals(source.getSelected(), restored.getSelected());
        assertEquals(10000, restored.getEntries().size());
        for (int i = 0; i < 10000; i++) {
            assertEquals(entries.get(i).getId(), restored.getEntries().get(i).getId());
            assertEquals(entries.get(i).getCharacteristics(), restored.getEntries().get(i).getCharacteristics());
            assertEquals(200L, restored.getEntries().get(i).getRecoveryTicks());
        }
    }
    @Test void rejectsEveryTruncationAndTrailingData() {
        CollectionPage page = new CollectionPage(UUID.randomUUID(), 1L, 0,
                Collections.singletonList(entry(1, "horse")));
        ByteBuf encoded = Unpooled.buffer();
        page.toBytes(encoded);
        for (int length = 0; length < encoded.readableBytes(); length++) {
            ByteBuf truncated = encoded.copy(0, length);
            assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(truncated));
            truncated.release();
        }
        encoded.writeByte(0);
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(encoded));
        encoded.release();
    }
    @Test void rejectsUntrustedLengthsCountsIdentifiersAndUtf8() {
        ByteBuf source = Unpooled.buffer();
        new CollectionPage(UUID.randomUUID(), 1L, 0, Collections.singletonList(entry(1, "horse"))).toBytes(source);
        for (int offset : new int[] {0, 29, 47, 47 + 2 + 5 + 4 + 8, source.writerIndex() - 1}) {
            ByteBuf bad = source.copy();
            bad.setByte(offset, 255);
            assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(bad));
            bad.release();
        }
        ByteBuf badUtf = source.copy();
        badUtf.setByte(49, 255);
        assertThrows(IllegalArgumentException.class, () -> new CollectionPage().fromBytes(badUtf));
        badUtf.release(); source.release();
    }
    @Test void duplicateAndMismatchedPagesInvalidateWithoutPartialPublication() {
        UUID id = UUID.randomUUID();
        CollectionView.Entry entry = entry(1, "horse");
        CollectionPage page = new CollectionPage(id, 1L, 0, Collections.singletonList(entry));
        CollectionAssembly assembly = new CollectionAssembly();
        assembly.begin(new CollectionHeader(id, 1L, 2, 2, null));
        assembly.accept(page);
        assertThrows(IllegalStateException.class, assembly::finish);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(page));
        assertEquals(0, assembly.getReceived());
        assembly.begin(new CollectionHeader(id, 1L, 2, 2, null));
        assembly.accept(page);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(
                new CollectionPage(id, 1L, 1, Collections.singletonList(entry))));
        assembly.begin(new CollectionHeader(UUID.randomUUID(), 1L, 0, 0, null));
        assertFalse(assembly.accept(page));
        assertTrue(assembly.finish().getEntries().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CollectionHeader(id, 1L, Integer.MAX_VALUE, 1, null));
    }
}
