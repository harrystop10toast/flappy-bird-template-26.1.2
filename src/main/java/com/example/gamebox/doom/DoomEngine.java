package com.example.gamebox.doom;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.Game;
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
 * Drives the native doomgeneric engine and renders DOOM frames into a
 * {@link BrailleDisplay}.
 *
 * <h2>Threading</h2>
 * The engine simulation runs on a dedicated daemon thread at 35 Hz (DOOM's tic
 * rate). Each iteration calls {@link DoomNative#gamebox_doom_tick()} and copies
 * the native 320x200 ARGB framebuffer into a Java array. The Minecraft client
 * thread then calls {@link #getFrame()} which downscales + dithers that snapshot
 * into Braille lines. Input events arrive from the client thread and are handed
 * straight to the native input queue (which is drained by {@code DG_GetKey} on
 * the engine thread).
 */
public final class DoomEngine implements Game {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox/doom");

    private static final int RESX = DoomNative.DOOMGENERIC_RESX; // 320
    private static final int RESY = DoomNative.DOOMGENERIC_RESY; // 200
    private static final long TIC_NANOS = 1_000_000_000L / 35L;  // 35 Hz

    private static DoomNative nativeLib;
    private static Throwable loadError;

    // Chat canvas, built once (fixed 64 x 9 cells). Rendered frame-by-frame into
    // chat like Flappy Bird.
    private BrailleDisplay display;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread engineThread;
    private volatile String status = "idle";

    /** Latest framebuffer snapshot (ARGB), published by the engine thread. */
    private final AtomicReference<int[]> latestFrame = new AtomicReference<>();

    private final Path wadPath;

    public DoomEngine(Path wadPath) {
        this.wadPath = wadPath;
    }

    /** The WAD file this engine was constructed with. */
    public Path wad() {
        return wadPath;
    }

    // ─── Native library loading ──────────────────────────────────────────────

    /**
     * Loads the native bridge library. Resolution order:
     * <ol>
     *   <li>the {@code gamebox.doom.lib} system property (absolute path);</li>
     *   <li>a platform-specific file bundled in the mod jar under
     *       {@code /natives/<os>/<libname>};</li>
     *   <li>the system library search path (name {@code gameboxdoom}).</li>
     * </ol>
     *
     * @return true if the library is available
     */
    public static synchronized boolean ensureLoaded() {
        if (nativeLib != null) {
            return true;
        }
        if (loadError != null) {
            return false;
        }
        try {
            String override = System.getProperty("gamebox.doom.lib");
            if (override != null && !override.isBlank()) {
                nativeLib = Native.load(override, DoomNative.class);
                return true;
            }
            Path extracted = tryExtractBundled();
            String target = extracted != null ? extracted.toString() : "gameboxdoom";
            nativeLib = Native.load(target, DoomNative.class);
            return true;
        } catch (Throwable t) {
            loadError = t;
            LOGGER.error("Failed to load native doomgeneric bridge ('gameboxdoom'). "
                    + "Build it from native/ and place it on the library path, "
                    + "or set -Dgamebox.doom.lib=/abs/path/to/lib. Cause: {}", t.toString());
            return false;
        }
    }

    public static String loadErrorMessage() {
        return loadError == null ? "" : loadError.toString();
    }

    /** Copies the bundled native lib (if present) to a temp file JNA can load. */
    private static Path tryExtractBundled() {
        String resource = "/natives/" + osDir() + "/" + System.mapLibraryName("gameboxdoom");
        try (InputStream in = DoomEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path tmpDir = Files.createTempDirectory("gamebox-doom");
            Path out = tmpDir.resolve(System.mapLibraryName("gameboxdoom"));
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            out.toFile().deleteOnExit();
            tmpDir.toFile().deleteOnExit();
            return out;
        } catch (IOException e) {
            LOGGER.warn("Could not extract bundled native lib: {}", e.toString());
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

    // ─── Game lifecycle ──────────────────────────────────────────────────────

    public synchronized boolean start() {
        if (running.get()) {
            return true;
        }
        if (!ensureLoaded()) {
            status = "native library not available: " + loadErrorMessage();
            return false;
        }
        if (wadPath == null || !Files.isReadable(wadPath)) {
            status = "WAD not found at " + wadPath;
            LOGGER.error(status);
            return false;
        }

        String[] argv = {"doom", "-iwad", wadPath.toAbsolutePath().toString()};
        int rc = nativeLib.gamebox_doom_init(argv.length, argv);
        if (rc != 0) {
            status = "gamebox_doom_init failed (rc=" + rc + ")";
            LOGGER.error(status);
            return false;
        }

        running.set(true);
        paused.set(false);
        status = "running";

        engineThread = new Thread(this::engineLoop, "gamebox-doom-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        return true;
    }

    private void engineLoop() {
        int[] frame = new int[RESX * RESY];
        long next = System.nanoTime();
        while (running.get()) {
            if (paused.get()) {
                sleep(16);
                next = System.nanoTime();
                continue;
            }
            try {
                nativeLib.gamebox_doom_tick();
                Pointer fb = nativeLib.gamebox_doom_framebuffer();
                if (fb != null) {
                    fb.read(0, frame, 0, frame.length);
                    latestFrame.set(frame.clone());
                }
            } catch (Throwable t) {
                LOGGER.error("Doom engine tick failed; stopping", t);
                running.set(false);
                status = "crashed: " + t;
                break;
            }

            next += TIC_NANOS;
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
                nativeLib.gamebox_doom_shutdown();
            }
        } catch (Throwable err) {
            LOGGER.warn("doom shutdown error: {}", err.toString());
        }
        engineThread = null;
        latestFrame.set(null);
        status = "idle";
    }

    // ─── Input (called from client thread / input screen) ──────────────────────

    public void pushKey(boolean pressed, int doomKey) {
        if (doomKey >= 0 && running.get() && nativeLib != null) {
            nativeLib.gamebox_doom_push_key(pressed ? 1 : 0, doomKey);
        }
    }

    public void pushMouse(int dx, int dy, int buttons) {
        if (running.get() && nativeLib != null) {
            nativeLib.gamebox_doom_push_mouse(dx, dy, buttons);
        }
    }

    // ─── Game interface ────────────────────────────────────────────────────────

    @Override
    public String id() {
        return "doom";
    }

    @Override
    public String displayName() {
        return "DOOM";
    }

    @Override
    public boolean isActive() {
        return running.get();
    }

    /** Builds/rebuilds the chat canvas (fixed 64 wide; height from GameBoxConfig). */
    private void ensureDisplay() {
        int w = com.example.gamebox.GameBoxConfig.get().chatPixelWidth();
        int h = com.example.gamebox.GameBoxConfig.get().chatPixelHeight();
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

    /** Raw, uncompressed 320x200 ARGB framebuffer snapshot, or null. For the debug view. */
    public int[] latestArgb() {
        return latestFrame.get();
    }

    /** Native framebuffer width (320). */
    public static int resX() {
        return RESX;
    }

    /** Native framebuffer height (200). */
    public static int resY() {
        return RESY;
    }

    /**
     * Renders the latest DOOM frame into the Minecraft chat box, frame-by-frame,
     * exactly like Flappy Bird: clear chat, post a fresh client-side system
     * message of a header line + Braille rows. MUST be called on the client
     * thread (it touches the GUI).
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
                " §c§lDOOM §7[" + status + (paused.get() ? " · paused" : "")
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

    @Override
    public String getStatusLine() {
        return "DOOM  [" + status + "]";
    }

    public String status() {
        return status;
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
