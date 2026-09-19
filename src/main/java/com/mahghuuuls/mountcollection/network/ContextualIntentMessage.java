package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class ContextualIntentMessage implements IMessage {
    private java.util.UUID session;
    private long sequence;
    private boolean automaticRiding;

    public ContextualIntentMessage() { }
    public ContextualIntentMessage(java.util.UUID session, long sequence, boolean automaticRiding) {
        if (sequence <= 0) { throw new IllegalArgumentException("invalid sequence"); }
        this.session = java.util.Objects.requireNonNull(session);
        this.sequence = sequence;
        this.automaticRiding = automaticRiding;
    }
    public java.util.UUID getSession() { return session; }
    public long getSequence() { return sequence; }
    public boolean isAutomaticRiding() { return automaticRiding; }

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 29 || buffer.readInt() != ExperienceProtocol.REVISION) {
            throw new IllegalArgumentException("incompatible contextual intent");
        }
        session = new java.util.UUID(buffer.readLong(), buffer.readLong());
        sequence = buffer.readLong();
        int riding = buffer.readUnsignedByte();
        if (sequence <= 0 || riding > 1) { throw new IllegalArgumentException("invalid contextual intent"); }
        automaticRiding = riding == 1;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(ExperienceProtocol.REVISION);
        buffer.writeLong(session.getMostSignificantBits());
        buffer.writeLong(session.getLeastSignificantBits());
        buffer.writeLong(sequence);
        buffer.writeByte(automaticRiding ? 1 : 0);
    }
}
