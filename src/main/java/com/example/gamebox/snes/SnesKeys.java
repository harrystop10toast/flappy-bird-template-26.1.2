package com.example.gamebox.snes;

import org.lwjgl.glfw.GLFW;

/**
 * Maps GLFW key codes to SNES controller buttons (libretro joypad ids, see
 * {@link SnesNative}). The fixed default control scheme while the input overlay
 * is focused:
 *
 * <ul>
 *   <li>Arrow keys / WASD — D-pad</li>
 *   <li>X — A, Z — B, S — Y, A — X (SNES face buttons)</li>
 *   <li>Q — L, E — R (shoulder buttons)</li>
 *   <li>Enter — Start, Right Shift / Space — Select</li>
 * </ul>
 *
 * <p>Returns {@code -1} if a key is not mapped. Note: WASD doubles as the D-pad,
 * so the SNES face buttons use X/Z/S/A to avoid clashing on the common keys.
 */
public final class SnesKeys {

    private SnesKeys() {
    }

    public static int fromGlfw(int glfwKey) {
        switch (glfwKey) {
            // D-pad.
            case GLFW.GLFW_KEY_UP:    return SnesNative.JOYPAD_UP;
            case GLFW.GLFW_KEY_DOWN:  return SnesNative.JOYPAD_DOWN;
            case GLFW.GLFW_KEY_LEFT:  return SnesNative.JOYPAD_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return SnesNative.JOYPAD_RIGHT;
            case GLFW.GLFW_KEY_W:     return SnesNative.JOYPAD_UP;
            case GLFW.GLFW_KEY_D:     return SnesNative.JOYPAD_RIGHT;

            // Face buttons (SNES diamond): A right, B bottom, X top, Y left.
            case GLFW.GLFW_KEY_X:     return SnesNative.JOYPAD_A;
            case GLFW.GLFW_KEY_Z:     return SnesNative.JOYPAD_B;
            case GLFW.GLFW_KEY_A:     return SnesNative.JOYPAD_X;
            case GLFW.GLFW_KEY_S:     return SnesNative.JOYPAD_Y;

            // Shoulders.
            case GLFW.GLFW_KEY_Q:     return SnesNative.JOYPAD_L;
            case GLFW.GLFW_KEY_E:     return SnesNative.JOYPAD_R;

            // Start / Select.
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                return SnesNative.JOYPAD_START;
            case GLFW.GLFW_KEY_RIGHT_SHIFT:
            case GLFW.GLFW_KEY_LEFT_SHIFT:
            case GLFW.GLFW_KEY_SPACE:
                return SnesNative.JOYPAD_SELECT;

            default:
                return -1;
        }
    }
}
