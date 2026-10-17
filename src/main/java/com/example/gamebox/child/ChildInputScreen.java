package com.example.gamebox.child;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent input overlay for the second (child) Minecraft, using the same
 * K-grab / Esc-release model as the other GameBox games. It has <b>two modes</b>,
 * chosen automatically from whether the child currently has a GUI screen open:
 *
 * <ul>
 *   <li><b>Gameplay mode</b> (in-world): arrow keys drive an artificial mouse
 *       (camera look via {@code LocalPlayer.turn}); left/right click =
 *       attack/use. WASD etc. forward as keys.</li>
 *   <li><b>Cursor mode</b> (a menu is open — title, inventory, settings): a
 *       visible cursor is drawn on the chat view; arrow keys <em>move the
 *       cursor</em>, and a click sends a positioned mouse click to the child at
 *       the cursor so you can press buttons / move items.</li>
 * </ul>
 *
 * <p><b>Esc</b> releases input; re-grab with the shared focus key (<b>K</b>).
 */
public final class ChildInputScreen extends Screen {

    private static final double LOOK_STEP = 8.0; // degrees of look per arrow step
    private static final int CURSOR_STEP_X = 2;  // cells moved per left/right press
    private static final int CURSOR_STEP_Y = 1;  // cells per up/down (vertical is coarser)

    private final ChildClientController controller;

    public ChildInputScreen(ChildClientController controller) {
        super(Component.literal("Minecraft #2"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        super.init();
        // Centre the cursor on open so it's visible immediately in cursor mode.
        if (controller.cursorCol() < 0 || controller.cursorRow() < 0) {
            controller.setCursor(controller.frameCols() / 2, controller.frameRows() / 2);
        }
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
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(null);
            return true;
        }

        boolean cursorMode = controller.isChildScreenOpen();

        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursorMode) {
                    moveCursor(-CURSOR_STEP_X, 0);
                } else {
                    controller.forwardLook(-LOOK_STEP, 0);
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursorMode) {
                    moveCursor(CURSOR_STEP_X, 0);
                } else {
                    controller.forwardLook(LOOK_STEP, 0);
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (cursorMode) {
                    moveCursor(0, -CURSOR_STEP_Y);
                } else {
                    controller.forwardLook(0, -LOOK_STEP);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (cursorMode) {
                    moveCursor(0, CURSOR_STEP_Y);
                } else {
                    controller.forwardLook(0, LOOK_STEP);
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_SPACE -> {
                // In cursor mode, Enter/Space clicks the cursor (handy for buttons).
                if (cursorMode) {
                    clickCursorLeft();
                    return true;
                }
                controller.forwardKey(key, event.scancode(), GLFW.GLFW_PRESS, event.modifiers());
                return true;
            }
            default -> {
                controller.forwardKey(key, event.scancode(), GLFW.GLFW_PRESS, event.modifiers());
                return true;
            }
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN
                || key == GLFW.GLFW_KEY_ESCAPE) {
            return true;
        }
        if (controller.isChildScreenOpen()
                && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE)) {
            return true; // consumed as a cursor click on press
        }
        controller.forwardKey(key, event.scancode(), GLFW.GLFW_RELEASE, event.modifiers());
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (controller.isChildScreenOpen()) {
            // Positioned click at the cursor for menus/GUIs.
            controller.forwardCursorCell(controller.cursorCol(), controller.cursorRow(),
                    event.button(), GLFW.GLFW_PRESS);
        } else {
            controller.forwardMouseButton(event.button(), GLFW.GLFW_PRESS, event.modifiers());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (controller.isChildScreenOpen()) {
            controller.forwardCursorCell(controller.cursorCol(), controller.cursorRow(),
                    event.button(), GLFW.GLFW_RELEASE);
        } else {
            controller.forwardMouseButton(event.button(), GLFW.GLFW_RELEASE, event.modifiers());
        }
        return true;
    }

    private void moveCursor(int dCol, int dRow) {
        int col = controller.cursorCol();
        int row = controller.cursorRow();
        if (col < 0) {
            col = controller.frameCols() / 2;
        }
        if (row < 0) {
            row = controller.frameRows() / 2;
        }
        controller.setCursor(col + dCol, row + dRow);
        // Also move the child's actual cursor so it hovers the right widget.
        controller.forwardCursorCell(controller.cursorCol(), controller.cursorRow(), -1, 0);
    }

    private void clickCursorLeft() {
        controller.forwardCursorCell(controller.cursorCol(), controller.cursorRow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS);
        controller.forwardCursorCell(controller.cursorCol(), controller.cursorRow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    }
}
