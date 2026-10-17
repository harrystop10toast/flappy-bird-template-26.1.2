package com.example.gamebox.doom;

import org.lwjgl.glfw.GLFW;

/**
 * doomgeneric key codes (from DOOM's {@code doomkeys.h}) and a mapping from GLFW
 * key codes to them.
 *
 * <p>doomgeneric expects the ASCII-ish key codes DOOM uses internally: printable
 * keys map to their lowercase ASCII value, and special keys use the {@code 0x80+}
 * range below.
 */
public final class DoomKeys {

    private DoomKeys() {
    }

    // From doomkeys.h
    public static final int KEY_RIGHTARROW = 0xae;
    public static final int KEY_LEFTARROW = 0xac;
    public static final int KEY_UPARROW = 0xad;
    public static final int KEY_DOWNARROW = 0xaf;
    public static final int KEY_STRAFE_L = 0xa0;
    public static final int KEY_STRAFE_R = 0xa1;
    public static final int KEY_USE = 0xa2;
    public static final int KEY_FIRE = 0xa3;
    public static final int KEY_ESCAPE = 27;
    public static final int KEY_ENTER = 13;
    public static final int KEY_TAB = 9;
    public static final int KEY_BACKSPACE = 0x7f;

    public static final int KEY_RSHIFT = 0x80 + 0x36;
    public static final int KEY_RCTRL = 0x80 + 0x1d;
    public static final int KEY_RALT = 0x80 + 0x38;

    public static final int KEY_F1 = 0x80 + 0x3b;
    public static final int KEY_F2 = 0x80 + 0x3c;
    public static final int KEY_F3 = 0x80 + 0x3d;
    public static final int KEY_F4 = 0x80 + 0x3e;
    public static final int KEY_F5 = 0x80 + 0x3f;
    public static final int KEY_F6 = 0x80 + 0x40;
    public static final int KEY_F7 = 0x80 + 0x41;
    public static final int KEY_F8 = 0x80 + 0x42;
    public static final int KEY_F9 = 0x80 + 0x43;
    public static final int KEY_F10 = 0x80 + 0x44;

    public static final int KEY_EQUALS = 0x3d;
    public static final int KEY_MINUS = 0x2d;

    /**
     * Maps a GLFW key code to a doomgeneric key code, or {@code -1} if there is no
     * mapping. Printable keys fall through to their lowercase ASCII value.
     */
    /**
     * Maps a GLFW key code to a doomgeneric key code, or {@code -1} if there is no
     * mapping.
     *
     * <p>This is a faithful pass-through of the real DOOM controls — nothing is
     * re-bound to a "modern" scheme. Printable keys map to their lowercase ASCII
     * value and special keys use DOOM's {@code 0x80+} codes. Because the DOOM
     * input overlay captures everything while focused, these keys drive DOOM and
     * never leak to the Minecraft player.
     *
     * <p>Classic DOOM defaults: arrows move/turn, Ctrl fires, Space uses/opens,
     * Shift runs, Alt strafes, comma/period strafe, number keys pick weapons.
     */
    public static int fromGlfw(int glfwKey) {
        switch (glfwKey) {
            case GLFW.GLFW_KEY_UP:
                return KEY_UPARROW;
            case GLFW.GLFW_KEY_DOWN:
                return KEY_DOWNARROW;
            case GLFW.GLFW_KEY_LEFT:
                return KEY_LEFTARROW;
            case GLFW.GLFW_KEY_RIGHT:
                return KEY_RIGHTARROW;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                return KEY_ENTER;
            case GLFW.GLFW_KEY_ESCAPE:
                return KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB:
                return KEY_TAB;
            case GLFW.GLFW_KEY_BACKSPACE:
                return KEY_BACKSPACE;
            case GLFW.GLFW_KEY_LEFT_SHIFT:
            case GLFW.GLFW_KEY_RIGHT_SHIFT:
                return KEY_RSHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL:
            case GLFW.GLFW_KEY_RIGHT_CONTROL:
                return KEY_FIRE; // Ctrl = fire (DOOM default)
            case GLFW.GLFW_KEY_LEFT_ALT:
            case GLFW.GLFW_KEY_RIGHT_ALT:
                return KEY_RALT; // Alt = strafe modifier (DOOM default)
            case GLFW.GLFW_KEY_SPACE:
                return KEY_USE;  // Space = use/open (DOOM default)
            case GLFW.GLFW_KEY_F1: return KEY_F1;
            case GLFW.GLFW_KEY_F2: return KEY_F2;
            case GLFW.GLFW_KEY_F3: return KEY_F3;
            case GLFW.GLFW_KEY_F4: return KEY_F4;
            case GLFW.GLFW_KEY_F5: return KEY_F5;
            case GLFW.GLFW_KEY_F6: return KEY_F6;
            case GLFW.GLFW_KEY_F7: return KEY_F7;
            case GLFW.GLFW_KEY_F8: return KEY_F8;
            case GLFW.GLFW_KEY_F9: return KEY_F9;
            case GLFW.GLFW_KEY_F10: return KEY_F10;
            case GLFW.GLFW_KEY_EQUAL: return KEY_EQUALS;
            case GLFW.GLFW_KEY_MINUS: return KEY_MINUS;
            default:
                // Printable ASCII letters/digits: GLFW uses uppercase ASCII for
                // letters; DOOM wants lowercase.
                if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
                    return (glfwKey - GLFW.GLFW_KEY_A) + 'a';
                }
                if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
                    return (glfwKey - GLFW.GLFW_KEY_0) + '0';
                }
                return -1;
        }
    }
}
