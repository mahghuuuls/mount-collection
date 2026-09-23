package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Server challenge and client acknowledgement; no gameplay accepted before this exchange. */
public class ExperienceProtocol implements IMessage {
    // Revision 1 reserved the preference field but did not implement rider-safe recall.
    public static final int REVISION = 2;
    private UUID session;
    public ExperienceProtocol() { }
    public ExperienceProtocol(UUID session) { this.session = java.util.Objects.requireNonNull(session); }
    public UUID getSession() { return session; }
    @Override public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 20 || buffer.readInt() != REVISION) {
            throw new IllegalArgumentException("incompatible Mount Collection protocol");
        }
        session = new UUID(buffer.readLong(), buffer.readLong());
    }
    @Override public void toBytes(ByteBuf buffer) {
        buffer.writeInt(REVISION);
        buffer.writeLong(session.getMostSignificantBits());
        buffer.writeLong(session.getLeastSignificantBits());
    }
}
