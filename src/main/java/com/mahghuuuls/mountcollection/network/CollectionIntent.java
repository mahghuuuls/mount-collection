package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.persistence.MountId;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class CollectionIntent implements IMessage {
    public enum Action { OPEN, SELECT, CLOSE, RENAME, ABANDON }
    private Action action;
    private UUID session;
    private long revision;
    private MountId target;
    private String name;
    public CollectionIntent() { }
    public CollectionIntent(Action action, UUID session, long revision, MountId target) {
        this(action, session, revision, target, null);
    }
    public CollectionIntent(Action action, UUID session, long revision, MountId target, String name) {
        this.action = Objects.requireNonNull(action, "action");
        this.session = Objects.requireNonNull(session, "session");
        boolean mutation = action == Action.SELECT || action == Action.RENAME || action == Action.ABANDON;
        if (revision < 0 || mutation != (target != null) || (!mutation && revision != 0)
                || (action == Action.RENAME) != (name != null)) {
            throw new IllegalArgumentException("invalid collection intent");
        }
        this.revision = revision; this.target = target;
        if (name != null && (name.length() > 256 || CollectionPage.encodeText(name).length > 256)) {
            throw new IllegalArgumentException("oversized rename input");
        }
        this.name = name;
    }
    public Action getAction() { return action; }
    public UUID getSession() { return session; }
    public long getRevision() { return revision; }
    public MountId getTarget() { return target; }
    public String getName() { return name; }
    @Override public void toBytes(ByteBuf out) {
        out.writeByte(CollectionPage.VERSION); out.writeByte(action.ordinal());
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
        out.writeLong(revision);
        if (target != null) {
            out.writeLong(target.asUuid().getMostSignificantBits()); out.writeLong(target.asUuid().getLeastSignificantBits());
        }
        if (name != null) { byte[] text = CollectionPage.encodeText(name); out.writeShort(text.length); out.writeBytes(text); }
    }
    @Override public void fromBytes(ByteBuf in) {
        int size = in.readableBytes();
        if (size < 26 || size > 300 || in.readUnsignedByte() != CollectionPage.VERSION) {
            throw new IllegalArgumentException("invalid collection intent size/version");
        }
        int value = in.readUnsignedByte();
        if (value >= Action.values().length || (value == 3 ? size < 44 : size != (value == 1 || value == 4 ? 42 : 26))) {
            throw new IllegalArgumentException("invalid collection action");
        }
        Action decodedAction = Action.values()[value];
        UUID decodedSession = new UUID(in.readLong(), in.readLong());
        long decodedRevision = in.readLong();
        MountId decodedTarget = value == 1 || value == 3 || value == 4 ? MountId.fromUuid(new UUID(in.readLong(), in.readLong())) : null;
        String decodedName = null;
        if (value == 3) {
            int length = in.readUnsignedShort();
            if (length > 256 || length != in.readableBytes()) { throw new IllegalArgumentException("invalid rename length"); }
            byte[] text = new byte[length]; in.readBytes(text);
            try {
                decodedName = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(text)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("invalid rename Unicode", invalid); }
        }
        CollectionIntent checked = new CollectionIntent(decodedAction, decodedSession, decodedRevision, decodedTarget, decodedName);
        action = checked.action; session = checked.session; revision = checked.revision; target = checked.target;
        name = checked.name;
    }
}
