package com.mahghuuuls.mountcollection.network;

import static org.junit.jupiter.api.Assertions.*;
import com.mahghuuuls.mountcollection.api.*;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import io.netty.buffer.*;
import java.util.*;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class PreviewCodecTest {
    private MountPreview preview(int version, byte[] bytes) {
        return new MountPreview(new ResourceLocation("test:provider"), new ResourceLocation("minecraft:horse"), version, bytes);
    }
    @Test void descriptorIsBoundedDefensivelyCopiedAndVersioned() {
        byte[] payload = new byte[MountPreview.MAX_PAYLOAD_BYTES]; payload[0] = 12;
        MountPreview value = preview(7, payload); payload[0] = 4; value.copyPayload()[0] = 8;
        assertEquals(12, value.copyPayload()[0]);
        assertThrows(IllegalArgumentException.class, () -> preview(-1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> preview(1, new byte[1025]));
        ByteBuf buf = Unpooled.buffer();
        try {
            PreviewCodec.write(buf, value); assertEquals(PreviewCodec.size(value), buf.readableBytes());
            assertEquals(value, PreviewCodec.read(buf)); assertFalse(buf.isReadable());
        } finally { buf.release(); }
    }
    @Test void badPreviewContentFallsBackButTruncatedEnvelopeRejects() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeShort(4).writeInt(-1); assertNull(PreviewCodec.read(buf));
            buf.clear().writeShort(1400).writeZero(1400); assertNull(PreviewCodec.read(buf));
            assertFalse(buf.isReadable());
            buf.clear().writeShort(10).writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> PreviewCodec.read(buf));
        } finally { buf.release(); }
    }
    @Test void maximumPayloadPagesRespectByteLimitAndAssembleEveryRow() {
        List<CollectionView.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            entries.add(new CollectionView.Entry(MountId.create(), "minecraft:horse", i + 1, i + 1,
                    CollectionView.State.LIVING, 0, MountCharacteristics.solidGround(), "", preview(1, new byte[1024])));
        }
        UUID snapshot = UUID.randomUUID(); CollectionView view = new CollectionView(1, null, entries);
        List<CollectionPage> pages = CollectionPage.split(snapshot, view);
        assertTrue(pages.size() > 3, "byte limit must split before entry count limit");
        CollectionAssembly assembly = new CollectionAssembly();
        assembly.begin(new CollectionHeader(snapshot, 1, entries.size(), pages.size(), null));
        for (CollectionPage page : pages) {
            ByteBuf buf = Unpooled.buffer();
            try {
                page.toBytes(buf); assertTrue(buf.readableBytes() <= CollectionPage.MAX_BYTES);
                CollectionPage decoded = new CollectionPage(); decoded.fromBytes(buf);
                assembly.accept(decoded);
            } finally { buf.release(); }
        }
        assertEquals(130, assembly.finish().getEntries().size());
    }
}
