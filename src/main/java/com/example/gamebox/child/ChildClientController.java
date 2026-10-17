package com.example.gamebox.child;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HOST side of the experimental second-client path. Launches a second copy of the
 * game as a child process (hidden window via {@code WindowMixin}), accepts its
 * localhost socket connection, decodes the braille frames it streams, renders them
 * into chat, and forwards keyboard input back to it.
 *
 * <p><b>Status: experimental and UNVERIFIED on macOS Apple Silicon.</b> It is
 * gated on the fps gate test — if a hidden GLFW window does not keep rendering in
 * the child, no frames will arrive and you should use the live mirror instead
 * ({@code /game minecraft}). The child relaunch command is reconstructed from the
 * current process and may need tailoring to your exact launcher/classpath.
 */
public final class ChildClientController {

    private static final Logger LOG = LoggerFactory.getLogger("gamebox/child-host");

    private static final int CHILD_W = 320;
    private static final int CHILD_H = 180;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile Process childProcess;
    private volatile Socket childSocket;
    private volatile DataOutputStream toChild;

    /** Latest decoded frame: cols, rows, and per-cell glyph+rgb. */
    private final AtomicReference<Decoded> latest = new AtomicReference<>();
    private volatile String status = "idle";

    // Host-side cursor position (in Braille cells) for menu/GUI interaction.
    private volatile int cursorCol = -1;
    private volatile int cursorRow = -1;

    /** Sets the host cursor cell (clamped) — called by the input screen. */
    public void setCursor(int col, int row) {
        Decoded d = latest.get();
        int cols = d == null ? 128 : d.cols();
        int rows = d == null ? 40 : d.rows();
        this.cursorCol = Math.max(0, Math.min(cols - 1, col));
        this.cursorRow = Math.max(0, Math.min(rows - 1, row));
    }

    public int cursorCol() {
        return cursorCol;
    }

    public int cursorRow() {
        return cursorRow;
    }

    public boolean isActive() {
        return active.get();
    }

    public boolean ownsChat() {
        return active.get();
    }

    // ─── Start / stop ───────────────────────────────────────────────────────

    public synchronized String start() {
        if (active.get()) {
            return "Second Minecraft (child) is already starting/running.";
        }
        try {
            server = new ServerSocket(0); // ephemeral localhost port
            server.setSoTimeout(0);
            int port = server.getLocalPort();
            active.set(true);
            status = "launching child…";

            acceptLoopAsync();
            launchChild(port);

            return "Launching a second REAL Minecraft as a hidden child process. "
                    + "Port " + port
                    + ". Press §eK§7 to grab input, §eEsc§7 to release. "
                    + "/game stop to end.";
        } catch (Throwable t) {
            active.set(false);
            cleanup();
            return "Failed to start child: " + t;
        }
    }

    public synchronized String stop() {
        if (!active.get()) {
            return "Second Minecraft (child) is not running.";
        }
        active.set(false);
        status = "idle";
        latest.set(null);
        cleanup();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(Component.literal(
                        "§b[GameBox] §7Second Minecraft stopped."));
            });
        }
        return "Second Minecraft (child) stopped.";
    }

    private void cleanup() {
        try {
            if (childSocket != null) {
                childSocket.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable ignored) {
        }
        if (childProcess != null && childProcess.isAlive()) {
            childProcess.destroy();
        }
        childSocket = null;
        toChild = null;
        server = null;
        childProcess = null;
    }

    // ─── Child process launch ──────────────────────────────────────────────────

    private void launchChild(int port) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        // Separate game dir so the child doesn't fight the host over files/locks.
        Path childGameDir = FabricLoader.getInstance().getGameDir().resolve("gamebox-child");
        childGameDir.toFile().mkdirs();
        writeChildOptions(childGameDir);
        // The child uses its own gameDir, so its mods/ is empty — but GameBox
        // declares a dependency on 'increased-chat-height' which lives in the
        // host's run/mods. Mirror the host's mods/ into the child's so its
        // dependencies resolve. (GameBox itself loads from the dev classpath.)
        copyHostMods(childGameDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        // macOS requires GLFW/AppKit on the first thread.
        cmd.add("-XstartOnFirstThread");
        cmd.add("-Xmx1G");

        // Carry over the host JVM's own -D/-X input arguments (Loom + Fabric dev
        // launch inject -Dfabric.*, -Djava.library.path for GLFW natives,
        // log4j config, DLI props, etc.). Replaying them is far more robust than
        // reconstructing the Fabric dev launch by hand (which is what caused the
        // "missing fabric.dli.main" failure). We skip the agent/-XstartOnFirstThread
        // we already added and anything that would re-point stdout.
        for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments()) {
            if (arg.startsWith("-XstartOnFirstThread") || arg.startsWith("-Xmx")
                    || arg.startsWith("-agentlib") || arg.startsWith("-javaagent")) {
                continue;
            }
            // Don't inherit a child flag if somehow present.
            if (arg.contains(ChildMode.PROP_CHILD) || arg.contains(ChildMode.PROP_PORT)) {
                continue;
            }
            cmd.add(arg);
        }

        // Our own markers.
        cmd.add("-D" + ChildMode.PROP_CHILD + "=true");
        cmd.add("-D" + ChildMode.PROP_PORT + "=" + port);

        // Also forward live system properties the dev launch relies on but which
        // may have been set *after* JVM start (so they're not in getInputArguments):
        // all fabric.* props, the natives path, and the log4j config. These are the
        // ones Knot/Loom need in a dev run.
        forwardLiveProperty(cmd, "fabric.development");
        forwardLiveProperty(cmd, "fabric.dli.config");
        forwardLiveProperty(cmd, "fabric.gameJarPath");
        forwardLiveProperty(cmd, "fabric.classPathGroups");
        forwardLiveProperty(cmd, "fabric.remapClasspathFile");
        forwardLiveProperty(cmd, "log4j.configurationFile");
        forwardLiveProperty(cmd, "log4j2.formatMsgNoLookups");
        forwardLiveProperty(cmd, "java.library.path");
        forwardLiveProperty(cmd, "org.lwjgl.librarypath");
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("fabric.") && System.getProperty(name) != null) {
                // Avoid duplicating ones we added explicitly above.
                String flag = "-D" + name + "=";
                boolean already = cmd.stream().anyMatch(a -> a.startsWith(flag));
                if (!already) {
                    cmd.add(flag + System.getProperty(name));
                }
            }
        }

        cmd.add("-cp");
        cmd.add(classpath);

        // Launch Fabric's Knot client directly, bypassing the dev-launch-injector
        // (DLI) bootstrap — DLI needs a -Dfabric.dli.main we don't control, whereas
        // KnotClient is the actual game entry point DLI ultimately hands off to.
        cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");

        // Minecraft client args.
        cmd.add("--username");
        cmd.add("GameBoxChild");
        cmd.add("--gameDir");
        cmd.add(childGameDir.toString());
        cmd.add("--width");
        cmd.add(String.valueOf(CHILD_W));
        cmd.add("--height");
        cmd.add(String.valueOf(CHILD_H));
        cmd.add("--accessToken");
        cmd.add("0");
        cmd.add("--version");
        cmd.add("gamebox-child");
        // Loom's dev client needs the asset index/dir; forward the host's if known.
        appendAssetArgs(cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Pipe the child's stdout/log to a file so you can read [GAMEBOX-GATE] fps.
        File logFile = childGameDir.resolve("child.log").toFile();
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        LOG.info("[host] launching child ({} args); log → {}", cmd.size(), logFile);
        childProcess = pb.start();
        status = "child launched (log: " + logFile.getName() + ")";
    }

    /** Adds {@code -Dname=value} from the live JVM if the property is set. */
    private static void forwardLiveProperty(List<String> cmd, String name) {
        String v = System.getProperty(name);
        if (v != null && !v.isEmpty()) {
            cmd.add("-D" + name + "=" + v);
        }
    }

    /**
     * Mirrors the host's {@code <gameDir>/mods} jars into the child's
     * {@code mods/} so the child resolves GameBox's declared dependencies (e.g.
     * {@code increased-chat-height}). Only copies regular {@code .jar} files; skips
     * ones already present/identical.
     */
    private void copyHostMods(Path childGameDir) {
        try {
            Path hostMods = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (!java.nio.file.Files.isDirectory(hostMods)) {
                return;
            }
            Path childMods = childGameDir.resolve("mods");
            java.nio.file.Files.createDirectories(childMods);
            try (var stream = java.nio.file.Files.list(hostMods)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(java.nio.file.Files::isRegularFile)
                        .forEach(p -> {
                            try {
                                Path dst = childMods.resolve(p.getFileName().toString());
                                if (!java.nio.file.Files.exists(dst)
                                        || java.nio.file.Files.size(dst) != java.nio.file.Files.size(p)) {
                                    java.nio.file.Files.copy(p, dst,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            } catch (Throwable t) {
                                LOG.warn("[host] could not copy mod {}: {}", p, t.toString());
                            }
                        });
            }
            LOG.info("[host] mirrored host mods → {}", childMods);
        } catch (Throwable t) {
            LOG.warn("[host] copyHostMods failed: {}", t.toString());
        }
    }

    /**
     * Forwards {@code --assetIndex}/{@code --assetsDir} from Loom's dev launch
     * config so the child can load textures. Read from
     * {@code .gradle/loom-cache/launch.cfg} if present; otherwise omitted (the
     * child still boots, just without assets resolved the same way).
     */
    private void appendAssetArgs(List<String> cmd) {
        try {
            Path cfg = FabricLoader.getInstance().getGameDir().getParent()
                    .resolve(".gradle/loom-cache/launch.cfg");
            if (!java.nio.file.Files.isReadable(cfg)) {
                return;
            }
            List<String> lines = java.nio.file.Files.readAllLines(cfg);
            String assetIndex = null;
            String assetsDir = null;
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i).trim();
                if (s.equals("--assetIndex") && i + 1 < lines.size()) {
                    assetIndex = lines.get(i + 1).trim();
                } else if (s.equals("--assetsDir") && i + 1 < lines.size()) {
                    assetsDir = lines.get(i + 1).trim();
                }
            }
            if (assetIndex != null) {
                cmd.add("--assetIndex");
                cmd.add(assetIndex);
            }
            if (assetsDir != null) {
                cmd.add("--assetsDir");
                cmd.add(assetsDir);
            }
        } catch (Throwable t) {
            LOG.warn("[host] could not read asset args: {}", t.toString());
        }
    }

    /** Minimal child options.txt: no pause on lost focus, vsync off, low fps/render. */
    private void writeChildOptions(Path childGameDir) {
        try {
            Path opts = childGameDir.resolve("options.txt");
            String content = String.join("\n",
                    "pauseOnLostFocus:false",
                    "enableVsync:false",
                    "maxFps:30",
                    "renderDistance:5",
                    "simulationDistance:5",
                    "") ;
            java.nio.file.Files.writeString(opts, content);
        } catch (Throwable t) {
            LOG.warn("[host] could not write child options.txt: {}", t.toString());
        }
    }

    // ─── Socket accept + frame decode ──────────────────────────────────────────

    private void acceptLoopAsync() {
        Thread t = new Thread(() -> {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                childSocket = s;
                toChild = new DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream()));
                DataInputStream in = new DataInputStream(
                        new java.io.BufferedInputStream(s.getInputStream()));
                LOG.info("[host] child connected");
                status = "child connected — receiving frames";
                readFrames(in);
            } catch (Throwable e) {
                if (active.get()) {
                    LOG.info("[host] accept/read ended: {}", e.toString());
                    status = "disconnected: " + e.getClass().getSimpleName();
                }
            }
        }, "gamebox-host-net");
        t.setDaemon(true);
        t.start();
    }

    private void readFrames(DataInputStream in) throws Exception {
        while (active.get()) {
            byte type = in.readByte();
            if (type != FrameProtocol.MSG_FRAME) {
                continue;
            }
            int cols = in.readInt();
            int rows = in.readInt();
            boolean screenOpen = in.readByte() != 0;
            int winW = in.readInt();
            int winH = in.readInt();
            int runCount = in.readInt();
            char[] glyphs = new char[cols * rows];
            int[] colors = new int[cols * rows];
            int p = 0;
            for (int r = 0; r < runCount; r++) {
                char glyph = in.readChar();
                int rgb = in.readInt();
                int len = in.readShort() & 0xFFFF;
                for (int k = 0; k < len && p < glyphs.length; k++, p++) {
                    glyphs[p] = glyph;
                    colors[p] = rgb;
                }
            }
            latest.set(new Decoded(cols, rows, glyphs, colors, screenOpen, winW, winH));
        }
    }

    // ─── Chat rendering (client thread) + input forward ────────────────────────

    public void renderToChat() {
        if (!active.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        MutableComponent out = Component.empty();
        Decoded d = latest.get();
        boolean cursorMode = d != null && d.screenOpen();
        String mode = cursorMode ? "§dcursor" : "§agameplay";
        out.append(Component.literal(" §b§lMINECRAFT #2 §7[" + status + " · " + mode
                + "§7]  §8K focus · Esc release · " + (cursorMode
                        ? "arrows move cursor · click = select"
                        : "arrows look · click = attack/use")));
        out.append(Component.literal("\n"));

        if (d == null) {
            out.append(Component.literal("§7waiting for child frames… "
                    + "§8(check gamebox-child/child.log for [GAMEBOX-GATE] fps)"));
        } else {
            int curCol = cursorMode ? cursorCol : -1;
            int curRow = cursorMode ? cursorRow : -1;
            for (int row = 0; row < d.rows; row++) {
                MutableComponent line = Component.empty();
                StringBuilder run = new StringBuilder();
                int runColor = Integer.MIN_VALUE;
                for (int col = 0; col < d.cols; col++) {
                    int idx = row * d.cols + col;
                    char g = d.glyphs[idx];
                    int rgb = d.colors[idx];
                    // Draw the cursor as a bright full block overriding this cell.
                    if (row == curRow && col == curCol) {
                        g = '\u28FF';          // full braille block
                        rgb = 0xFF2BE0;        // vivid magenta
                    }
                    if (rgb != runColor) {
                        if (run.length() > 0) {
                            line.append(Component.literal(run.toString())
                                    .withStyle(Style.EMPTY.withColor(runColor)));
                            run.setLength(0);
                        }
                        runColor = rgb;
                    }
                    run.append(g);
                }
                if (run.length() > 0) {
                    line.append(Component.literal(run.toString())
                            .withStyle(Style.EMPTY.withColor(runColor)));
                }
                out.append(line);
                if (row < d.rows - 1) {
                    out.append(Component.literal("\n"));
                }
            }
        }
        mc.gui.getChat().clearMessages(false);
        mc.gui.getChat().addClientSystemMessage(out);
    }

    /** Forwards a key event to the child (called from the host input screen). */
    public void forwardKey(int key, int scancode, int action, int mods) {
        DataOutputStream o = toChild;
        if (o == null) {
            return;
        }
        try {
            synchronized (o) {
                o.writeByte(FrameProtocol.MSG_KEY);
                o.writeInt(key);
                o.writeInt(scancode);
                o.writeInt(action);
                o.writeInt(mods);
                o.flush();
            }
        } catch (Throwable t) {
            LOG.debug("[host] forwardKey failed: {}", t.toString());
        }
    }

    /** Forwards a camera look delta (artificial mouse) to the child. */
    public void forwardLook(double dYaw, double dPitch) {
        DataOutputStream o = toChild;
        if (o == null) {
            return;
        }
        try {
            synchronized (o) {
                o.writeByte(FrameProtocol.MSG_LOOK);
                o.writeDouble(dYaw);
                o.writeDouble(dPitch);
                o.flush();
            }
        } catch (Throwable t) {
            LOG.debug("[host] forwardLook failed: {}", t.toString());
        }
    }

    /** Forwards a mouse button (attack/use) to the child. */
    public void forwardMouseButton(int button, int action, int mods) {
        DataOutputStream o = toChild;
        if (o == null) {
            return;
        }
        try {
            synchronized (o) {
                o.writeByte(FrameProtocol.MSG_MOUSE_BTN);
                o.writeInt(button);
                o.writeInt(action);
                o.writeInt(mods);
                o.flush();
            }
        } catch (Throwable t) {
            LOG.debug("[host] forwardMouseButton failed: {}", t.toString());
        }
    }

    /** Whether the child currently has a GUI screen open (→ host uses cursor mode). */
    public boolean isChildScreenOpen() {
        Decoded d = latest.get();
        return d != null && d.screenOpen();
    }

    /** The decoded frame's cell grid cols, or 0. */
    public int frameCols() {
        Decoded d = latest.get();
        return d == null ? 0 : d.cols();
    }

    public int frameRows() {
        Decoded d = latest.get();
        return d == null ? 0 : d.rows();
    }

    /**
     * Forwards an absolute cursor position + click, mapping a host cursor cell
     * (col,row in the Braille grid) to the child's raw framebuffer pixel. Pass
     * {@code button < 0} to move only (no click).
     */
    public void forwardCursorCell(int col, int row, int button, int action) {
        Decoded d = latest.get();
        if (d == null) {
            return;
        }
        int cols = Math.max(1, d.cols());
        int rows = Math.max(1, d.rows());
        // Default an unset cursor to the centre so a click without prior movement
        // still lands somewhere sensible.
        if (col < 0) {
            col = cols / 2;
        }
        if (row < 0) {
            row = rows / 2;
        }
        col = Math.max(0, Math.min(cols - 1, col));
        row = Math.max(0, Math.min(rows - 1, row));
        int winW = d.winW() > 0 ? d.winW() : cols * 2;
        int winH = d.winH() > 0 ? d.winH() : rows * 4;
        // Map the cell centre to the child's GUI-SCALED coordinate space (winW/winH
        // are the child's getGuiScaledWidth/Height), which is what Screen mouse
        // handlers consume directly.
        int xRaw = (int) ((col + 0.5) / cols * winW);
        int yRaw = (int) ((row + 0.5) / rows * winH);
        DataOutputStream o = toChild;
        if (o == null) {
            return;
        }
        try {
            synchronized (o) {
                o.writeByte(FrameProtocol.MSG_CURSOR);
                o.writeInt(xRaw);
                o.writeInt(yRaw);
                o.writeInt(button);
                o.writeInt(action);
                o.flush();
            }
        } catch (Throwable t) {
            LOG.debug("[host] forwardCursor failed: {}", t.toString());
        }
    }

    private record Decoded(int cols, int rows, char[] glyphs, int[] colors,
                           boolean screenOpen, int winW, int winH) {
    }
}
