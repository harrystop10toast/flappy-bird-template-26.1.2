package com.example.gamebox.doom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A transparent, full-screen {@link Screen} that captures keyboard and mouse
 * input while DOOM is being played and forwards it to the {@link DoomEngine}.
 *
 * <p>It draws nothing and suppresses the default screen dim/blur, so the DOOM
 * frame (rendered into the chat box by {@link DoomEngine}, exactly like Flappy
 * Bird) and the world remain fully visible. It only exists to grab input focus.
 * The cursor is grabbed so raw mouse-motion deltas can drive mouse-look. Pressing
 * ESC closes the overlay (releasing input) without stopping the engine; re-grab
 * with the DOOM focus key or {@code /doom resume}.
 */
public final class DoomInputScreen extends Screen {

    private final DoomEngine engine;
    private double lastMouseX;
    private double lastMouseY;
    private boolean haveLast;
    private int mouseButtons;

    public DoomInputScreen(DoomEngine engine) {
        super(Component.literal("DOOM"));
        this.engine = engine;
    }

    @Override
    protected void init() {
        super.init();
        grabCursor();
        haveLast = false;
    }

    private void grabCursor() {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    private void releaseCursor() {
        Minecraft mc = Minecraft.getInstance();
        long handle = mc.getWindow().handle();
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }

    @Override
    public void onClose() {
        releaseCursor();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // Keep the game world (and our engine) running while this screen is open.
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // We handle ESC ourselves so it closes the overlay (releasing input)
        // rather than being swallowed as a DOOM key.
        return false;
    }

    // ─── Keyboard ───────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyEvent event) {
        int glfw = event.key();
        if (glfw == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        int doomKey = DoomKeys.fromGlfw(glfw);
        if (doomKey >= 0) {
            engine.pushKey(true, doomKey);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int doomKey = DoomKeys.fromGlfw(event.key());
        if (doomKey >= 0) {
            engine.pushKey(false, doomKey);
            return true;
        }
        return super.keyReleased(event);
    }

    // ─── Mouse ───────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        setButton(event.button(), true);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            engine.pushKey(true, DoomKeys.KEY_FIRE);
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            engine.pushKey(true, DoomKeys.KEY_USE);
        }
        engine.pushMouse(0, 0, mouseButtons);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        setButton(event.button(), false);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            engine.pushKey(false, DoomKeys.KEY_FIRE);
        } else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            engine.pushKey(false, DoomKeys.KEY_USE);
        }
        engine.pushMouse(0, 0, mouseButtons);
        return true;
    }

    private void setButton(int button, boolean down) {
        int bit;
        switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT:
                bit = 1;
                break;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
                bit = 2;
                break;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE:
                bit = 4;
                break;
            default:
                return;
        }
        if (down) {
            mouseButtons |= bit;
        } else {
            mouseButtons &= ~bit;
        }
    }

    @Override
    public void mouseMoved(double x, double y) {
        if (!haveLast) {
            lastMouseX = x;
            lastMouseY = y;
            haveLast = true;
            return;
        }
        int dx = (int) Math.round(x - lastMouseX);
        int dy = (int) Math.round(y - lastMouseY);
        lastMouseX = x;
        lastMouseY = y;
        if (dx != 0 || dy != 0) {
            engine.pushMouse(dx, dy, mouseButtons);
        }
    }

    // ─── Render: fully transparent, no dim/blur ──────────────────────────────────

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Draw NOTHING. The default Screen background dims/blurs the world (the
        // "grey shader"); we suppress it so the chat-rendered DOOM frame and the
        // world stay fully visible while this screen only captures input.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Deliberately empty: this overlay exists only to capture input. DOOM is
        // drawn frame-by-frame into the chat box by DoomEngine (like Flappy Bird).
    }
}
