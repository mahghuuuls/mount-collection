package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Absolute native pose, deliberately not a replacement for vanilla movement baselines. */
public final class RelocationMessage implements IMessage {
    public static final int BYTES = 109;
    private UUID session, entity;
    private long sequence;
    private int dimension, entityId;
    private double x,y,z,vx,vy,vz;
    private float yaw,pitch;
    private boolean grounded;
    public RelocationMessage() { }
    public RelocationMessage(UUID session, long sequence, Entity e) {
        this.session=session; this.sequence=sequence; entity=e.getUniqueID(); entityId=e.getEntityId();
        dimension=e.dimension; x=e.posX; y=e.posY; z=e.posZ; yaw=e.rotationYaw; pitch=e.rotationPitch;
        vx=e.motionX; vy=e.motionY; vz=e.motionZ; grounded=e.onGround; validate();
    }
    public static boolean legalPosition(double x,double y,double z) {
        return bounded(x,30000000D) && bounded(y,30000000D) && bounded(z,30000000D);
    }
    private static boolean bounded(double n,double limit) { return Double.isFinite(n) && Math.abs(n)<=limit; }
    private void validate() {
        if(session==null || entity==null || sequence<=0 || !legalPosition(x,y,z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90F
                || !bounded(vx,30000000D) || !bounded(vy,30000000D) || !bounded(vz,30000000D)) {
            throw new IllegalArgumentException("invalid relocation pose");
        }
    }
    @Override public void fromBytes(ByteBuf b) {
        if(b.readableBytes()!=BYTES || b.readInt()!=ExperienceProtocol.REVISION) {
            throw new IllegalArgumentException("incompatible relocation protocol");
        }
        session=new UUID(b.readLong(),b.readLong()); sequence=b.readLong(); dimension=b.readInt(); entityId=b.readInt();
        entity=new UUID(b.readLong(),b.readLong()); x=b.readDouble(); y=b.readDouble(); z=b.readDouble();
        yaw=b.readFloat(); pitch=b.readFloat(); vx=b.readDouble(); vy=b.readDouble(); vz=b.readDouble();
        int flag=b.readUnsignedByte(); if(flag>1) { throw new IllegalArgumentException("invalid grounded flag"); }
        grounded=flag==1; validate();
    }
    @Override public void toBytes(ByteBuf b) {
        validate(); b.writeInt(ExperienceProtocol.REVISION); b.writeLong(session.getMostSignificantBits());
        b.writeLong(session.getLeastSignificantBits()); b.writeLong(sequence); b.writeInt(dimension); b.writeInt(entityId);
        b.writeLong(entity.getMostSignificantBits()); b.writeLong(entity.getLeastSignificantBits());
        b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yaw); b.writeFloat(pitch);
        b.writeDouble(vx); b.writeDouble(vy); b.writeDouble(vz); b.writeByte(grounded?1:0);
    }
    public UUID session() { return session; }
    public long sequence() { return sequence; }
    public int dimension() { return dimension; }
    public int entityId() { return entityId; }
    public UUID entity() { return entity; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public double vz() { return vz; }
    public boolean grounded() { return grounded; }
}
