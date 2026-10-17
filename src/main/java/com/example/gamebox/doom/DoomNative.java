package com.example.gamebox.doom;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * JNA binding to the native {@code gameboxdoom} shared library (a thin platform
 * layer around <a href="https://github.com/ozkl/doomgeneric">doomgeneric</a>,
 * GPL-2.0).
 *
 * <p>The native side implements the doomgeneric platform callbacks
 * ({@code DG_Init}, {@code DG_DrawFrame}, {@code DG_SleepMs},
 * {@code DG_GetTicksMs}, {@code DG_GetKey}) and exposes the small, stable C ABI
 * declared here so Java can drive the engine and read frames. See
 * {@code native/doomgeneric_gamebox.c}.
 *
 * <p>Everything here is called from the dedicated engine thread (see
 * {@code DoomEngine}); the native library is single-threaded with respect to
 * the engine.
 */
public interface DoomNative extends Library {

    /** DOOM's framebuffer dimensions. */
    int DOOMGENERIC_RESX = 320;
    int DOOMGENERIC_RESY = 200;

    /**
     * Initialises the engine. {@code argv} is the standard DOOM command line,
     * NULL-terminated, e.g. {@code {"doom", "-iwad", "/path/DOOM1.WAD", NULL}}.
     * Returns 0 on success, non-zero on failure.
     *
     * <p>Internally this calls {@code doomgeneric_Create(argc, argv)}.
     */
    int gamebox_doom_init(int argc, String[] argv);

    /**
     * Runs a single engine iteration ({@code doomgeneric_Tick()}), which advances
     * the simulation and renders one frame into the internal framebuffer.
     */
    void gamebox_doom_tick();

    /**
     * Returns a pointer to the current 320x200 ARGB framebuffer
     * ({@code DG_ScreenBuffer}). The buffer is {@code DOOMGENERIC_RESX *
     * DOOMGENERIC_RESY} 32-bit pixels (0x00RRGGBB / 0xAARRGGBB).
     */
    Pointer gamebox_doom_framebuffer();

    /**
     * Pushes a key event into the native input queue consumed by
     * {@code DG_GetKey}.
     *
     * @param pressed   1 for key down, 0 for key up
     * @param doomKey   the doomgeneric key code (see DoomKeys / KEY_* mapping)
     */
    void gamebox_doom_push_key(int pressed, int doomKey);

    /**
     * Feeds a relative mouse movement for mouse-look (extension to doomgeneric).
     *
     * @param dx horizontal delta (turn)
     * @param dy vertical delta (unused by vanilla DOOM but forwarded)
     * @param buttons bitmask of mouse buttons currently held (bit0=left/fire,
     *                bit1=right, bit2=middle)
     */
    void gamebox_doom_push_mouse(int dx, int dy, int buttons);

    /** Monotonic millisecond clock the engine uses ({@code DG_GetTicksMs}). */
    int gamebox_doom_ticks_ms();

    /** Shuts the engine down and frees native resources. */
    void gamebox_doom_shutdown();
}
