package com.example.gamebox.graphing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent, full-screen input overlay for the graphing calculator — the same
 * input technique DOOM and Super Mario use. While focused:
 *
 * <ul>
 *   <li><b>Arrow keys</b> — pan the view (←/→ left-right, ↑/↓ up-down)</li>
 *   <li><b>O</b> — zoom in</li>
 *   <li><b>P</b> — zoom out</li>
 *   <li><b>Esc</b> — release input (the calculator stays on); re-grab with the
 *       shared focus key (<b>K</b>)</li>
 * </ul>
 *
 * <p>Draws nothing and suppresses the world dim/blur so the chat-rendered graph
 * and the world stay fully visible.
 */
public final class GraphingInputScreen extends Screen {

    private static final double PAN_FRACTION = 0.12; // 12% of the view per press

    private final GraphingController controller;

    public GraphingInputScreen(GraphingController controller) {
        super(Component.literal("Graphing"));
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
        int key = event.key();
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE:
                onClose();
                Minecraft.getInstance().setScreen(null);
                return true;
            case GLFW.GLFW_KEY_LEFT:
                controller.pan(-PAN_FRACTION, 0);
                return true;
            case GLFW.GLFW_KEY_RIGHT:
                controller.pan(PAN_FRACTION, 0);
                return true;
            case GLFW.GLFW_KEY_UP:
                controller.pan(0, PAN_FRACTION); // move view up (world +y)
                return true;
            case GLFW.GLFW_KEY_DOWN:
                controller.pan(0, -PAN_FRACTION);
                return true;
            case GLFW.GLFW_KEY_O:
                controller.zoomIn();
                return true;
            case GLFW.GLFW_KEY_P:
                controller.zoomOut();
                return true;
            default:
                return super.keyPressed(event);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — keep the world + chat-rendered graph fully visible.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — this overlay only captures input.
    }
}
