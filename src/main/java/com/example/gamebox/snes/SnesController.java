package com.example.gamebox.snes;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level control surface for the SNES core, driven by
 * {@code /game open <file.sfc>}. Owns the single {@link SnesEngine}, loads a ROM
 * from a path, and pushes the input overlay when the game starts.
 *
 * <p>Like DOOM (and unlike the pure-Java NES/Game Boy cores), the native
 * libretro core is kept alive for the whole session: {@code stop} pauses and
 * hides the engine, and a later {@code run} resumes it. The first ROM binds the
 * engine for the session; opening a different ROM resumes the current one and
 * notes that swapping needs a restart. All failures are returned as readable
 * status strings — nothing throws into Minecraft.
 */
public final class SnesController {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/snes");

    private SnesEngine engine;
    private String lastError = "";
    private boolean hidden = true;

    /**
     * Starts the SNES core on the given ROM (or resumes the running session).
     *
     * @param rom a readable path to a {@code .sfc}/{@code .snes} ROM
     * @return a human-readable status line
     */
    public synchronized String run(Path rom) {
        if (rom == null || !Files.isReadable(rom)) {
            return "ROM not readable: " + rom;
        }

        if (engine != null) {
            Path current = engine.rom();
            hidden = false;
            engine.resume();
            openInputScreen();
            if (current != null && !current.equals(rom)) {
                return "Super NES resumed (still running \"" + current.getFileName()
                        + "\" — the native core can't swap ROMs mid-session; restart "
                        + "Minecraft to load \"" + rom.getFileName() + "\").";
            }
            return "Super NES resumed (" + rom.getFileName() + ").";
        }

        engine = new SnesEngine(rom);
        if (!engine.start()) {
            lastError = engine.status();
            engine = null;
            return "Failed to start Super NES: " + lastError;
        }
        hidden = false;
        openInputScreen();
        return "Super NES started (" + rom.getFileName() + ").";
    }

    /** Pauses (freezes) and hides the session, releasing the chat box. */
    public synchronized String stop() {
        if (engine == null || hidden) {
            return "Super NES is not running.";
        }
        engine.pause();
        hidden = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                if (mc.screen instanceof SnesInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§d[GameBox] §7Super NES stopped — chat is yours. "
                                        + "§e/game open <file.sfc> §7to resume."));
            });
        }
        return "Super NES stopped.";
    }

    public synchronized boolean isActive() {
        return engine != null && !hidden && engine.isActive();
    }

    /** Whether the SNES currently owns (clears + repaints) the chat box. */
    public synchronized boolean ownsChat() {
        return isActive() && !engine.isPaused();
    }

    public synchronized SnesEngine engine() {
        return engine;
    }

    /** Renders the current frame into chat; call on the client thread each tick. */
    public void renderToChat() {
        SnesEngine e = engine;
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
        SnesEngine e = engine;
        if (e != null && mc != null) {
            mc.execute(() -> mc.setScreen(new SnesInputScreen(e)));
        }
    }
}
