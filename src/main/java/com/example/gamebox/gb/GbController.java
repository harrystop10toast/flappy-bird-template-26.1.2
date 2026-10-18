package com.example.gamebox.gb;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level control surface for the Game Boy core, driven by
 * {@code /game open <file.gb>}. Owns the single {@link GbEngine}, loads a ROM
 * from a path, and pushes the input overlay when the game starts.
 *
 * <p>Like the NES core (and unlike native DOOM), the Game Boy is pure Java, so a
 * fresh engine + cartridge is created on each ROM load — launching a different
 * ROM swaps the cartridge. All failures are returned as human-readable status
 * strings; nothing throws into Minecraft.
 */
public final class GbController {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/gb");

    private GbEngine engine;
    private String lastError = "";
    private boolean hidden = true;

    /**
     * Starts a fresh Game Boy session from a specific ROM file. Always loads the
     * given ROM (does not resume), so launching a different ROM swaps the
     * cartridge.
     *
     * @param rom a readable path to a {@code .gb}/{@code .gbc} ROM
     * @return a human-readable status line
     */
    public synchronized String run(Path rom) {
        if (rom == null || !Files.isReadable(rom)) {
            lastError = "ROM not readable: " + rom;
            return lastError;
        }

        if (engine != null) {
            engine.stop();
            engine = null;
            hidden = true;
        }

        GbCartridge cart;
        try {
            cart = GbCartridge.fromFile(rom);
        } catch (Exception e) {
            lastError = "Failed to load ROM: " + e.getMessage();
            LOGGER.error(lastError, e);
            return lastError;
        }

        engine = new GbEngine();
        if (!engine.load(cart)) {
            lastError = engine.status();
            engine = null;
            return "Failed to init Game Boy: " + lastError;
        }
        if (!engine.start()) {
            lastError = engine.status();
            engine = null;
            return "Failed to start Game Boy: " + lastError;
        }
        hidden = false;
        openInputScreen();
        return "Game Boy started: " + cart.title() + " (" + cart.mapper()
                + ", " + rom.getFileName() + ").";
    }

    /** Pauses (freezes) the session, releasing the chat box so chat is readable. */
    public synchronized String stop() {
        if (engine == null || !engine.isActive()) {
            return "Game Boy is not running.";
        }
        engine.pause();
        hidden = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                if (mc.screen instanceof GbInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§a[GameBox] §7Game Boy paused — chat is yours. "
                                        + "§eK §7to re-grab, §e/game stop §7when you're done."));
            });
        }
        return "Game Boy paused.";
    }

    public synchronized boolean isActive() {
        return engine != null && !hidden && engine.isActive();
    }

    /** Whether the Game Boy currently owns (clears + repaints) the chat box. */
    public synchronized boolean ownsChat() {
        return isActive() && !engine.isPaused();
    }

    public synchronized GbEngine engine() {
        return engine;
    }

    /** Renders the current frame into chat; call on the client thread each tick. */
    public void renderToChat() {
        GbEngine e = engine;
        if (e != null && !hidden && e.isActive() && !e.isPaused()) {
            e.renderToChat();
        }
    }

    /** Re-opens the input overlay (bound to the shared focus hotkey, K). */
    public void focus() {
        if (engine != null && !hidden && engine.isActive()) {
            engine.resume();
            openInputScreen();
        }
    }

    private void openInputScreen() {
        Minecraft mc = Minecraft.getInstance();
        GbEngine e = engine;
        if (e != null && mc != null) {
            mc.execute(() -> mc.setScreen(new GbInputScreen(e)));
        }
    }
}
