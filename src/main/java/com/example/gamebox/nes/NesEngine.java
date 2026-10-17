package com.example.gamebox.nes;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.Game;
import com.example.gamebox.GameBoxConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the pure-Java NES console ({@link Bus}) on a dedicated 60 Hz daemon
 * thread and renders each 256x240 ARGB frame into the Minecraft chat box as
 * colored Unicode Braille — exactly the technique DOOM uses.
 *
 * <h2>Threading</h2>
 * The emulator thread calls {@link Bus#runFrame()} ~60 times a second and
 * publishes the resulting framebuffer via an {@link AtomicReference}. The
 * Minecraft client thread calls {@link #renderToChat()} each tick to downscale +
 * color-pack the latest snapshot into chat lines. Controller input arrives from
 * the client thread and is written straight into the {@link Controller} latch.
 */
public final class NesEngine implements Game {

    /** NES NTSC frame period: 60.0988 Hz ≈ 16.639 ms. */
    private static final long FRAME_NANOS = 16_639_267L;

    private final Bus bus = new Bus();
    private final AtomicReference<int[]> latestFrame = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Thread engineThread;
    private volatile String status = "idle";

    private BrailleDisplay display;

    /** Loads a cartridge and resets the console (does not start the thread). */
    public synchronized boolean load(Cartridge cart) {
        try {
            bus.insert(cart);
            bus.reset();
            status = "loaded";
            return true;
        } catch (Throwable t) {
            status = "load failed: " + t;
            return false;
        }
    }

    public synchronized boolean start() {
        if (running.get()) {
            return true;
        }
        if (bus.cartridge() == null) {
            status = "no cartridge";
            return false;
        }
        running.set(true);
        paused.set(false);
        status = "running";
        engineThread = new Thread(this::loop, "gamebox-nes-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        return true;
    }

    private void loop() {
        long next = System.nanoTime();
        while (running.get()) {
            if (paused.get()) {
                sleep(16);
                next = System.nanoTime();
                continue;
            }
            try {
                bus.runFrame();
                int[] fb = bus.ppu().framebuffer();
                latestFrame.set(fb.clone());
            } catch (Throwable t) {
                status = "crashed: " + t;
                running.set(false);
                break;
            }

            // Pace to the NES NTSC frame rate (~60.0988 Hz). Use an absolute
            // deadline and park (which honours sub-millisecond waits far better
            // than Thread.sleep) so the emulator can never outrun real time. If
            // we fall far behind (e.g. a GC pause), resync instead of
            // fast-forwarding.
            next += FRAME_NANOS;
            long now = System.nanoTime();
            long remaining = next - now;
            if (remaining > FRAME_NANOS) {
                // Clock jumped backwards or first iteration — clamp.
                remaining = FRAME_NANOS;
                next = now + FRAME_NANOS;
            }
            while (remaining > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                if (!running.get()) {
                    return;
                }
                remaining = next - System.nanoTime();
            }
            if (-remaining > 4 * FRAME_NANOS) {
                // More than ~4 frames behind: give up catching up and resync.
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
        engineThread = null;
        latestFrame.set(null);
        bus.controller1().clearAll();
        status = "idle";
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    public void setButton(int button, boolean pressed) {
        bus.controller1().setButton(button, pressed);
    }

    public void releaseAllButtons() {
        bus.controller1().clearAll();
    }

    // ─── Game interface ────────────────────────────────────────────────────────

    @Override
    public String id() {
        return "supermario";
    }

    @Override
    public String displayName() {
        return "Super Mario Bros.";
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
        display.blitArgb(frame, Ppu.WIDTH, Ppu.HEIGHT);
        return display.toLines();
    }

    public int[] latestArgb() {
        return latestFrame.get();
    }

    public String status() {
        return status;
    }

    /**
     * Renders the latest NES frame into the Minecraft chat box, frame-by-frame,
     * exactly like DOOM: clear chat, post a fresh client-side system message of a
     * header line + colored Braille rows. MUST run on the client thread.
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
        display.blitArgb(frame, Ppu.WIDTH, Ppu.HEIGHT);
        List<net.minecraft.network.chat.Component> lines = display.toColoredComponents();

        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        out.append(net.minecraft.network.chat.Component.literal(
                " §c§lSUPER MARIO BROS §7[" + status + (paused.get() ? " · paused" : "")
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
