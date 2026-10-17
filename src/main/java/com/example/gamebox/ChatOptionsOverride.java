package com.example.gamebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * While a GameBox game (DOOM or Flappy Bird) is running, forces the vanilla chat
 * options so the Braille canvas has the most room, then restores the player's
 * original values when no game is running.
 *
 * <p>Chat options are normalised 0..1 doubles. With the Increased Chat Height mod
 * installed, {@code chatHeightFocused}/{@code chatHeightUnfocused} = {@code 1.0}
 * is the expanded max (720px). {@code chatScale} {@code 0.33} is ~33% text size
 * (smaller glyphs → more fit per line). The player's prior values are saved and
 * restored exactly.
 *
 * <p>Call {@link #sync(boolean)} every client tick with whether any game is
 * active; it applies once on the rising edge and restores once on the falling
 * edge, so it is safe to call repeatedly.
 */
public final class ChatOptionsOverride {

    private boolean applied;
    private double savedFocused;
    private double savedUnfocused;
    private double savedWidth;
    private double savedScale;

    /** Applies when {@code anyGameActive} and not yet applied; restores otherwise. */
    public void sync(boolean anyGameActive) {
        if (anyGameActive) {
            apply();
        } else {
            restore();
        }
    }

    private void apply() {
        if (applied) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        Options o = mc.options;
        savedFocused = o.chatHeightFocused().get();
        savedUnfocused = o.chatHeightUnfocused().get();
        savedWidth = o.chatWidth().get();
        savedScale = o.chatScale().get();

        // The Increased Chat Height mod replaces these options' value-sets so the
        // stored VALUE ranges 0..max (not 0..1), and the shown pixel height = value
        // * 180. The mod uses max = 4.0 for focused/unfocused (→ 4*180 = 720px) and
        // max = 5.0 for scale. So 720px = value 4.0, and 33% scale = value 0.33.
        // (Without the mod these clamp to vanilla's 180px max; the mod is a
        // declared dependency, so in practice 720px applies.)
        o.chatHeightFocused().set(4.0);   // 720px with the mod (4 * 180)
        o.chatHeightUnfocused().set(4.0); // 720px with the mod
        o.chatWidth().set(1.0);           // widest chat (vanilla 0..1)
        o.chatScale().set(0.33);          // 33% text size → more cells fit per line
        o.save();
        applied = true;
    }

    private void restore() {
        if (!applied) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            Options o = mc.options;
            o.chatHeightFocused().set(savedFocused);
            o.chatHeightUnfocused().set(savedUnfocused);
            o.chatWidth().set(savedWidth);
            o.chatScale().set(savedScale);
            o.save();
        }
        applied = false;
    }
}
