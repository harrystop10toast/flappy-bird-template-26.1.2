package com.example.gamebox.doom;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * A debug screen that renders DOOM's full, uncompressed 320x200 framebuffer as a
 * real on-screen image (scaled up with letterboxing), outside the chat box. The
 * keyboard still drives DOOM here, so this doubles as a "proper" way to play.
 *
 * <p>Opened from the GameBox GUI. Press ESC to close (DOOM keeps running).
 */
public final class DoomDebugScreen extends Screen {

    private static final Identifier TEX_ID = Identifier.fromNamespaceAndPath("gamebox", "doom_debug");

    private final DoomController doom;
    private DynamicTexture texture;
    private boolean registered;

    public DoomDebugScreen(DoomController doom) {
        super(Component.literal("DOOM (full resolution)"));
        this.doom = doom;
    }

    @Override
    protected void init() {
        super.init();
        if (texture == null) {
            texture = new DynamicTexture(() -> "gamebox-doom-debug",
                    DoomEngine.resX(), DoomEngine.resY(), true);
        }
        if (!registered) {
            Minecraft.getInstance().getTextureManager().register(TEX_ID, texture);
            registered = true;
        }
    }

    @Override
    public void onClose() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(TEX_ID);
            registered = false;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // ESC closes the debug view; DOOM keeps running.
    }

    // Forward keyboard to DOOM so it is playable from the debug view too.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(event);
        }
        int doomKey = DoomKeys.fromGlfw(event.key());
        DoomEngine e = doom.engine();
        if (doomKey >= 0 && e != null) {
            e.pushKey(true, doomKey);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int doomKey = DoomKeys.fromGlfw(event.key());
        DoomEngine e = doom.engine();
        if (doomKey >= 0 && e != null) {
            e.pushKey(false, doomKey);
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Dim background so the DOOM image reads clearly.
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        DoomEngine e = doom.engine();
        int[] frame = (e != null) ? e.latestArgb() : null;
        int rw = DoomEngine.resX();
        int rh = DoomEngine.resY();

        if (frame != null && texture != null) {
            // Copy ARGB (0x00RRGGBB from doomgeneric) into the texture as ABGR with
            // opaque alpha, which is what NativeImage.setPixelABGR expects.
            NativeImage img = texture.getPixels();
            if (img != null) {
                for (int y = 0; y < rh; y++) {
                    for (int x = 0; x < rw; x++) {
                        int c = frame[y * rw + x];
                        int r = (c >> 16) & 0xFF;
                        int g = (c >> 8) & 0xFF;
                        int b = c & 0xFF;
                        int abgr = 0xFF000000 | (b << 16) | (g << 8) | r;
                        img.setPixelABGR(x, y, abgr);
                    }
                }
                texture.upload();
            }

            // Integer-scale with letterbox to preserve the 320x200 aspect ratio.
            int scale = Math.max(1, Math.min(this.width / rw, this.height / rh));
            int dw = rw * scale;
            int dh = rh * scale;
            int dx = (this.width - dw) / 2;
            int dy = (this.height - dh) / 2;

            graphics.pose().pushMatrix();
            graphics.pose().translate(dx, dy);
            graphics.pose().scale(scale, scale);
            graphics.blit(RenderPipelines.GUI_TEXTURED, TEX_ID, 0, 0, 0.0f, 0.0f, rw, rh, rw, rh);
            graphics.pose().popMatrix();
        } else {
            graphics.centeredText(Minecraft.getInstance().font,
                    Component.literal("§7DOOM is not running. Use §e/doom play §7first."),
                    this.width / 2, this.height / 2, 0xFFFFFFFF);
        }

        graphics.text(Minecraft.getInstance().font,
                Component.literal("§aDOOM full-res debug §8— ESC to close · keyboard drives DOOM"),
                6, 6, 0xFFFFFFFF, true);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
