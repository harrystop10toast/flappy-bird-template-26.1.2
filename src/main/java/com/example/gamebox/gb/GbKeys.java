package com.example.gamebox.gb;

import org.lwjgl.glfw.GLFW;

/**
 * Maps GLFW key codes to Game Boy joypad buttons (see {@link GbJoypad}). The
 * fixed default control scheme offered while the input overlay is focused:
 *
 * <ul>
 *   <li>Arrow keys / WASD — D-pad</li>
 *   <li>X / J — A</li>
 *   <li>Z / K — B</li>
 *   <li>Enter — Start</li>
 *   <li>Right Shift / Space — Select</li>
 * </ul>
 *
 * <p>Returns {@code -1} if a key is not mapped to any Game Boy button.
 */
public final class GbKeys {

    private GbKeys() {
    }

    public static int fromGlfw(int glfwKey) {
        switch (glfwKey) {
            case GLFW.GLFW_KEY_UP:
            case GLFW.GLFW_KEY_W:
                return GbJoypad.BUTTON_UP;
            case GLFW.GLFW_KEY_DOWN:
            case GLFW.GLFW_KEY_S:
                return GbJoypad.BUTTON_DOWN;
            case GLFW.GLFW_KEY_LEFT:
            case GLFW.GLFW_KEY_A:
                return GbJoypad.BUTTON_LEFT;
            case GLFW.GLFW_KEY_RIGHT:
            case GLFW.GLFW_KEY_D:
                return GbJoypad.BUTTON_RIGHT;
            case GLFW.GLFW_KEY_X:
            case GLFW.GLFW_KEY_J:
                return GbJoypad.BUTTON_A;
            case GLFW.GLFW_KEY_Z:
                return GbJoypad.BUTTON_B;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                return GbJoypad.BUTTON_START;
            case GLFW.GLFW_KEY_RIGHT_SHIFT:
            case GLFW.GLFW_KEY_LEFT_SHIFT:
            case GLFW.GLFW_KEY_SPACE:
                return GbJoypad.BUTTON_SELECT;
            default:
                return -1;
        }
    }
}
