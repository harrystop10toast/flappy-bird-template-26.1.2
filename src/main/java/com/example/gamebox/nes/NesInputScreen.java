package com.example.gamebox.nes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A transparent, full-screen {@link Screen} that captures keyboard input while
 * Super Mario Bros. is being played and forwards it to the {@link NesEngine}'s
 * controller — the same input technique DOOM uses, minus the mouse (the NES has
 * no mouse-look).
 *
 * <p>It draws nothing and suppresses the default screen dim/blur so the NES frame
 * (rendered into the chat box by {@link NesEngine}) and the world stay fully
 * visible. Pressing <b>ESC</b> closes the overlay (releasing input) without
 * stopping the game; re-grab with the shared focus key (<b>K</b>) or
 * {@code /game mario}.
 */
public final class NesInputScreen extends Screen {

    private final NesEngine engine;

    public NesInputScreen(NesEngine engine) {
        super(Component.literal("Super Mario Bros."));
        this.engine = engine;
    }

    @Override
    protected void init() {
        super.init();
        // Release any buttons that may have been latched from a prior overlay.
        engine.releaseAllButtons();
    }

    @Override
    public void onClose() {
        // Dropping focus must not leave buttons stuck down.
        engine.releaseAllButtons();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // We handle ESC ourselves so it releases input rather than being swallowed.
        return false;
    }

    // ─── Keyboard ─────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyEvent event) {
        int glfw = event.key();
        if (glfw == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        int button = NesKeys.fromGlfw(glfw);
        if (button >= 0) {
            engine.setButton(button, true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int button = NesKeys.fromGlfw(event.key());
        if (button >= 0) {
            engine.setButton(button, false);
            return true;
        }
        return super.keyReleased(event);
    }

    // ─── Render: fully transparent, no dim/blur ───────────────────────────────

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Draw nothing: suppress the default world dim/blur so the chat-rendered
        // NES frame and the world stay fully visible.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Empty: this overlay exists only to capture input. The frame is drawn
        // into the chat box by NesEngine (like DOOM / Flappy Bird).
    }
}
