package com.example.gamebox.nes;

import org.lwjgl.glfw.GLFW;

/**
 * Maps GLFW key codes to NES controller buttons (see {@link Controller}). This is
 * the fixed, default NES control scheme the game offers while its input overlay
 * is focused:
 *
 * <ul>
 *   <li>Arrow keys / WASD — D-pad</li>
 *   <li>X / J — A (jump)</li>
 *   <li>Z / K — B (run / fireball)</li>
 *   <li>Enter — Start</li>
 *   <li>Right Shift / Space — Select</li>
 * </ul>
 *
 * <p>Returns {@code -1} if a key is not mapped to any NES button.
 */
public final class NesKeys {

    private NesKeys() {
    }

    public static int fromGlfw(int glfwKey) {
        switch (glfwKey) {
            case GLFW.GLFW_KEY_UP:
            case GLFW.GLFW_KEY_W:
                return Controller.BUTTON_UP;
            case GLFW.GLFW_KEY_DOWN:
            case GLFW.GLFW_KEY_S:
                return Controller.BUTTON_DOWN;
            case GLFW.GLFW_KEY_LEFT:
            case GLFW.GLFW_KEY_A:
                return Controller.BUTTON_LEFT;
            case GLFW.GLFW_KEY_RIGHT:
            case GLFW.GLFW_KEY_D:
                return Controller.BUTTON_RIGHT;
            case GLFW.GLFW_KEY_X:
            case GLFW.GLFW_KEY_J:
                return Controller.BUTTON_A;   // A = jump
            case GLFW.GLFW_KEY_Z:
                return Controller.BUTTON_B;   // B = run / fireball
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                return Controller.BUTTON_START;
            case GLFW.GLFW_KEY_RIGHT_SHIFT:
            case GLFW.GLFW_KEY_LEFT_SHIFT:
            case GLFW.GLFW_KEY_SPACE:
                return Controller.BUTTON_SELECT;
            default:
                return -1;
        }
    }
}
