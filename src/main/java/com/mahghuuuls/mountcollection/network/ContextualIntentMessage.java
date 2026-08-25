package com.mahghuuuls.mountcollection.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

public final class ContextualIntentMessage implements IMessage {

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != 0) {
            throw new IllegalArgumentException("contextual intent payload must be empty");
        }
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        // The authenticated sender and current server state are the complete intent.
    }
}
