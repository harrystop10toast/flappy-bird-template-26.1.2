package com.example.gamebox.child;

/**
 * The tiny wire protocol shared by the host and the hidden child client over a
 * localhost socket. Two message kinds flow in opposite directions:
 *
 * <ul>
 *   <li><b>Child → Host: FRAME</b> — one encoded braille frame as run-length runs
 *       of {@code (glyph, rgb)} so a near-static view is only a few KB. Layout:
 *       <pre>
 *   byte  type = MSG_FRAME
 *   int   cols
 *   int   rows
 *   int   runCount
 *   runCount × { char glyph; int rgb; short runLength }
 *       </pre></li>
 *   <li><b>Host → Child: INPUT</b> — a keyboard/mouse event to inject:
 *       <pre>
 *   byte  type = MSG_KEY | MSG_CHAR | MSG_MOUSE_BTN | MSG_MOUSE_MOVE
 *   (MSG_KEY)        int key; int scancode; int action; int mods
 *   (MSG_CHAR)       int codepoint; int mods
 *   (MSG_MOUSE_BTN)  int button; int action; int mods
 *   (MSG_MOUSE_MOVE) double dx; double dy
 *       </pre></li>
 * </ul>
 *
 * All integers are big-endian via {@code DataInput/DataOutputStream}.
 */
public final class FrameProtocol {

    public static final int MAGIC = 0x47424f58; // "GBOX"

    public static final byte MSG_FRAME = 1;
    public static final byte MSG_KEY = 2;
    public static final byte MSG_CHAR = 3;
    public static final byte MSG_MOUSE_BTN = 4;
    public static final byte MSG_MOUSE_MOVE = 5;
    /** Host → Child: camera look delta (artificial mouse). {@code double dYaw; double dPitch} */
    public static final byte MSG_LOOK = 6;
    /**
     * Host → Child: absolute cursor position + button for menu/GUI interaction.
     * {@code int xRaw; int yRaw; int button; int action} where x/y are raw
     * framebuffer pixels in the child's window.
     */
    public static final byte MSG_CURSOR = 7;

    private FrameProtocol() {
    }
}
