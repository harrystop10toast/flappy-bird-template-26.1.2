package com.example.gamebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent, full-screen input overlay for Flappy Bird — the same input
 * technique DOOM, Super Mario, the graphing calculator and the trig visualizer
 * use. While focused it <b>captures all keyboard/mouse input</b> so the flap key
 * never also triggers a Minecraft action (e.g. Space jumping the player); only
 * the flap and release keys do anything, and every other key is swallowed.
 *
 * <ul>
 *   <li><b>Space / W / ↑ / left-click</b> — flap;</li>
 *   <li><b>Esc</b> — release input (the game keeps running); re-grab with the
 *       shared focus key (<b>K</b>).</li>
 * </ul>
 *
 * <p>Draws nothing and suppresses the world dim/blur so the chat-rendered game
 * and the world stay fully visible.
 */
public final class FlappyInputScreen extends Screen {

    private final FlappyBirdGame game;

    public FlappyInputScreen(FlappyBirdGame game) {
        super(Component.literal("Flappy Bird"));
        this.game = game;
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
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (isFlapKey(key)) {
            game.onFlapPressed();
            return true;
        }
        // Swallow every other key so nothing leaks to Minecraft (no mistriggers).
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return true; // swallow releases too
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Left-click flaps; all clicks are captured so they never reach the world.
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            game.onFlapPressed();
        }
        return true;
    }

    private static boolean isFlapKey(int glfw) {
        return glfw == GLFW.GLFW_KEY_SPACE
                || glfw == GLFW.GLFW_KEY_W
                || glfw == GLFW.GLFW_KEY_UP;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — keep the world + chat-rendered game fully visible.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Nothing — this overlay only captures input.
    }
}
