package com.example.gamebox.snes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A transparent, full-screen {@link Screen} that captures keyboard input while a
 * SNES ROM is being played and forwards it to the {@link SnesEngine}'s joypad —
 * the same input technique DOOM and the NES/Game Boy cores use.
 *
 * <p>It draws nothing and suppresses the default screen dim/blur so the SNES
 * frame (rendered into the chat box by {@link SnesEngine}) and the world stay
 * fully visible. Pressing <b>ESC</b> closes the overlay (releasing input)
 * without stopping the game; re-grab with the shared focus key (<b>K</b>).
 */
public final class SnesInputScreen extends Screen {

    private final SnesEngine engine;

    public SnesInputScreen(SnesEngine engine) {
        super(Component.literal("Super NES"));
        this.engine = engine;
    }

    @Override
    protected void init() {
        super.init();
        engine.releaseAllButtons();
    }

    @Override
    public void onClose() {
        engine.releaseAllButtons();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int glfw = event.key();
        if (glfw == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        int button = SnesKeys.fromGlfw(glfw);
        if (button >= 0) {
            engine.setButton(button, true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int button = SnesKeys.fromGlfw(event.key());
        if (button >= 0) {
            engine.setButton(button, false);
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Draw nothing: suppress the default world dim/blur.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Empty: this overlay exists only to capture input. The frame is drawn
        // into the chat box by SnesEngine.
    }
}
