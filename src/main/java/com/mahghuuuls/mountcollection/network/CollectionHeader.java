package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.persistence.MountId;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class CollectionHeader implements IMessage {
    private UUID snapshot;
    private long revision;
    private int total;
    private int pages;
    private MountId selected;
    private boolean detailed;

    public CollectionHeader() { }
    public CollectionHeader(UUID snapshot, long revision, int total, int pages, MountId selected) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        validate(revision, total, pages);
        if (total == 0 && selected != null) { throw new IllegalArgumentException("empty selection"); }
        this.revision = revision;
        this.total = total;
        this.pages = pages;
        this.selected = selected;
    }
    private static void validate(long revision, int total, int pages) {
        if (revision < 0 || total < 0 || pages < 0 || pages > total
                || (total == 0) != (pages == 0) || (long) pages * CollectionPage.MAX_ENTRIES < total) {
            throw new IllegalArgumentException("invalid snapshot totals");
        }
    }
    public UUID getSnapshot() { return snapshot; }
    public long getRevision() { return revision; }
    public int getTotal() { return total; }
    public int getPages() { return pages; }
    public MountId getSelected() { return selected; }
    public boolean isDetailed() { return detailed; }
    public CollectionHeader withDetailed(boolean enabled) {
        CollectionHeader copy = new CollectionHeader(snapshot, revision, total, pages, selected);
        copy.detailed = enabled;
        return copy;
    }
    @Override public void toBytes(ByteBuf out) {
        out.writeByte(CollectionPage.VERSION);
        out.writeLong(snapshot.getMostSignificantBits());
        out.writeLong(snapshot.getLeastSignificantBits());
        out.writeLong(revision);
        out.writeInt(total);
        out.writeInt(pages);
        out.writeByte((selected != null ? 1 : 0) | (detailed ? 2 : 0));
        if (selected != null) {
            out.writeLong(selected.asUuid().getMostSignificantBits());
            out.writeLong(selected.asUuid().getLeastSignificantBits());
        }
    }
    @Override public void fromBytes(ByteBuf in) {
        int bytes = in.readableBytes();
        if ((bytes != 34 && bytes != 50) || in.readUnsignedByte() != CollectionPage.VERSION) {
            throw new IllegalArgumentException("invalid collection header");
        }
        UUID id = new UUID(in.readLong(), in.readLong());
        long rev = in.readLong();
        int count = in.readInt();
        int pageCount = in.readInt();
        int flags = in.readUnsignedByte();
        int hasSelected = flags & 1;
        if ((flags & ~3) != 0 || bytes != 34 + 16 * hasSelected) {
            throw new IllegalArgumentException("invalid selection flag");
        }
        MountId selection = hasSelected == 0 ? null : MountId.fromUuid(new UUID(in.readLong(), in.readLong()));
        validate(rev, count, pageCount);
        if (count == 0 && selection != null) { throw new IllegalArgumentException("empty selection"); }
        snapshot = id; revision = rev; total = count; pages = pageCount; selected = selection;
        detailed = (flags & 2) != 0;
    }
}
