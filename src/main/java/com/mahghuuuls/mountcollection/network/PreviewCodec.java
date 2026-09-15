package com.mahghuuuls.mountcollection.network;

import com.mahghuuuls.mountcollection.api.MountPreview;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import net.minecraft.util.ResourceLocation;

/** A length-delimited optional preview can fail without discarding a valid collection row. */
final class PreviewCodec {
    private static final int MAX_BYTES = 8 + 2 * MountPreview.MAX_ID_BYTES + MountPreview.MAX_PAYLOAD_BYTES;
    private PreviewCodec() { }
    static int size(MountPreview preview) {
        return 2 + (preview == null ? 0 : 8 + preview.getProviderId().toString().length()
                + preview.getEntityType().toString().length() + preview.getPayloadSize());
    }
    static void write(ByteBuf out, MountPreview preview) {
        out.writeShort(size(preview) - 2);
        if (preview == null) { return; }
        writeId(out, preview.getProviderId()); writeId(out, preview.getEntityType());
        out.writeInt(preview.getVersion()); out.writeBytes(preview.copyPayload());
    }
    private static void writeId(ByteBuf out, ResourceLocation id) {
        byte[] bytes = id.toString().getBytes(StandardCharsets.US_ASCII);
        out.writeShort(bytes.length); out.writeBytes(bytes);
    }
    static MountPreview read(ByteBuf in) {
        if (in.readableBytes() < 2) { throw new IllegalArgumentException("truncated preview envelope"); }
        int length = in.readUnsignedShort();
        if (length > in.readableBytes()) { throw new IllegalArgumentException("truncated preview body"); }
        ByteBuf body = in.readSlice(length);
        if (length == 0 || length > MAX_BYTES) { return null; }
        try {
            ResourceLocation provider = readId(body), entity = readId(body);
            int version = body.readInt();
            if (body.readableBytes() > MountPreview.MAX_PAYLOAD_BYTES) { return null; }
            byte[] bytes = new byte[body.readableBytes()]; body.readBytes(bytes);
            return new MountPreview(provider, entity, version, bytes);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) { return null; }
    }
    private static ResourceLocation readId(ByteBuf body) {
        int length = body.readUnsignedShort();
        if (length == 0 || length > MountPreview.MAX_ID_BYTES || length > body.readableBytes()) {
            throw new IllegalArgumentException("invalid preview identifier length");
        }
        byte[] bytes = new byte[length]; body.readBytes(bytes);
        String id = new String(bytes, StandardCharsets.US_ASCII);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) { throw new IllegalArgumentException("invalid preview identifier"); }
        return new ResourceLocation(id);
    }
}
