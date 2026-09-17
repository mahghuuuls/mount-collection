package com.mahghuuuls.mountcollection.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/** Ordinary button input/disabled behavior with a small core-owned icon instead of a label. */
final class CollectionIconButton extends GuiButton {
    private final ResourceLocation icon;
    private final int iconSize;
    CollectionIconButton(int id, int x, int y, String iconName) {
        this(id, x, y, new ResourceLocation("mountcollection", "textures/gui/" + iconName + ".png"), 14);
    }
    CollectionIconButton(int id, int x, int y, ResourceLocation texture, int size) {
        super(id, x, y, 20, 20, "");
        icon = texture;
        iconSize = size;
    }
    boolean contains(int mouseX, int mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
    @Override public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        if (!visible) { return; }
        super.drawButton(minecraft, mouseX, mouseY, partialTicks);
        float shade = enabled ? 1F : 0.4F;
        GlStateManager.color(shade, shade, shade, 1F);
        GlStateManager.enableBlend();
        minecraft.getTextureManager().bindTexture(icon);
        int inset = (20 - iconSize) / 2;
        drawModalRectWithCustomSizedTexture(x + inset, y + inset, 0, 0, iconSize, iconSize, iconSize, iconSize);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }
}
