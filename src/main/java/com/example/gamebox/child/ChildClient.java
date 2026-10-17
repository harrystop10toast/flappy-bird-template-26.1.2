package com.example.gamebox.child;

import com.example.gamebox.BrailleDisplay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs <b>inside the hidden child client</b> ({@code -Dgamebox.child=true}). It:
 * <ol>
 *   <li>connects back to the host over {@code 127.0.0.1:<gamebox.child.port>};</li>
 *   <li>every few frames, captures the child's own main render target via
 *       {@link Screenshot#takeScreenshot}, packs it into a {@link BrailleDisplay},
 *       run-length-encodes the {@code (glyph, rgb)} cells and sends a FRAME to the
 *       host (a few KB for a near-static view);</li>
 *   <li>receives INPUT messages from the host and injects them into the child's
 *       own {@link KeyboardHandler} so the game is playable.</li>
 * </ol>
 *
 * <p><b>Status: experimental, unverified on macOS Apple Silicon.</b> It only does
 * anything if the hidden-window render loop actually runs (the fps gate test).
 * Input injection calls the private {@code KeyboardHandler.keyPress} via
 * reflection; that is a dev-run (named-mappings) convenience for the experiment.
 */
public final class ChildClient {

    private static final Logger LOG = LoggerFactory.getLogger("gamebox/child");

    /** Capture resolution → braille cells. Matches host-side decode geometry. */
    private static final int COLS = 128;  // 256 px wide
    private static final int ROWS = 40;   // 160 px tall
    private static final int CAP_W = COLS * 2;
    private static final int CAP_H = ROWS * 4;
    /** Send every Nth frame to keep bandwidth/CPU modest. */
    private static final int FRAME_STRIDE = 2;

    private static ChildClient instance;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private volatile DataInputStream in;
    private final BrailleDisplay display = new BrailleDisplay(CAP_W, CAP_H);
    private int frameCounter;
    private int framesSent;
    private boolean captureEntered;
    private boolean captureCallbackSeen;
    private boolean cursorClickLogged;

    private Method keyPressMethod;   // KeyboardHandler.keyPress(long, int, KeyEvent)
    private Method onButtonMethod;   // MouseHandler.onButton(long, MouseButtonInfo, int)
    private Method onMoveMethod;     // MouseHandler.onMove(long, double, double)

    private ChildClient() {
    }

    /** Starts the child client bridge if this process is the child. Call once from init. */
    public static synchronized void startIfChild() {
        if (!ChildMode.isChild() || instance != null) {
            return;
        }
        int port = ChildMode.hostPort();
        if (port <= 0) {
            LOG.warn("[child] no host port set; child bridge not started");
            return;
        }
        instance = new ChildClient();
        instance.connect(port);
    }

    public static ChildClient get() {
        return instance;
    }

    private void connect(int port) {
        running.set(true);
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                s.setTcpNoDelay(true);
                this.socket = s;
                this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                this.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                LOG.info("[child] connected to host on port {}", port);
                readInputLoop();
            } catch (Throwable e) {
                LOG.error("[child] connection failed: {}", e.toString());
                running.set(false);
            }
        }, "gamebox-child-net");
        t.setDaemon(true);
        t.start();
    }

    // ─── Frame capture + send (called from the HUD element, render thread) ─────

    /** Captures + encodes + sends one frame (throttled by FRAME_STRIDE). */
    public void onRenderFrame() {
        if (!running.get() || out == null) {
            return;
        }
        if ((frameCounter++ % FRAME_STRIDE) != 0) {
            return;
        }
        if (!captureEntered) {
            captureEntered = true;
            LOG.info("[child] first onRenderFrame — attempting capture");
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        var target = mc.getMainRenderTarget();
        if (target == null) {
            return;
        }
        try {
            Screenshot.takeScreenshot(target, img -> {
                try {
                    if (img != null) {
                        if (!captureCallbackSeen) {
                            captureCallbackSeen = true;
                            LOG.info("[child] first screenshot callback: {}x{}",
                                    img.getWidth(), img.getHeight());
                        }
                        int[] argb = scaleToArgb(img, CAP_W, CAP_H);
                        sendFrame(argb);
                    }
                } catch (Throwable t) {
                    LOG.warn("[child] frame encode failed: {}", t.toString());
                } finally {
                    if (img != null) {
                        img.close();
                    }
                }
            });
        } catch (Throwable t) {
            LOG.warn("[child] capture failed: {}", t.toString());
        }
    }

    private synchronized void sendFrame(int[] argb) {
        DataOutputStream o = out;
        if (o == null) {
            return;
        }
        display.clear();
        display.blitArgb(argb, CAP_W, CAP_H);
        int cols = display.cols();
        int rows = display.rows();
        try {
            // Build RLE runs of (glyph, rgb).
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(4096);
            DataOutputStream rle = new DataOutputStream(buf);
            int runCount = 0;
            char curGlyph = 0;
            int curRgb = 0;
            int runLen = 0;
            boolean first = true;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    char g = display.glyphAt(col, row);
                    int rgb = display.cellColorAt(col, row);
                    if (first) {
                        curGlyph = g; curRgb = rgb; runLen = 1; first = false;
                    } else if (g == curGlyph && rgb == curRgb && runLen < 0x7FFF) {
                        runLen++;
                    } else {
                        rle.writeChar(curGlyph); rle.writeInt(curRgb); rle.writeShort(runLen);
                        runCount++;
                        curGlyph = g; curRgb = rgb; runLen = 1;
                    }
                }
            }
            if (!first) {
                rle.writeChar(curGlyph); rle.writeInt(curRgb); rle.writeShort(runLen);
                runCount++;
            }
            rle.flush();

            // Report whether the child currently has a GUI screen open (title,
            // inventory, settings, …) and its GUI-SCALED size — that is the
            // coordinate space a Screen's mouse handlers use, so the host can map a
            // cursor cell straight to a scaled click position (no Retina/raw-pixel
            // or getScaledXPos conversion needed).
            Minecraft mc = Minecraft.getInstance();
            boolean screenOpen = mc != null && mc.screen != null;
            int winW = mc != null ? mc.getWindow().getGuiScaledWidth() : 0;
            int winH = mc != null ? mc.getWindow().getGuiScaledHeight() : 0;

            o.writeByte(FrameProtocol.MSG_FRAME);
            o.writeInt(cols);
            o.writeInt(rows);
            o.writeByte(screenOpen ? 1 : 0);
            o.writeInt(winW);
            o.writeInt(winH);
            o.writeInt(runCount);
            o.write(buf.toByteArray());
            o.flush();
            if (framesSent++ == 0 || (framesSent % 60) == 0) {
                LOG.info("[child] sent frame #{} ({}x{} cells, {} runs)",
                        framesSent, cols, rows, runCount);
            }
        } catch (Throwable t) {
            LOG.debug("[child] send failed: {}", t.toString());
            running.set(false);
        }
    }

    // ─── Input receive + inject ────────────────────────────────────────────────

    private void readInputLoop() {
        DataInputStream i = in;
        try {
            while (running.get() && i != null) {
                byte type = i.readByte();
                switch (type) {
                    case FrameProtocol.MSG_KEY -> {
                        int key = i.readInt();
                        int scancode = i.readInt();
                        int action = i.readInt();
                        int mods = i.readInt();
                        injectKey(key, scancode, action, mods);
                    }
                    case FrameProtocol.MSG_CHAR -> {
                        i.readInt(); // codepoint — char typing injection omitted for now
                        i.readInt();
                    }
                    case FrameProtocol.MSG_MOUSE_BTN -> {
                        int button = i.readInt();
                        int action = i.readInt();
                        int mods = i.readInt();
                        injectMouseButton(button, action, mods);
                    }
                    case FrameProtocol.MSG_MOUSE_MOVE -> {
                        i.readDouble(); i.readDouble();
                    }
                    case FrameProtocol.MSG_LOOK -> {
                        double dYaw = i.readDouble();
                        double dPitch = i.readDouble();
                        injectLook(dYaw, dPitch);
                    }
                    case FrameProtocol.MSG_CURSOR -> {
                        int xRaw = i.readInt();
                        int yRaw = i.readInt();
                        int button = i.readInt();
                        int action = i.readInt();
                        injectCursor(xRaw, yRaw, button, action);
                    }
                    default -> { /* ignore unknown */ }
                }
            }
        } catch (Throwable e) {
            LOG.info("[child] input loop ended: {}", e.toString());
        } finally {
            running.set(false);
        }
    }

    /**
     * Injects a key event into the child's own KeyboardHandler by calling the
     * private {@code keyPress(long window, int action, KeyEvent)} via reflection,
     * on the client thread. Dev-run/experimental.
     */
    private void injectKey(int key, int scancode, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            try {
                KeyboardHandler kh = mc.keyboardHandler;
                if (keyPressMethod == null) {
                    keyPressMethod = KeyboardHandler.class.getDeclaredMethod(
                            "keyPress", long.class, int.class, KeyEvent.class);
                    keyPressMethod.setAccessible(true);
                }
                long window = mc.getWindow().handle();
                KeyEvent ev = new KeyEvent(key, scancode, mods);
                keyPressMethod.invoke(kh, window, action, ev);
            } catch (Throwable t) {
                LOG.debug("[child] key inject failed: {}", t.toString());
            }
        });
    }

    /**
     * Applies a camera look delta — the "artificial mouse" driven by the host's
     * arrow keys. {@link net.minecraft.client.player.LocalPlayer#turn} is exactly
     * what the game calls to rotate the view from real mouse movement, so this is
     * genuine mouse-look, just sourced from arrow keys instead of a cursor.
     */
    private void injectLook(double dYaw, double dPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            try {
                if (mc.player != null) {
                    mc.player.turn(dYaw, dPitch);
                }
            } catch (Throwable t) {
                LOG.debug("[child] look inject failed: {}", t.toString());
            }
        });
    }

    /**
     * Injects a mouse button (left = attack, right = use) into the child's own
     * {@link MouseHandler} via the private {@code onButton(long, MouseButtonInfo,
     * int action)}, mirroring vanilla behaviour.
     */
    private void injectMouseButton(int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            try {
                MouseHandler mh = mc.mouseHandler;
                if (onButtonMethod == null) {
                    onButtonMethod = MouseHandler.class.getDeclaredMethod(
                            "onButton", long.class, MouseButtonInfo.class, int.class);
                    onButtonMethod.setAccessible(true);
                }
                long window = mc.getWindow().handle();
                MouseButtonInfo info = new MouseButtonInfo(button, mods);
                onButtonMethod.invoke(mh, window, info, action);
            } catch (Throwable t) {
                LOG.debug("[child] mouse-button inject failed: {}", t.toString());
            }
        });
    }

    /**
     * Positions the cursor at a raw framebuffer pixel and clicks there — used for
     * menu/GUI screens (title, inventory, settings). Calls {@code onMove} to set
     * the cursor position (so the active screen hovers the right widget), then
     * {@code onButton} to click, mirroring vanilla's move-then-click order.
     */
    private void injectCursor(int xRaw, int yRaw, int button, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            try {
                net.minecraft.client.gui.screens.Screen screen = mc.screen;
                if (screen == null) {
                    return;
                }
                // The host already maps the cursor into GUI-SCALED coordinates
                // (the child reports getGuiScaledWidth/Height each frame), so these
                // are used directly — no raw→scaled conversion.
                double sx = xRaw;
                double sy = yRaw;

                // Hover first so the widget under the cursor highlights.
                screen.mouseMoved(sx, sy);

                if (button >= 0) {
                    MouseButtonInfo info = new MouseButtonInfo(button, 0);
                    MouseButtonEvent ev = new MouseButtonEvent(sx, sy, info);
                    if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                        boolean handled = screen.mouseClicked(ev, false);
                        if (!cursorClickLogged) {
                            cursorClickLogged = true;
                            LOG.info("[child] cursor click at scaled({},{}) guiSize({}x{}) handled={}",
                                    (int) sx, (int) sy,
                                    mc.getWindow().getGuiScaledWidth(),
                                    mc.getWindow().getGuiScaledHeight(), handled);
                        }
                    } else if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
                        screen.mouseReleased(ev);
                    }
                }
            } catch (Throwable t) {
                LOG.warn("[child] cursor inject failed: {}", t.toString());
            }
        });
    }

    /**
     * Downsamples a captured {@link NativeImage} into a {@code dstW}x{@code dstH}
     * ARGB {@code int[]} (0xFFRRGGBB). {@code Screenshot.takeScreenshot} returns an
     * upright image, so no vertical flip is needed. {@link NativeImage#getPixel}
     * returns ABGR (little-endian RGBA).
     */
    static int[] scaleToArgb(NativeImage img, int dstW, int dstH) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[dstW * dstH];
        for (int dy = 0; dy < dstH; dy++) {
            int sy = Math.min(h - 1, dy * h / dstH);
            for (int dx = 0; dx < dstW; dx++) {
                int sx = Math.min(w - 1, dx * w / dstW);
                int abgr = img.getPixel(sx, sy);
                int r = abgr & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                out[dy * dstW + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }
}
