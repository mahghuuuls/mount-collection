package com.mahghuuuls.mountcollection.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.opengl.GL11;

/** Rendering is isolated from construction and is always paired with GUI state cleanup. */
public final class PreviewRenderer {
    private PreviewRenderer() { }
    interface ScissorState {
        void rectangle(int x, int y, int width, int height);
        void enabled(boolean enabled);
    }
    private static final ScissorState GL_SCISSOR = new ScissorState() {
        public void rectangle(int x, int y, int width, int height) { GL11.glScissor(x, y, width, height); }
        public void enabled(boolean enabled) {
            if (enabled) { GL11.glEnable(GL11.GL_SCISSOR_TEST); }
            else { GL11.glDisable(GL11.GL_SCISSOR_TEST); }
        }
    };
    static boolean fallback(PreviewSession session, Throwable failure) {
        // Minecraft wraps renderer Throwables in ReportedException; classify the complete cause chain.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof VirtualMachineError) { throw (VirtualMachineError) cause; }
            if (cause instanceof ThreadDeath) { throw (ThreadDeath) cause; }
        }
        session.failed(); return false;
    }
    static void restoreScissor(ScissorState state, boolean enabled, java.nio.IntBuffer rectangle) {
        state.rectangle(rectangle.get(0), rectangle.get(1), rectangle.get(2), rectangle.get(3));
        state.enabled(enabled);
    }
    public static boolean draw(PreviewSession session, EntityLivingBase entity, int left, int top, int width, int height) {
        if (entity == null || width < 24 || height < 24) { return false; }
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager manager = mc.getRenderManager();
        float oldView = manager.playerViewY;
        boolean shadow = manager.isRenderShadow();
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int depth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        int projectionDepth = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
        java.nio.FloatBuffer modelMatrix = org.lwjgl.BufferUtils.createFloatBuffer(16);
        java.nio.FloatBuffer projectionMatrix = org.lwjgl.BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projectionMatrix);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        java.nio.IntBuffer box = org.lwjgl.BufferUtils.createIntBuffer(4);
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, box);
        try {
            int factor = new ScaledResolution(mc).getScaleFactor();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            int sx = left * factor, sy = mc.displayHeight - (top + height) * factor;
            int ex = sx + width * factor, ey = sy + height * factor;
            if (scissor) { sx = Math.max(sx, box.get(0)); sy = Math.max(sy, box.get(1));
                ex = Math.min(ex, box.get(0) + box.get(2)); ey = Math.min(ey, box.get(1) + box.get(3)); }
            GL11.glScissor(sx, sy, Math.max(0, ex - sx), Math.max(0, ey - sy));
            GlStateManager.matrixMode(GL11.GL_MODELVIEW); GlStateManager.pushMatrix();
            float scale = Math.min((width - 12) / (entity.width * 1.8F), (height - 12) / (entity.height * 1.3F));
            GlStateManager.translate(left + width / 2F, top + height - 6, 100);
            GlStateManager.scale(-scale, scale, scale); GlStateManager.rotate(180, 0, 0, 1);
            GlStateManager.rotate(session.getYaw(), 0, 1, 0);
            GlStateManager.enableDepth(); GlStateManager.enableColorMaterial();
            RenderHelper.enableStandardItemLighting();
            manager.setPlayerViewY(180); manager.setRenderShadow(false);
            manager.renderEntity(entity, 0, 0, 0, 0, 0, false);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return fallback(session, failure);
        } finally {
            manager.setPlayerViewY(oldView); manager.setRenderShadow(shadow);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > depth) { GlStateManager.popMatrix(); }
            GL11.glLoadMatrix(modelMatrix);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            while (GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH) > projectionDepth) { GlStateManager.popMatrix(); }
            GL11.glLoadMatrix(projectionMatrix);
            GlStateManager.matrixMode(mode);
            // Establish the GUI baseline through cached setters, not glPopAttrib (which bypasses their caches).
            RenderHelper.disableStandardItemLighting(); GlStateManager.disableRescaleNormal();
            GlStateManager.disableColorMaterial(); GlStateManager.disableDepth(); GlStateManager.depthMask(true);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit); GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit); GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha(); GlStateManager.enableBlend(); GlStateManager.disableCull();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); GlStateManager.color(1, 1, 1, 1);
            restoreScissor(GL_SCISSOR, scissor, box);
        }
    }
}
