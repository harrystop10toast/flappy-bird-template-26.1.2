package com.example.gamebox;

import java.util.List;

/**
 * A game that renders into a {@link BrailleDisplay} and is shown on the HUD.
 *
 * <p>All methods are invoked on the Minecraft client thread unless noted.
 */
public interface Game {

    /** Stable identifier, e.g. {@code "flappybird"} or {@code "doom"}. */
    String id();

    /** Human-readable name shown in the GameBox GUI, e.g. {@code "Flappy Bird"}. */
    String displayName();

    /** Whether the game currently wants to be drawn on the HUD. */
    boolean isActive();

    /**
     * Advances the game by one client tick. Flappy Bird uses this to step its
     * simulation and repaint the chat box; DOOM runs on a background thread, so
     * its tick is a no-op.
     */
    default void tick() {
    }

    /**
     * Returns the current frame as Braille lines (15 lines x 48 chars), or
     * {@code null} if there is nothing to draw this frame.
     */
    List<String> getFrame();

    /** Optional status line drawn above the frame (may be {@code null}). */
    default String getStatusLine() {
        return null;
    }

    /** Stops the game and releases any resources. */
    void stop();
}
