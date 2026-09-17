package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.collection.CollectionView;
import com.mahghuuuls.mountcollection.persistence.MountId;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Fixed-version bounded page. Decode validates lengths before allocation. */
public final class CollectionPage implements IMessage {
    public static final int VERSION = 5;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_BYTES = 32768;
    private static final int MAX_TEXT_BYTES = 512;
    private static final int PREFIX_BYTES = 31;
    private UUID snapshot;
    private long revision;
    private int index;
    private List<CollectionView.Entry> entries;

    public CollectionPage() { }

    public CollectionPage(UUID snapshot, long revision, int index, List<CollectionView.Entry> entries) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (revision < 0 || index < 0 || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid page metadata");
        }
        this.revision = revision;
        this.index = index;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        int size = PREFIX_BYTES;
        for (CollectionView.Entry entry : entries) { size += encodedSize(entry); }
        if (size > MAX_BYTES) { throw new IllegalArgumentException("oversized page"); }
    }

    public UUID getSnapshot() { return snapshot; }
    public long getRevision() { return revision; }
    public int getIndex() { return index; }
    public List<CollectionView.Entry> getEntries() { return entries; }

    public static List<CollectionPage> split(UUID snapshot, CollectionView view) {
        List<CollectionPage> pages = new ArrayList<>();
        List<CollectionView.Entry> batch = new ArrayList<>();
        int bytes = PREFIX_BYTES;
        for (CollectionView.Entry entry : view.getEntries()) {
            int size = encodedSize(entry);
            if (batch.size() == MAX_ENTRIES || bytes + size > MAX_BYTES) {
                pages.add(new CollectionPage(snapshot, view.getSelectionRevision(), pages.size(), batch));
                batch.clear();
                bytes = PREFIX_BYTES;
            }
            batch.add(entry);
            bytes += size;
        }
        if (!batch.isEmpty()) {
            pages.add(new CollectionPage(snapshot, view.getSelectionRevision(), pages.size(), batch));
        }
        return Collections.unmodifiableList(pages);
    }

    private static int encodedSize(CollectionView.Entry entry) {
        int text = encodeText(entry.getTypeKey()).length;
        if (text > MAX_TEXT_BYTES) { throw new IllegalArgumentException("oversized type key"); }
        return 43 + text + encodeText(entry.getCustomName()).length + PreviewCodec.size(entry.getPreview());
    }

    static byte[] encodeText(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid type key Unicode", invalid);
        }
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeByte(VERSION);
        out.writeLong(snapshot.getMostSignificantBits());
        out.writeLong(snapshot.getLeastSignificantBits());
        out.writeLong(revision);
        out.writeInt(index);
        out.writeShort(entries.size());
        for (CollectionView.Entry entry : entries) {
            out.writeLong(entry.getId().asUuid().getMostSignificantBits());
            out.writeLong(entry.getId().asUuid().getLeastSignificantBits());
            byte[] text = encodeText(entry.getTypeKey());
            out.writeShort(text.length);
            out.writeBytes(text);
            out.writeInt(entry.getOrdinal());
            out.writeLong(entry.getOrder());
            out.writeByte(entry.getState().ordinal());
            out.writeLong(entry.getRecoveryTicks());
            out.writeByte(entry.getCharacteristics().getPlacementProfile().ordinal());
            out.writeByte((entry.getCharacteristics().hasTrait(MountTrait.FLYING) ? 1 : 0)
                    | (entry.isSummoningDisabled() ? 2 : 0));
            byte[] name = encodeText(entry.getCustomName());
            out.writeShort(name.length); out.writeBytes(name);
            PreviewCodec.write(out, entry.getPreview());
        }
    }

    @Override
    public void fromBytes(ByteBuf in) {
        if (in.readableBytes() < PREFIX_BYTES || in.readableBytes() > MAX_BYTES
                || in.readUnsignedByte() != VERSION) {
            throw new IllegalArgumentException("invalid collection page size/version");
        }
        UUID id = new UUID(in.readLong(), in.readLong());
        long rev = in.readLong();
        int pageIndex = in.readInt();
        int count = in.readUnsignedShort();
        if (rev < 0 || pageIndex < 0 || count < 1 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid collection page metadata");
        }
        List<CollectionView.Entry> decoded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (in.readableBytes() < 45) { throw new IllegalArgumentException("truncated entry"); }
            MountId mount = MountId.fromUuid(new UUID(in.readLong(), in.readLong()));
            int length = in.readUnsignedShort();
            if (length < 1 || length > MAX_TEXT_BYTES || in.readableBytes() < length + 25) {
                throw new IllegalArgumentException("invalid type key length");
            }
            byte[] text = new byte[length];
            in.readBytes(text);
            String key;
            try {
                key = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("invalid UTF-8 type key", invalid);
            }
            int ordinal = in.readInt();
            long order = in.readLong();
            int state = in.readUnsignedByte();
            long ticks = in.readLong();
            int profile = in.readUnsignedByte();
            int traits = in.readUnsignedByte();
            int nameLength = in.readUnsignedShort();
            if (nameLength > 128 || in.readableBytes() < nameLength) { throw new IllegalArgumentException("invalid name length"); }
            byte[] nameBytes = new byte[nameLength]; in.readBytes(nameBytes);
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("invalid name Unicode", invalid); }
            if (state >= CollectionView.State.values().length
                    || profile >= PlacementProfile.values().length || (traits & ~3) != 0) {
                throw new IllegalArgumentException("unknown presentation identifier");
            }
            decoded.add(new CollectionView.Entry(mount, key, ordinal, order,
                    CollectionView.State.values()[state], ticks, new MountCharacteristics(
                            PlacementProfile.values()[profile], (traits & 1) == 0
                                    ? Collections.emptySet() : EnumSet.of(MountTrait.FLYING)), name, PreviewCodec.read(in), (traits & 2) != 0));
        }
        if (in.isReadable()) { throw new IllegalArgumentException("trailing page bytes"); }
        snapshot = id;
        revision = rev;
        index = pageIndex;
        entries = Collections.unmodifiableList(decoded);
    }
}
