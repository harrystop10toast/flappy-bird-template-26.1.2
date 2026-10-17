package com.example.gamebox.trig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent, full-screen input overlay for the trig triangle visualizer — the
 * same input technique DOOM, Super Mario and the graphing calculator use. The
 * triangle auto-fits the canvas, so there is no pan/zoom to drive; the overlay
 * exists mainly so that pressing <b>Esc</b> releases input (leaving the
 * visualizer on and the chat readable), and the shared focus key (<b>K</b>)
 * re-grabs it. The triangle itself is changed with the {@code /trig} command.
 *
 * <p>Draws nothing and suppresses the world dim/blur so the chat-rendered
 * triangle and the world stay fully visible.
 */
public final class TrigInputScreen extends Screen {

    @SuppressWarnings("unused")
    private final TrigController controller;

    public TrigInputScreen(TrigController controller) {
        super(Component.literal("Trig"));
        this.controller = controller;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // we handle ESC ourselves (release input)
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — keep the world + chat-rendered triangle fully visible.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — this overlay only captures input.
    }
}
