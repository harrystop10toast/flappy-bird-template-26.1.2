package com.example.gamebox.trig;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.GameBoxConfig;

import java.util.List;

/**
 * Renders a solved triangle into a {@link BrailleDisplay} and posts it, in color,
 * into the Minecraft chat box using the same Braille technique as the graphing
 * calculator, DOOM and Super Mario. The triangle is auto-fitted (scaled and
 * centred) to the canvas, its three edges drawn as lines, each vertex marked and
 * labeled (A/B/C), and each side/angle annotated with its numeric value.
 *
 * <p>The engine holds the latest {@link TrigCalculator.SolveResult}; whenever the
 * user changes the knowns via the {@code /trig} command the controller swaps it
 * in and the next tick re-draws. If nothing is solved yet it shows a short hint.
 */
public final class TrigEngine {

    private static final int EDGE_COLOR   = 0x40E0FF; // cyan edges
    private static final int VERT_COLOR   = 0xFFFFFF; // white vertices
    private static final int LABEL_COLOR  = 0xFFD040; // amber vertex labels
    private static final int SIDE_COLOR   = 0x90FF90; // green side labels
    private static final int ANGLE_COLOR  = 0xFF80C0; // pink angle labels
    private static final int HINT_COLOR   = 0x808080;

    /**
     * On-screen width:height of a single buffer pixel. A Braille cell renders in
     * the default font as roughly 6 px wide × 9 px tall, and each cell packs
     * 2 buffer-pixels across and 4 down, so one buffer pixel is about
     * (6/2) px wide × (9/4) px tall → a 3.0 : 2.25 ≈ 4:3 ratio. Shapes must be
     * compressed in X by this factor to avoid shearing (so a right angle looks
     * like a right angle). Tweak if the triangle still looks stretched.
     */
    private static final double PIXEL_ASPECT = (6.0 / 2.0) / (9.0 / 4.0); // ≈1.333

    private BrailleDisplay display;

    private TrigCalculator.SolveResult result;
    private String caseLabel = "";
    private String angleUnit = "degrees";
    private String errorMsg;  // non-null → draw an error hint instead

    // ─── State updates (from the controller) ──────────────────────────────────

    public synchronized void setResult(TrigCalculator.SolveResult r, String angleUnit) {
        this.result = r;
        this.caseLabel = r == null ? "" : r.kind();
        this.angleUnit = angleUnit;
        this.errorMsg = null;
    }

    public synchronized void setError(String message) {
        this.errorMsg = message;
    }

    public synchronized void clear() {
        this.result = null;
        this.errorMsg = null;
    }

    public synchronized boolean hasTriangle() {
        return result != null;
    }

    // ─── Rendering ─────────────────────────────────────────────────────────────

    private void ensureDisplay() {
        int w = GameBoxConfig.get().chatPixelWidth();
        int h = GameBoxConfig.get().chatPixelHeight();
        if (display == null || display.width() != w || display.height() != h) {
            display = new BrailleDisplay(w, h);
        }
    }

    private synchronized void drawFrame() {
        ensureDisplay();
        display.clear();
        int w = display.width();
        int h = display.height();

        if (result == null) {
            drawHint(w, h);
            return;
        }

        TrigCalculator.SolveResult r = result;

        // Model-space triangle vertices (A origin, B on +x, C above).
        double[] vx = {r.ax(), r.bx(), r.cx()};
        double[] vy = {r.ay(), r.by(), r.cy()};

        // Fit transform: bounding box → canvas with a margin for labels.
        double minX = Math.min(vx[0], Math.min(vx[1], vx[2]));
        double maxX = Math.max(vx[0], Math.max(vx[1], vx[2]));
        double minY = Math.min(vy[0], Math.min(vy[1], vy[2]));
        double maxY = Math.max(vy[0], Math.max(vy[1], vy[2]));
        double spanX = Math.max(1e-9, maxX - minX);
        double spanY = Math.max(1e-9, maxY - minY);

        int margin = 10; // px around the triangle for vertex/side labels
        // Correct for non-square buffer pixels: a buffer x-pixel is wider on
        // screen than a y-pixel (PIXEL_ASPECT), so the triangle must be
        // compressed in X. We fit using "screen units" (X already divided by
        // PIXEL_ASPECT) so the shape keeps its true angles.
        double screenSpanX = spanX / PIXEL_ASPECT;
        double sx = (w - 2.0 * margin) / screenSpanX;
        double sy = (h - 2.0 * margin) / spanY;
        double s = Math.min(sx, sy);        // uniform scale in *screen* units
        double scaleX = s / PIXEL_ASPECT;   // buffer-x pixels per model unit
        double scaleY = s;                  // buffer-y pixels per model unit

        // Centre the fitted triangle in the canvas. Model +y is "up", screen +y
        // is "down", so flip Y when projecting.
        double drawnW = spanX * scaleX;
        double drawnH = spanY * scaleY;
        double offX = (w - drawnW) / 2.0 - minX * scaleX;
        double offY = (h - drawnH) / 2.0 + maxY * scaleY; // +maxY because of the flip

        int[] px = new int[3];
        int[] py = new int[3];
        for (int i = 0; i < 3; i++) {
            px[i] = (int) Math.round(offX + vx[i] * scaleX);
            py[i] = (int) Math.round(offY - vy[i] * scaleY);
        }

        // Edges: AB (side c), BC (side a), CA (side b).
        line(px[0], py[0], px[1], py[1], EDGE_COLOR); // A-B
        line(px[1], py[1], px[2], py[2], EDGE_COLOR); // B-C
        line(px[2], py[2], px[0], py[0], EDGE_COLOR); // C-A

        // Vertices as small 2x2 blobs.
        for (int i = 0; i < 3; i++) {
            blob(px[i], py[i], VERT_COLOR);
        }

        TrigCalculator.Triangle t = r.tri();

        // Vertex labels A/B/C, nudged away from the triangle centroid so they sit
        // outside the shape.
        double gx = (px[0] + px[1] + px[2]) / 3.0;
        double gy = (py[0] + py[1] + py[2]) / 3.0;
        drawLabelOutside(px[0], py[0], gx, gy, "A", LABEL_COLOR, w, h);
        drawLabelOutside(px[1], py[1], gx, gy, "B", LABEL_COLOR, w, h);
        drawLabelOutside(px[2], py[2], gx, gy, "C", LABEL_COLOR, w, h);

        // Side-length labels at each edge midpoint, pushed slightly outward.
        drawSideLabel(px[0], py[0], px[1], py[1], gx, gy, "c=" + fmt(t.c()), SIDE_COLOR, w, h);
        drawSideLabel(px[1], py[1], px[2], py[2], gx, gy, "a=" + fmt(t.a()), SIDE_COLOR, w, h);
        drawSideLabel(px[2], py[2], px[0], py[0], gx, gy, "b=" + fmt(t.b()), SIDE_COLOR, w, h);

        // Angle labels just inside each vertex, toward the centroid.
        drawAngleLabel(px[0], py[0], gx, gy, fmt(t.A()), ANGLE_COLOR, w, h);
        drawAngleLabel(px[1], py[1], gx, gy, fmt(t.B()), ANGLE_COLOR, w, h);
        drawAngleLabel(px[2], py[2], gx, gy, fmt(t.C()), ANGLE_COLOR, w, h);
    }

    private void drawHint(int w, int h) {
        // A couple of lines of the tiny font, centred-ish.
        drawText(4, h / 2 - 8, "no triangle yet", HINT_COLOR);
        drawText(4, h / 2 - 1, "e.g. a=3 b=4 C=90", HINT_COLOR);
    }

    // ─── Primitives ─────────────────────────────────────────────────────────

    /** Bresenham line into the display. */
    private void line(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int stepX = x0 < x1 ? 1 : -1, stepY = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            display.set(x0, y0, true, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += stepX;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += stepY;
            }
        }
    }

    private void blob(int x, int y, int color) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                display.set(x + dx, y + dy, true, color);
            }
        }
    }

    /** Places a label just outside a vertex (away from the centroid). */
    private void drawLabelOutside(int vxp, int vyp, double gx, double gy,
                                  String s, int color, int w, int h) {
        double dx = vxp - gx, dy = vyp - gy;
        double len = Math.max(1e-6, Math.hypot(dx, dy));
        int lx = (int) Math.round(vxp + dx / len * 5) - 1;
        int ly = (int) Math.round(vyp + dy / len * 5) - 2;
        drawTextClamped(lx, ly, s, color, w, h);
    }

    /** Places a side label near an edge midpoint, pushed outward from centroid. */
    private void drawSideLabel(int x0, int y0, int x1, int y1,
                               double gx, double gy, String s, int color, int w, int h) {
        double mx = (x0 + x1) / 2.0, my = (y0 + y1) / 2.0;
        double dx = mx - gx, dy = my - gy;
        double len = Math.max(1e-6, Math.hypot(dx, dy));
        int lx = (int) Math.round(mx + dx / len * 4) - (s.length() * 4) / 2;
        int ly = (int) Math.round(my + dy / len * 4) - 2;
        drawTextClamped(lx, ly, s, color, w, h);
    }

    /** Places an angle value just inside a vertex, toward the centroid. */
    private void drawAngleLabel(int vxp, int vyp, double gx, double gy,
                                String s, int color, int w, int h) {
        double dx = gx - vxp, dy = gy - vyp;
        double len = Math.max(1e-6, Math.hypot(dx, dy));
        int lx = (int) Math.round(vxp + dx / len * 9) - (s.length() * 4) / 2;
        int ly = (int) Math.round(vyp + dy / len * 9) - 2;
        drawTextClamped(lx, ly, s, color, w, h);
    }

    // ─── Tiny 3x5 bitmap font (digits, letters used, punctuation) ───────────

    private static final java.util.Map<Character, int[]> FONT = buildFont();

    private static java.util.Map<Character, int[]> buildFont() {
        java.util.Map<Character, int[]> f = new java.util.HashMap<>();
        f.put('0', new int[]{0b111, 0b101, 0b101, 0b101, 0b111});
        f.put('1', new int[]{0b010, 0b110, 0b010, 0b010, 0b111});
        f.put('2', new int[]{0b111, 0b001, 0b111, 0b100, 0b111});
        f.put('3', new int[]{0b111, 0b001, 0b111, 0b001, 0b111});
        f.put('4', new int[]{0b101, 0b101, 0b111, 0b001, 0b001});
        f.put('5', new int[]{0b111, 0b100, 0b111, 0b001, 0b111});
        f.put('6', new int[]{0b111, 0b100, 0b111, 0b101, 0b111});
        f.put('7', new int[]{0b111, 0b001, 0b010, 0b010, 0b010});
        f.put('8', new int[]{0b111, 0b101, 0b111, 0b101, 0b111});
        f.put('9', new int[]{0b111, 0b101, 0b111, 0b001, 0b111});
        f.put('-', new int[]{0b000, 0b000, 0b111, 0b000, 0b000});
        f.put('.', new int[]{0b000, 0b000, 0b000, 0b000, 0b010});
        f.put('=', new int[]{0b000, 0b111, 0b000, 0b111, 0b000});
        f.put(' ', new int[]{0b000, 0b000, 0b000, 0b000, 0b000});
        f.put('A', new int[]{0b010, 0b101, 0b111, 0b101, 0b101});
        f.put('B', new int[]{0b110, 0b101, 0b110, 0b101, 0b110});
        f.put('C', new int[]{0b011, 0b100, 0b100, 0b100, 0b011});
        f.put('a', new int[]{0b000, 0b110, 0b011, 0b101, 0b011});
        f.put('b', new int[]{0b100, 0b100, 0b110, 0b101, 0b110});
        f.put('c', new int[]{0b000, 0b011, 0b100, 0b100, 0b011});
        f.put('e', new int[]{0b000, 0b011, 0b101, 0b110, 0b011});
        f.put('g', new int[]{0b000, 0b011, 0b101, 0b011, 0b110});
        f.put('h', new int[]{0b100, 0b100, 0b110, 0b101, 0b101});
        f.put('i', new int[]{0b010, 0b000, 0b010, 0b010, 0b010});
        f.put('l', new int[]{0b010, 0b010, 0b010, 0b010, 0b010});
        f.put('n', new int[]{0b000, 0b110, 0b101, 0b101, 0b101});
        f.put('o', new int[]{0b000, 0b010, 0b101, 0b101, 0b010});
        f.put('r', new int[]{0b000, 0b011, 0b100, 0b100, 0b100});
        f.put('t', new int[]{0b010, 0b111, 0b010, 0b010, 0b011});
        f.put('y', new int[]{0b000, 0b101, 0b101, 0b011, 0b110});
        return f;
    }

    private void drawGlyph(int x, int y, char ch, int color) {
        int[] rows = FONT.get(ch);
        if (rows == null) {
            rows = FONT.get(' ');
        }
        for (int ry = 0; ry < 5; ry++) {
            for (int rx = 0; rx < 3; rx++) {
                if ((rows[ry] & (0b100 >> rx)) != 0) {
                    display.set(x + rx, y + ry, true, color);
                }
            }
        }
    }

    private void drawText(int x, int y, String s, int color) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            drawGlyph(cx, y, s.charAt(i), color);
            cx += 4;
        }
    }

    private void drawTextClamped(int x, int y, String s, int color, int w, int h) {
        int tw = s.length() * 4;
        x = Math.max(0, Math.min(w - tw, x));
        y = Math.max(0, Math.min(h - 5, y));
        drawText(x, y, s, color);
    }

    /** Compact numeric formatting shared with the controller's style. */
    private static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e6) {
            return Long.toString((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    // ─── Chat posting ───────────────────────────────────────────────────────

    /** Renders the current triangle into the chat box (client thread). */
    public synchronized void renderToChat() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        drawFrame();
        List<net.minecraft.network.chat.Component> lines = display.toColoredComponents();

        net.minecraft.network.chat.MutableComponent out =
                net.minecraft.network.chat.Component.empty();
        out.append(net.minecraft.network.chat.Component.literal(headerLine()));
        out.append(net.minecraft.network.chat.Component.literal("\n"));
        for (net.minecraft.network.chat.Component line : lines) {
            out.append(line);
            out.append(net.minecraft.network.chat.Component.literal("\n"));
        }
        out.append(net.minecraft.network.chat.Component.literal(footerLine()));

        net.minecraft.client.gui.components.ChatComponent chat = mc.gui.getChat();
        chat.clearMessages(false);
        chat.addClientSystemMessage(out);
    }

    private String headerLine() {
        if (errorMsg != null) {
            return " §c§lTRIG §7— §c" + errorMsg;
        }
        if (result == null) {
            return " §b§lTRIG §7[no triangle]  §8K focus · Esc release · type /trig a=3 b=4 c=5";
        }
        return String.format(
                " §b§lTRIG §7[%s · %s]  §8K focus · Esc release · /trig <key=val …> · /trig clear",
                caseLabel, angleUnit);
    }

    private String footerLine() {
        if (result == null) {
            return " §8Give any 3 parts incl. a side: §7sides §aa b c§8, angles §dA B C";
        }
        TrigCalculator.Triangle t = result.tri();
        String amb = (t.ambiguous() && t.altB() != null)
                ? "  §6(ambiguous: B' possible)" : "";
        return String.format(
                " §aa=%s b=%s c=%s  §dA=%s B=%s C=%s  §7area=%s%s",
                fmt(t.a()), fmt(t.b()), fmt(t.c()),
                fmt(t.A()), fmt(t.B()), fmt(t.C()), fmt(result.area()), amb);
    }
}
