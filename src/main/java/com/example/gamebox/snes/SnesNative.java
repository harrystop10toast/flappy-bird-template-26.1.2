package com.example.gamebox.snes;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * JNA binding to the native {@code gameboxsnes} shared library — a thin libretro
 * frontend (see {@code native/snes9x_gamebox.c}) that loads a snes9x libretro
 * core at runtime and exposes the small, stable C ABI declared here, mirroring
 * {@link com.example.gamebox.doom.DoomNative}.
 *
 * <p>All methods are called from the dedicated SNES engine thread (see
 * {@code SnesEngine}); the native library is single-threaded with respect to the
 * engine.
 */
public interface SnesNative extends Library {

    /** Fixed framebuffer the bridge produces (SNES common resolution). */
    int SNES_RESX = 256;
    int SNES_RESY = 224;

    // ── libretro joypad button ids (must match the native bridge) ──
    int JOYPAD_B = 0;
    int JOYPAD_Y = 1;
    int JOYPAD_SELECT = 2;
    int JOYPAD_START = 3;
    int JOYPAD_UP = 4;
    int JOYPAD_DOWN = 5;
    int JOYPAD_LEFT = 6;
    int JOYPAD_RIGHT = 7;
    int JOYPAD_A = 8;
    int JOYPAD_X = 9;
    int JOYPAD_L = 10;
    int JOYPAD_R = 11;

    /**
     * Sets the absolute path to the snes9x libretro core to load. Optional — if
     * not called, the bridge falls back to the {@code GAMEBOX_SNES_CORE}
     * environment variable and then a default core name on the library path.
     * Must be called before {@link #gamebox_snes_init}.
     */
    void gamebox_snes_set_core_path(String path);

    /**
     * Loads the core and the ROM at {@code romPath}. Returns 0 on success, or a
     * negative error code: -1 core not loadable, -2 core missing symbols,
     * -3 ROM unreadable, -4 core failed to load the game.
     */
    int gamebox_snes_init(String romPath);

    /** Runs one frame of emulation ({@code retro_run}). */
    void gamebox_snes_tick();

    /**
     * Returns a pointer to the current 256x224 ARGB framebuffer. The buffer is
     * {@code SNES_RESX * SNES_RESY} 32-bit pixels (0xFFRRGGBB).
     */
    Pointer gamebox_snes_framebuffer();

    /** Native framebuffer width (256). */
    int gamebox_snes_resx();

    /** Native framebuffer height (224). */
    int gamebox_snes_resy();

    /**
     * Sets a joypad button state. {@code index} is a {@code JOYPAD_*} id above;
     * {@code pressed} is 1 for down, 0 for up.
     */
    void gamebox_snes_push_button(int index, int pressed);

    /** Soft-resets the console. */
    void gamebox_snes_reset();

    /** Monotonic millisecond clock since init. */
    int gamebox_snes_ticks_ms();

    /** Unloads the game, deinitialises the core, and frees native resources. */
    void gamebox_snes_shutdown();
}
