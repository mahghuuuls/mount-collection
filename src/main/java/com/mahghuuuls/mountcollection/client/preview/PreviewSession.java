package com.mahghuuuls.mountcollection.client.preview;

import com.mahghuuuls.mountcollection.api.MountPreview;
import com.mahghuuuls.mountcollection.persistence.MountId;
import java.util.Objects;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

/** One screen owns one detached representation. Failed construction/rendering is not retried each frame. */
public final class PreviewSession {
    private final ClientPreviewRegistry registry;
    private MountId id;
    private MountPreview data;
    private World world;
    private EntityLivingBase entity;
    private float yaw = 25;
    public PreviewSession(ClientPreviewRegistry registry) { this.registry = Objects.requireNonNull(registry, "registry"); }
    public EntityLivingBase prepare(MountId next, MountPreview presentation, World context) {
        if (!Objects.equals(id, next) || !Objects.equals(data, presentation) || world != context) {
            clear(); id = next; data = presentation; world = context;
            entity = registry.create(presentation, context);
        }
        return entity;
    }
    public float getYaw() { return yaw; }
    public void rotate(float delta) { if (Float.isFinite(delta)) { yaw = (yaw + delta) % 360; } }
    public void failed() { entity = null; }
    public void clear() { id = null; data = null; world = null; entity = null; yaw = 25; }
}
