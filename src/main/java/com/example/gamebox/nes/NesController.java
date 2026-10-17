package com.example.gamebox.nes;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level control surface for Super Mario Bros., shared by the
 * {@code /supermario} command. Owns the single {@link NesEngine}, resolves the
 * NES ROM, and pushes the input overlay when the game starts.
 *
 * <p>Unlike DOOM (a native engine that cannot be re-initialised), the NES console
 * is pure Java, so a fresh engine + cartridge can be created on each run —
 * which makes {@code restart} a genuine power-cycle.
 *
 * <h2>ROM resolution order</h2>
 * <ol>
 *   <li>the {@code gamebox.nes.rom} system property (absolute path);</li>
 *   <li>the game directory and its parent — the brief places the ROM
 *       "directly under the main folder": in dev the game dir is {@code run/},
 *       so its parent is the project root where the ROM lives;</li>
 *   <li>{@code <game dir>/config/gamebox/}.</li>
 * </ol>
 */
public final class NesController {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/nes");

    private static final String[] ROM_CANDIDATES = {
            "Super Mario Bros. (Europe) (Rev A).nes",
            "Super Mario Bros. (World).nes",
            "Super Mario Bros.nes",
            "supermario.nes",
            "smb.nes",
            "mario.nes",
    };

    private NesEngine engine;
    private String lastError = "";
    private boolean hidden = true;

    /** Starts a new session, or resumes a paused one, and grabs keyboard input. */
    public synchronized String run() {
        // Resume an existing (possibly paused/frozen) session where it left off,
        // rather than restarting the game.
        if (engine != null && engine.isActive()) {
            hidden = false;
            engine.resume();
            openInputScreen();
            return "Super Mario Bros. resumed.";
        }

        Path rom = resolveRom();
        if (rom == null) {
            lastError = "ROM not found. Place \"" + ROM_CANDIDATES[0] + "\" in "
                    + gameDir() + " (or its parent / config/gamebox/) or set "
                    + "-Dgamebox.nes.rom=/abs/path.nes";
            return lastError;
        }
        return run(rom);
    }

    /**
     * Starts a fresh NES session from a specific ROM file (used by the unified
     * {@code /game <file.nes>} command). Always loads the given ROM — it does not
     * resume a prior session — so launching a different ROM swaps the cartridge.
     *
     * @param rom a readable path to an iNES {@code .nes} ROM
     * @return a human-readable status line
     */
    public synchronized String run(Path rom) {
        if (rom == null || !Files.isReadable(rom)) {
            lastError = "ROM not readable: " + rom;
            return lastError;
        }

        // Replace any existing session so a new ROM takes effect.
        if (engine != null) {
            engine.stop();
            engine = null;
            hidden = true;
        }

        Cartridge cart;
        try {
            cart = Cartridge.fromFile(rom);
        } catch (Exception e) {
            lastError = "Failed to load ROM: " + e.getMessage();
            LOGGER.error(lastError, e);
            return lastError;
        }

        engine = new NesEngine();
        if (!engine.load(cart)) {
            lastError = engine.status();
            engine = null;
            return "Failed to init NES: " + lastError;
        }
        if (!engine.start()) {
            lastError = engine.status();
            engine = null;
            return "Failed to start NES: " + lastError;
        }
        hidden = false;
        openInputScreen();
        return "NES started (ROM: " + rom.getFileName() + ").";
    }

    /**
     * Pauses (freezes) the current session, keeping it alive so {@code run}
     * resumes exactly where it left off. Releases the chat box — nothing is
     * re-posted while paused — so the player can read normal messages.
     */
    public synchronized String stop() {
        if (engine == null || !engine.isActive()) {
            return "Super Mario Bros. is not running.";
        }
        engine.pause();
        hidden = false; // engine stays "active"; ownsChat()==false because paused
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                // If our input overlay is grabbing the keyboard, release it so
                // keys go back to Minecraft while paused.
                if (mc.screen instanceof NesInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§c[GameBox] §7Super Mario Bros. paused — chat is yours. "
                                        + "§e/supermario run §7to continue, "
                                        + "§e/supermario restart §7for a fresh game."));
            });
        }
        return "Super Mario Bros. paused.";
    }

    /** Full power-cycle: destroy the current session and start a fresh game. */
    public synchronized String restart() {
        if (engine != null) {
            engine.stop();
            engine = null;
            hidden = true;
        }
        return run().replaceFirst("started", "restarted");
    }

    public synchronized boolean isActive() {
        return engine != null && !hidden && engine.isActive();
    }

    /**
     * Whether Super Mario should currently be drawing to (and clearing) the chat
     * box. False while paused so the player can read normal chat.
     */
    public synchronized boolean ownsChat() {
        return isActive() && !engine.isPaused();
    }

    public synchronized NesEngine engine() {
        return engine;
    }

    /** Renders the current NES frame into chat; call on the client thread each tick. */
    public void renderToChat() {
        NesEngine e = engine;
        if (e != null && !hidden && e.isActive() && !e.isPaused()) {
            e.renderToChat();
        }
    }

    /** Re-opens the input overlay (bound to the shared focus hotkey, K). */
    public void focus() {
        if (engine != null && !hidden && engine.isActive()) {
            openInputScreen();
        }
    }

    private void openInputScreen() {
        Minecraft mc = Minecraft.getInstance();
        NesEngine e = engine;
        if (e != null) {
            mc.execute(() -> mc.setScreen(new NesInputScreen(e)));
        }
    }

    private Path resolveRom() {
        String override = System.getProperty("gamebox.nes.rom");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isReadable(p)) {
                return p;
            }
            LOGGER.warn("gamebox.nes.rom set but not readable: {}", override);
        }
        Path game = gameDir();
        Path[] searchDirs = {
                game,
                game.getParent(),
                game.resolve("config").resolve("gamebox"),
        };
        for (Path dir : searchDirs) {
            if (dir == null) {
                continue;
            }
            for (String name : ROM_CANDIDATES) {
                Path p = dir.resolve(name);
                if (Files.isReadable(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
