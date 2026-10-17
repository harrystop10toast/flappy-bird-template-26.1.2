package com.example.gamebox.doom;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level control surface for DOOM, shared by the {@code /doom} command and
 * the GameBox GUI. Owns the single {@link DoomEngine} instance, resolves the WAD
 * file, and pushes the input overlay when the game starts.
 *
 * <p>WAD resolution order:
 * <ol>
 *   <li>the {@code gamebox.doom.wad} system property;</li>
 *   <li>{@code <game dir>/config/gamebox/DOOM1.WAD} (or {@code doom1.wad},
 *       {@code freedoom1.wad}, {@code freedoom2.wad}).</li>
 * </ol>
 */
public final class DoomController {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/doom");

    private static final String[] WAD_CANDIDATES = {
            "DOOM1.WAD", "doom1.wad", "DOOM.WAD", "doom.wad",
            "freedoom1.wad", "freedoom2.wad", "FREEDOOM1.WAD", "FREEDOOM2.WAD",
    };

    private DoomEngine engine;
    private String lastError = "";
    /** When true the engine is parked (paused) and does not own the chat box. */
    private boolean hidden = true;

    /** Starts (or resumes) DOOM and grabs keyboard input (used by /doom play). */
    public synchronized String play() {
        return play(true);
    }

    /**
     * Starts (or resumes) DOOM.
     *
     * @param grabInput if true, open the input overlay to capture the keyboard;
     *                  if false, leave the current screen (e.g. the GameBox GUI)
     *                  up so its on-screen controls can drive DOOM with the mouse
     */
    public synchronized String play(boolean grabInput) {
        // doomgeneric cannot be re-initialised within one process, so we keep a
        // single engine alive for the whole session. "stop" just pauses + hides it;
        // "play" un-hides and resumes (creating the engine only the first time).
        if (engine != null) {
            hidden = false;
            engine.resume();
            if (grabInput) {
                openInputScreen();
            }
            return "DOOM resumed.";
        }

        Path wad = resolveWad();
        if (wad == null) {
            lastError = "No WAD found. Place DOOM1.WAD (or a freedoom WAD) in "
                    + configDir() + " or set -Dgamebox.doom.wad=/abs/path.wad";
            return lastError;
        }

        engine = new DoomEngine(wad);
        boolean ok = engine.start();
        if (!ok) {
            lastError = engine.status();
            engine = null;
            return "Failed to start DOOM: " + lastError;
        }
        hidden = false;
        if (grabInput) {
            openInputScreen();
        }
        return "DOOM started (WAD: " + wad.getFileName() + ").";
    }

    /**
     * Launches DOOM from a specific WAD file (used by the unified
     * {@code /game <file.wad>} command).
     *
     * <p>doomgeneric cannot be re-initialised within a single process, so once a
     * DOOM engine exists it stays bound to its original WAD for the rest of the
     * session. If an engine is already alive this call simply resumes it and
     * notes that the WAD cannot be swapped without restarting Minecraft.
     *
     * @param wad a readable path to a DOOM IWAD/PWAD
     * @return a human-readable status line
     */
    public synchronized String play(Path wad) {
        if (wad == null || !Files.isReadable(wad)) {
            return "WAD not readable: " + wad;
        }
        if (engine != null) {
            Path current = engine.wad();
            hidden = false;
            engine.resume();
            openInputScreen();
            if (current != null && !current.equals(wad)) {
                return "DOOM resumed (still running \"" + current.getFileName()
                        + "\" — doomgeneric can't swap WADs mid-session; restart "
                        + "Minecraft to load \"" + wad.getFileName() + "\").";
            }
            return "DOOM resumed (WAD: " + wad.getFileName() + ").";
        }

        engine = new DoomEngine(wad);
        if (!engine.start()) {
            lastError = engine.status();
            engine = null;
            return "Failed to start DOOM: " + lastError;
        }
        hidden = false;
        openInputScreen();
        return "DOOM started (WAD: " + wad.getFileName() + ").";
    }

    public synchronized String pause() {
        if (engine == null || hidden) {
            return "DOOM is not running.";
        }
        engine.pause();
        // Stop owning the chat box so the player can read normal messages. Clear
        // the game frame once and leave a small notice; nothing is re-posted
        // while paused, so new chat appears normally. /doom resume (or play)
        // takes the chat back over.
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                if (mc.screen instanceof DoomInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§c[GameBox] §7DOOM paused — chat is yours. "
                                        + "§e/doom resume §7to continue."));
            });
        }
        return "DOOM paused.";
    }

    public synchronized String resume() {
        if (engine == null) {
            return "DOOM is not running.";
        }
        hidden = false;
        engine.resume();
        openInputScreen();
        return "DOOM resumed.";
    }

    public synchronized String stop() {
        if (engine == null || hidden) {
            return "DOOM is not running.";
        }
        // Pause (not destroy) the engine — doomgeneric can't be re-created — and
        // hide it so it no longer owns the chat box. A later /doom play resumes.
        engine.pause();
        hidden = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§c[GameBox] §7DOOM stopped. §e/doom play §7to start again."));
            });
        }
        return "DOOM stopped.";
    }

    public synchronized String status() {
        if (engine == null) {
            String libMsg = DoomEngine.ensureLoaded() ? "native library OK" : ("native lib unavailable: " + DoomEngine.loadErrorMessage());
            Path wad = resolveWad();
            return "DOOM: idle. " + libMsg + ". WAD: " + (wad == null ? "not found" : wad.getFileName());
        }
        if (hidden) {
            return "DOOM: stopped (engine parked — /doom play to resume)";
        }
        return "DOOM: " + engine.status() + (engine.isPaused() ? " (paused)" : "");
    }

    /** Whether DOOM is currently running (and thus owns the chat box). */
    public synchronized boolean isActive() {
        return engine != null && !hidden && engine.isActive();
    }

    /**
     * Whether DOOM should currently be drawing to (and clearing) the chat box.
     * False while paused, so the player can read normal chat until
     * {@code /doom resume}.
     */
    public synchronized boolean ownsChat() {
        return isActive() && !engine.isPaused();
    }

    /** The running engine, or null. Used by the input screen to forward input. */
    public synchronized DoomEngine engine() {
        return engine;
    }

    /**
     * Sends a single press+release of a DOOM key (see {@link DoomKeys}). Lets the
     * GameBox GUI drive menus (Enter, arrows, Fire…) with the mouse without having
     * to grab keyboard input through the overlay — handy for getting past the
     * title screen.
     */
    public void tapKey(int doomKey) {
        DoomEngine e = engine;
        if (e != null && !hidden && e.isActive()) {
            e.pushKey(true, doomKey);
            e.pushKey(false, doomKey);
        }
    }

    /** Renders the current DOOM frame into chat; call on the client thread each tick. */
    public void renderToChat() {
        DoomEngine e = engine;
        if (e != null && !hidden && e.isActive() && !e.isPaused()) {
            e.renderToChat();
        }
    }

    /** Re-opens the input overlay (bound to the DOOM focus hotkey). */
    public void focus() {
        if (engine != null && !hidden && engine.isActive()) {
            openInputScreen();
        }
    }

    private void openInputScreen() {
        Minecraft mc = Minecraft.getInstance();
        DoomEngine e = engine;
        if (e != null) {
            mc.execute(() -> mc.setScreen(new DoomInputScreen(e)));
        }
    }

    private Path resolveWad() {
        String override = System.getProperty("gamebox.doom.wad");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isReadable(p)) {
                return p;
            }
            LOGGER.warn("gamebox.doom.wad set but not readable: {}", override);
        }
        Path dir = configDir();
        for (String name : WAD_CANDIDATES) {
            Path p = dir.resolve(name);
            if (Files.isReadable(p)) {
                return p;
            }
        }
        return null;
    }

    private Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("gamebox");
    }
}
