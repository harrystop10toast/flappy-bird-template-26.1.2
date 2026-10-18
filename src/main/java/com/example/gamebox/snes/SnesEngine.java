package com.example.gamebox.snes;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.Game;
import com.example.gamebox.GameBoxConfig;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the native snes9x libretro core (through the {@code gameboxsnes} bridge)
 * and renders SNES frames into the Minecraft chat box as colored Braille —
 * mirroring {@link com.example.gamebox.doom.DoomEngine}.
 *
 * <h2>Threading</h2>
 * A dedicated ~60 Hz daemon thread calls {@link SnesNative#gamebox_snes_tick()}
 * and copies the native 256x224 ARGB framebuffer into a Java array. The Minecraft
 * client thread calls {@link #renderToChat()} each tick to dither that snapshot
 * into Braille. Each tick is wrapped in try/catch so a native fault stops the
 * engine with a status line rather than taking down the client.
 */
public final class SnesEngine implements Game {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/snes");

    private static final int RESX = SnesNative.SNES_RESX; // 256
    private static final int RESY = SnesNative.SNES_RESY; // 224
    private static final long FRAME_NANOS = 1_000_000_000L / 60L;

    private static SnesNative nativeLib;
    private static Throwable loadError;
    private static boolean coreResolved;

    private BrailleDisplay display;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread engineThread;
    private volatile String status = "idle";

    private final AtomicReference<int[]> latestFrame = new AtomicReference<>();

    private final Path romPath;

    public SnesEngine(Path romPath) {
        this.romPath = romPath;
    }

    public Path rom() {
        return romPath;
    }

    // ─── Native library loading ──────────────────────────────────────────────

    /**
     * Loads the native bridge. Resolution order for the bridge library:
     * <ol>
     *   <li>the {@code gamebox.snes.lib} system property (absolute path);</li>
     *   <li>a bundled copy at {@code /natives/<os>/<gameboxsnes>};</li>
     *   <li>the system library search path (name {@code gameboxsnes}).</li>
     * </ol>
     * Then it resolves the snes9x <em>core</em> and hands its path to the bridge:
     * {@code -Dgamebox.snes.core=…}, else a bundled
     * {@code /natives/<os>/snes9x_libretro.<ext>}, else the bridge's own fallback.
     */
    public static synchronized boolean ensureLoaded() {
        if (nativeLib != null) {
            return true;
        }
        if (loadError != null) {
            return false;
        }
        try {
            String override = System.getProperty("gamebox.snes.lib");
            if (override != null && !override.isBlank()) {
                nativeLib = Native.load(override, SnesNative.class);
            } else {
                Path extracted = tryExtractBundled("gameboxsnes");
                String target = extracted != null ? extracted.toString() : "gameboxsnes";
                nativeLib = Native.load(target, SnesNative.class);
            }
            resolveCorePath();
            return true;
        } catch (Throwable t) {
            loadError = t;
            LOGGER.error("Failed to load native SNES bridge ('gameboxsnes'). Build it "
                    + "with native/build-snes.sh and place it on the library path, "
                    + "or set -Dgamebox.snes.lib=/abs/path. Cause: {}", t.toString());
            return false;
        }
    }

    /** Tells the bridge where the snes9x libretro core is (once). */
    private static void resolveCorePath() {
        if (coreResolved || nativeLib == null) {
            return;
        }
        coreResolved = true;
        String override = System.getProperty("gamebox.snes.core");
        if (override != null && !override.isBlank()) {
            nativeLib.gamebox_snes_set_core_path(override);
            return;
        }
        Path core = tryExtractCore();
        if (core != null) {
            nativeLib.gamebox_snes_set_core_path(core.toString());
        }
        // else: let the bridge fall back to GAMEBOX_SNES_CORE / default name.
    }

    public static String loadErrorMessage() {
        return loadError == null ? "" : loadError.toString();
    }

    private static Path tryExtractBundled(String libBaseName) {
        String resource = "/natives/" + osDir() + "/" + System.mapLibraryName(libBaseName);
        try (InputStream in = SnesEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path tmpDir = Files.createTempDirectory("gamebox-snes");
            Path out = tmpDir.resolve(System.mapLibraryName(libBaseName));
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            tmpDir.toFile().deleteOnExit();
            return out;
        } catch (IOException e) {
            LOGGER.warn("Could not extract bundled native lib {}: {}", libBaseName, e.toString());
            return null;
        }
    }

    /** Extracts the bundled snes9x libretro core, if one ships in the jar. */
    private static Path tryExtractCore() {
        String coreFile;
        if (Platform.isWindows()) {
            coreFile = "snes9x_libretro.dll";
        } else if (Platform.isMac()) {
            coreFile = "snes9x_libretro.dylib";
        } else {
            coreFile = "snes9x_libretro.so";
        }
        String resource = "/natives/" + osDir() + "/" + coreFile;
        try (InputStream in = SnesEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path tmpDir = Files.createTempDirectory("gamebox-snes-core");
            Path out = tmpDir.resolve(coreFile);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            tmpDir.toFile().deleteOnExit();
            return out;
        } catch (IOException e) {
            LOGGER.warn("Could not extract bundled snes9x core: {}", e.toString());
            return null;
        }
    }

    private static String osDir() {
        if (Platform.isWindows()) {
            return "windows";
        }
        if (Platform.isMac()) {
            return "macos";
        }
        return "linux";
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized boolean start() {
        if (running.get()) {
            return true;
        }
        if (!ensureLoaded()) {
            status = "native library not available: " + loadErrorMessage();
            return false;
        }
        if (romPath == null || !Files.isReadable(romPath)) {
            status = "ROM not readable: " + romPath;
            LOGGER.error(status);
            return false;
        }

        int rc = nativeLib.gamebox_snes_init(romPath.toAbsolutePath().toString());
        if (rc != 0) {
            status = "init failed (rc=" + rc + ": " + describeInitError(rc) + ")";
            LOGGER.error("gamebox_snes_init failed: {}", status);
            return false;
        }

        running.set(true);
        paused.set(false);
        status = "running";
        engineThread = new Thread(this::loop, "gamebox-snes-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        return true;
    }

    private static String describeInitError(int rc) {
        switch (rc) {
            case -1: return "snes9x libretro core not found — build it with "
                    + "native/build-snes.sh or set -Dgamebox.snes.core=/abs/path";
            case -2: return "core missing required libretro symbols";
            case -3: return "ROM file unreadable";
            case -4: return "core rejected the ROM";
            default: return "unknown";
        }
    }

    private void loop() {
        int[] frame = new int[RESX * RESY];
        long next = System.nanoTime();
        while (running.get()) {
            if (paused.get()) {
                sleep(16);
                next = System.nanoTime();
                continue;
            }
            try {
                nativeLib.gamebox_snes_tick();
                Pointer fb = nativeLib.gamebox_snes_framebuffer();
                if (fb != null) {
                    fb.read(0, frame, 0, frame.length);
                    latestFrame.set(frame.clone());
                }
            } catch (Throwable t) {
                LOGGER.error("SNES engine tick failed; stopping", t);
                running.set(false);
                status = "crashed: " + t;
                break;
            }

            next += FRAME_NANOS;
            long sleepNs = next - System.nanoTime();
            if (sleepNs > 0) {
                sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
            } else {
                next = System.nanoTime();
            }
        }
    }

    public void pause() {
        if (running.get()) {
            paused.set(true);
            status = "paused";
        }
    }

    public void resume() {
        if (running.get()) {
            paused.set(false);
            status = "running";
        }
    }

    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        status = "stopping";
        Thread t = engineThread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (nativeLib != null) {
                nativeLib.gamebox_snes_shutdown();
            }
        } catch (Throwable err) {
            LOGGER.warn("snes shutdown error: {}", err.toString());
        }
        engineThread = null;
        latestFrame.set(null);
        status = "idle";
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    public void setButton(int retroId, boolean pressed) {
        if (retroId >= 0 && running.get() && nativeLib != null) {
            nativeLib.gamebox_snes_push_button(retroId, pressed ? 1 : 0);
        }
    }

    public void releaseAllButtons() {
        if (nativeLib == null) {
            return;
        }
        for (int id = 0; id <= SnesNative.JOYPAD_R; id++) {
            nativeLib.gamebox_snes_push_button(id, 0);
        }
    }

    // ─── Game interface ────────────────────────────────────────────────────────

    @Override
    public String id() {
        return "snes";
    }

    @Override
    public String displayName() {
        return "Super NES";
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    private void ensureDisplay() {
        int w = GameBoxConfig.get().chatPixelWidth();
        int h = GameBoxConfig.get().chatPixelHeight();
        if (display == null || display.width() != w || display.height() != h) {
            display = new BrailleDisplay(w, h);
        }
    }

    @Override
    public List<String> getFrame() {
        int[] frame = latestFrame.get();
        if (frame == null) {
            return null;
        }
        ensureDisplay();
        display.blitArgb(frame, RESX, RESY);
        return display.toLines();
    }

    public int[] latestArgb() {
        return latestFrame.get();
    }

    public String status() {
        return status;
    }

    /**
     * Renders the latest SNES frame into the Minecraft chat box, frame by frame,
     * exactly like DOOM. MUST run on the client thread.
     */
    public void renderToChat() {
        if (!running.get()) {
            return;
        }
        int[] frame = latestFrame.get();
        if (frame == null) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }

        ensureDisplay();
        display.blitArgb(frame, RESX, RESY);
        List<net.minecraft.network.chat.Component> lines = display.toColoredComponents();

        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        out.append(net.minecraft.network.chat.Component.literal(
                " §d§lSUPER NES §7[" + status + (paused.get() ? " · paused" : "")
                        + "]  §8ESC release · K regrab"));
        out.append(net.minecraft.network.chat.Component.literal("\n"));
        for (int i = 0; i < lines.size(); i++) {
            out.append(lines.get(i));
            if (i < lines.size() - 1) {
                out.append(net.minecraft.network.chat.Component.literal("\n"));
            }
        }

        net.minecraft.client.gui.components.ChatComponent chat = mc.gui.getChat();
        chat.clearMessages(false);
        chat.addClientSystemMessage(out);
    }

    private static void sleep(long ms) {
        sleep(ms, 0);
    }

    private static void sleep(long ms, int ns) {
        try {
            if (ms > 0 || ns > 0) {
                Thread.sleep(ms, ns);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
