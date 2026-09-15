package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class CollectionReply implements IMessage {
    public enum Result { SELECTED, UNCHANGED, STALE, NOT_OWNED, READ_ONLY, SAVE_FAILED, RATE_LIMITED, UNAVAILABLE,
        RENAMED, NAME_UNCHANGED, INVALID_NAME, BUSY, ABANDONED, ABANDONMENT_PENDING }
    private UUID session;
    private Result result;
    public CollectionReply() { }
    public CollectionReply(UUID session, Result result) {
        this.session = Objects.requireNonNull(session, "session");
        this.result = Objects.requireNonNull(result, "result");
    }
    public UUID getSession() { return session; }
    public Result getResult() { return result; }
    @Override public void toBytes(ByteBuf out) {
        out.writeByte(CollectionPage.VERSION);
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
        out.writeByte(result.ordinal());
    }
    @Override public void fromBytes(ByteBuf in) {
        if (in.readableBytes() != 18 || in.readUnsignedByte() != CollectionPage.VERSION) {
            throw new IllegalArgumentException("invalid collection reply");
        }
        UUID id = new UUID(in.readLong(), in.readLong());
        int code = in.readUnsignedByte();
        if (code >= Result.values().length) { throw new IllegalArgumentException("unknown collection result"); }
        session = id; result = Result.values()[code];
    }
}
