package com.example.gamebox.child;

/**
 * Central flag for "am I the hidden child Minecraft client?". The host launches a
 * second copy of the game with {@code -Dgamebox.child=true} (plus a host socket
 * port in {@code -Dgamebox.child.port}); everything that must behave differently
 * in the child keys off {@link #isChild()}.
 *
 * <p>This is the backbone of the experimental second-client path, which is
 * <b>unverified on macOS Apple Silicon</b> until the fps gate test
 * ({@code GameRendererFpsMixin}) confirms a hidden GLFW window keeps rendering.
 */
public final class ChildMode {

    public static final String PROP_CHILD = "gamebox.child";
    public static final String PROP_PORT = "gamebox.child.port";

    private ChildMode() {
    }

    /** True in the child client process launched by the host. */
    public static boolean isChild() {
        return Boolean.getBoolean(PROP_CHILD);
    }

    /** The host's localhost frame/input socket port, or -1 if unset. */
    public static int hostPort() {
        try {
            return Integer.parseInt(System.getProperty(PROP_PORT, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
