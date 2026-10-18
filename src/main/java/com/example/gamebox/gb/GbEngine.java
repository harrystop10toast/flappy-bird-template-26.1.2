package com.example.gamebox.gb;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.Game;
import com.example.gamebox.GameBoxConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the pure-Java Game Boy console ({@link GbBus}) on a dedicated ~59.7 Hz
 * daemon thread and renders each 160×144 ARGB frame into the Minecraft chat box
 * as colored Unicode Braille — the same technique the NES core and DOOM use.
 *
 * <h2>Threading &amp; crash safety</h2>
 * The emulator thread calls {@link GbBus#runFrame()} and publishes the resulting
 * framebuffer via an {@link AtomicReference}. Each frame is wrapped in a
 * try/catch so an unexpected error pauses the game with a status line instead of
 * propagating into Minecraft. The Minecraft client thread calls
 * {@link #renderToChat()} each tick to downscale + color-pack the latest frame.
 */
public final class GbEngine implements Game {

    /** DMG refresh: 4,194,304 Hz / 70,224 cycles ≈ 59.727 Hz ≈ 16.742 ms. */
    private static final long FRAME_NANOS = 16_742_706L;

    private final GbBus bus = new GbBus();
    private final AtomicReference<int[]> latestFrame = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Thread engineThread;
    private volatile String status = "idle";
    private volatile String title = "GAME BOY";

    private BrailleDisplay display;

    /** Loads a cartridge and resets the console (does not start the thread). */
    public synchronized boolean load(GbCartridge cart) {
        try {
            bus.insert(cart);
            bus.reset();
            title = cart.title();
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
        engineThread = new Thread(this::loop, "gamebox-gb-engine");
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

            // Pace to the DMG frame rate with an absolute deadline (park handles
            // sub-ms waits better than sleep). Resync instead of fast-forwarding
            // if we fall far behind (e.g. a GC pause).
            next += FRAME_NANOS;
            long remaining = next - System.nanoTime();
            if (remaining > FRAME_NANOS) {
                remaining = FRAME_NANOS;
                next = System.nanoTime() + FRAME_NANOS;
            }
            while (remaining > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                if (!running.get()) {
                    return;
                }
                remaining = next - System.nanoTime();
            }
            if (-remaining > 4 * FRAME_NANOS) {
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
        bus.joypad().clearAll();
        status = "idle";
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    public void setButton(int button, boolean pressed) {
        bus.joypad().setButton(button, pressed);
        if (pressed) {
            bus.requestInterrupt(4); // Joypad interrupt on press
        }
    }

    public void releaseAllButtons() {
        bus.joypad().clearAll();
    }

    // ─── Game interface ────────────────────────────────────────────────────────

    @Override
    public String id() {
        return "gameboy";
    }

    @Override
    public String displayName() {
        return "Game Boy";
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
        display.blitArgb(frame, GbPpu.WIDTH, GbPpu.HEIGHT);
        return display.toLines();
    }

    public int[] latestArgb() {
        return latestFrame.get();
    }

    public String status() {
        return status;
    }

    /**
     * Renders the latest Game Boy frame into the Minecraft chat box, frame by
     * frame, exactly like the NES core: clear chat, post a fresh client-side
     * system message of a header line + colored Braille rows. MUST run on the
     * client thread.
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
        display.blitArgb(frame, GbPpu.WIDTH, GbPpu.HEIGHT);
        List<net.minecraft.network.chat.Component> lines = display.toColoredComponents();

        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        out.append(net.minecraft.network.chat.Component.literal(
                " §a§lGAME BOY §7[" + title + (paused.get() ? " · paused" : "")
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
        try {
            if (ms > 0) {
                Thread.sleep(ms);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
