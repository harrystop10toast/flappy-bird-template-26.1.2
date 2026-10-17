package com.example.gamebox;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * GameBox key mappings, registered into the vanilla Controls screen so the player
 * rebinds them there. Movement inside DOOM uses a fixed GLFW&rarr;DOOM mapping
 * (WASD, arrows, Ctrl=fire, Space=use) handled by {@code DoomInputScreen}; the two
 * mappings here are the Flappy Bird flap key and the DOOM grab-keyboard key.
 */
public final class GameBoxKeybinds {

    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("gamebox", "gamebox"));

    /** Flappy Bird: flap / start / resume. */
    public static final KeyMapping FLAP = new KeyMapping(
            "key.gamebox.flap", GLFW.GLFW_KEY_SPACE, CATEGORY);

    /** DOOM: grab the keyboard (open the input overlay) while DOOM is running. */
    public static final KeyMapping DOOM_FOCUS = new KeyMapping(
            "key.gamebox.doom_focus", GLFW.GLFW_KEY_K, CATEGORY);

    private GameBoxKeybinds() {
    }

    /** Registers all key mappings. Call once from client init. */
    public static void register() {
        KeyMappingHelper.registerKeyMapping(FLAP);
        KeyMappingHelper.registerKeyMapping(DOOM_FOCUS);
    }
}
