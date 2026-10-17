package com.example.gamebox.graphing;

import com.example.gamebox.BrailleDisplay;
import com.example.gamebox.GameBoxConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A 2D function grapher that plots {@code y = f(x)} equations into a
 * {@link BrailleDisplay} and renders them, in color, into the Minecraft chat box
 * using the same Braille technique as DOOM and Super Mario.
 *
 * <h2>Viewport</h2>
 * The view is a Cartesian window centred on {@code (centerX, centerY)} with
 * {@code scale} pixels per unit. Panning changes the centre; zooming changes the
 * scale (like a terminal Desmos). Each equation is sampled once per screen column
 * and connected to the previous sample so continuous curves draw as lines.
 */
public final class GraphingEngine {

    /** One graphed equation with its compiled expression and display color. */
    public static final class Entry {
        final int id;
        String text;
        Relation relation;    // compiled relation (equation + constraints)
        String error;         // parse error, or null
        final int color;      // 0xRRGGBB

        Entry(int id, String text, int color) {
            this.id = id;
            this.color = color;
            setText(text);
        }

        void setText(String t) {
            this.text = t;
            this.relation = Relation.parse(t);
            this.error = relation.ok() ? null : relation.error();
        }

        public int id() {
            return id;
        }

        public String text() {
            return text;
        }

        public int color() {
            return color;
        }

        public String error() {
            return error;
        }

        public Relation relation() {
            return relation;
        }
    }

    // A palette of distinct, bright curve colors (cycled by equation order).
    private static final int[] CURVE_COLORS = {
            0xFF4040, // red
            0x40A0FF, // blue
            0x40E060, // green
            0xFFC020, // amber
            0xC060FF, // purple
            0x20E0E0, // cyan
            0xFF80C0, // pink
            0xA0FF40, // lime
    };

    private static final int AXIS_COLOR = 0xB0B0B0;
    private static final int GRID_COLOR = 0x404040;

    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;

    // Viewport state
    private double centerX = 0.0;
    private double centerY = 0.0;
    private double scale = 12.0; // pixels per unit (zoom)

    private BrailleDisplay display;

    // ─── Equation management ────────────────────────────────────────────────

    /** Adds an equation; returns its assigned id. */
    public synchronized Entry add(String text) {
        Entry e = new Entry(nextId++, text, CURVE_COLORS[entries.size() % CURVE_COLORS.length]);
        entries.add(e);
        return e;
    }

    /** Returns the entry with the given id, or null. */
    public synchronized Entry byId(int id) {
        for (Entry e : entries) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    /** Modifies the equation at id. Returns the entry, or null if id not found. */
    public synchronized Entry modify(int id, String text) {
        Entry e = byId(id);
        if (e != null) {
            e.setText(text);
        }
        return e;
    }

    /** Deletes the equation at id. Returns true if it existed. */
    public synchronized boolean delete(int id) {
        return entries.removeIf(e -> e.id == id);
    }

    /** Deletes every equation (does not reset the viewport). */
    public synchronized void clear() {
        entries.clear();
    }

    /** A snapshot copy of the current equations, in display order. */
    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized int count() {
        return entries.size();
    }

    // ─── Pan / zoom ──────────────────────────────────────────────────────────

    /** Pans by a fraction of the visible width/height (e.g. 0.1 = 10%). */
    public synchronized void panByViewFraction(double fx, double fy) {
        ensureDisplay();
        double unitsW = display.width() / scale;
        double unitsH = display.height() / scale;
        centerX += fx * unitsW;
        centerY += fy * unitsH;
    }

    /** Multiplies the zoom (scale) by factor, clamped to a sane range. */
    public synchronized void zoom(double factor) {
        scale = Math.max(0.5, Math.min(4000.0, scale * factor));
    }

    public synchronized void resetView() {
        centerX = 0;
        centerY = 0;
        scale = 12.0;
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    private void ensureDisplay() {
        int w = GameBoxConfig.get().chatPixelWidth();
        int h = GameBoxConfig.get().chatPixelHeight();
        if (display == null || display.width() != w || display.height() != h) {
            display = new BrailleDisplay(w, h);
        }
    }

    private int screenX(double worldX) {
        return (int) Math.round(display.width() / 2.0 + (worldX - centerX) * scale);
    }

    private int screenY(double worldY) {
        return (int) Math.round(display.height() / 2.0 - (worldY - centerY) * scale);
    }

    private double worldXAt(int sx) {
        return centerX + (sx - display.width() / 2.0) / scale;
    }

    /** Draws axes, grid and all curves into the display. */
    private synchronized void drawFrame() {
        ensureDisplay();
        display.clear();
        int w = display.width();
        int h = display.height();

        // Grid lines at "nice" unit spacing based on the current zoom.
        double step = niceStep();
        int pxStep = (int) Math.round(step * scale);
        if (pxStep >= 4) {
            // Vertical grid lines
            double startX = Math.floor((centerX - (w / 2.0) / scale) / step) * step;
            for (double gx = startX; ; gx += step) {
                int sx = screenX(gx);
                if (sx > w) break;
                if (sx >= 0 && Math.abs(gx) > 1e-9) {
                    for (int y = 0; y < h; y++) display.set(sx, y, true, GRID_COLOR);
                }
            }
            // Horizontal grid lines
            double startY = Math.floor((centerY - (h / 2.0) / scale) / step) * step;
            for (double gy = startY; ; gy += step) {
                int sy = screenY(gy);
                if (sy < 0) break;
                if (sy <= h && Math.abs(gy) > 1e-9) {
                    for (int x = 0; x < w; x++) display.set(x, sy, true, GRID_COLOR);
                }
            }
        }

        // Axes
        int ax = screenX(0);
        int ay = screenY(0);
        if (ay >= 0 && ay < h) {
            for (int x = 0; x < w; x++) display.set(x, ay, true, AXIS_COLOR);
        }
        if (ax >= 0 && ax < w) {
            for (int y = 0; y < h; y++) display.set(ax, y, true, AXIS_COLOR);
        }

        // Numeric tick labels along each axis (tiny 3x5 bitmap digits).
        drawAxisLabels(w, h, step, ax, ay);

        // Curves: plot each relation as an implicit curve. We evaluate
        // F(x,y)=lhs-rhs on the pixel grid and mark any pixel where F changes
        // sign to its right or lower neighbour — this draws y=f(x), x=f(y),
        // 1/y=x, circles and other implicit relations uniformly. Each pixel is
        // masked by the relation's constraints ({…}) evaluated at that point.
        for (Entry e : entries) {
            Relation rel = e.relation;
            if (rel == null || !rel.ok()) {
                continue;
            }
            plotImplicit(rel, e.color, w, h);
        }
    }

    private void plot(int x, int y, int color, int h) {
        if (y >= 0 && y < h) {
            display.set(x, y, true, color);
        }
    }

    /**
     * Plots the zero set of {@code F(x,y)} by sampling F at every pixel-grid
     * vertex and lighting a pixel wherever F changes sign to the right or below
     * (a marching-squares-style edge test), masked by the relation's constraints.
     */
    private void plotImplicit(Relation rel, int color, int w, int h) {
        // Sample F at each vertex of the (w+1) x (h+1) pixel grid.
        double[] row0 = new double[w + 1];
        double[] row1 = new double[w + 1];

        for (int sx = 0; sx <= w; sx++) {
            row0[sx] = safeF(rel, worldXAt(sx), worldYAt(0));
        }
        for (int sy = 1; sy <= h; sy++) {
            double wy = worldYAt(sy);
            for (int sx = 0; sx <= w; sx++) {
                row1[sx] = safeF(rel, worldXAt(sx), wy);
            }
            // Compare the previous scanline (row0 at sy-1) with this one.
            int py = sy - 1;
            for (int sx = 0; sx < w; sx++) {
                double v = row0[sx];
                boolean hit = signChange(v, row0[sx + 1]) || signChange(v, row1[sx]);
                if (hit) {
                    // Mask: draw only where the constraints hold at this point.
                    double wx = worldXAt(sx);
                    double wyy = worldYAt(py);
                    if (rel.constraintsHold(wx, wyy)) {
                        plot(sx, py, color, h);
                    }
                }
            }
            // row1 -> row0 (reuse arrays)
            double[] tmp = row0;
            row0 = row1;
            row1 = tmp;
        }
    }

    private static boolean signChange(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b)) {
            return false;
        }
        // A zero exactly on a vertex counts; otherwise require opposite signs.
        if (a == 0.0) {
            return true;
        }
        return (a < 0) != (b < 0);
    }

    private static double safeF(Relation rel, double x, double y) {
        try {
            return rel.f(x, y);
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private double worldYAt(int sy) {
        return centerY - (sy - display.height() / 2.0) / scale;
    }

    /** Chooses a "nice" grid step (1,2,5 × 10^n) so ~8-16 lines are visible. */
    private double niceStep() {
        double targetPx = 24.0; // aim ~24 px between grid lines
        double unitsPerLine = targetPx / scale;
        double mag = Math.pow(10, Math.floor(Math.log10(unitsPerLine)));
        double norm = unitsPerLine / mag;
        double nice;
        if (norm < 1.5) nice = 1;
        else if (norm < 3) nice = 2;
        else if (norm < 7) nice = 5;
        else nice = 10;
        return nice * mag;
    }

    // ─── Axis labels (tiny 3x5 bitmap font) ────────────────────────────────────

    private static final int LABEL_COLOR = 0xDADADA;

    /**
     * 3x5 bitmap glyphs for digits, '-' and '.' . Each int is 5 rows of 3 bits
     * (top row in the high bits), so bit layout per row is 0b_abc.
     */
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
        f.put('x', new int[]{0b000, 0b101, 0b010, 0b101, 0b000});
        f.put('y', new int[]{0b000, 0b101, 0b010, 0b010, 0b100});
        return f;
    }

    private void drawGlyph(int x, int y, char ch, int color) {
        int[] rows = FONT.get(ch);
        if (rows == null) {
            return;
        }
        for (int ry = 0; ry < 5; ry++) {
            for (int rx = 0; rx < 3; rx++) {
                if ((rows[ry] & (0b100 >> rx)) != 0) {
                    display.set(x + rx, y + ry, true, color);
                }
            }
        }
    }

    /** Draws a short string left-to-right; returns the width in pixels drawn. */
    private int drawText(int x, int y, String s, int color) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            drawGlyph(cx, y, s.charAt(i), color);
            cx += 4; // 3px glyph + 1px gap
        }
        return cx - x;
    }

    /** Formats a tick value compactly (no trailing zeros). */
    private static String fmtTick(double v) {
        if (Math.abs(v) < 1e-9) {
            return "0";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e7) {
            return Long.toString((long) v);
        }
        String s = String.format("%.2f", v);
        // strip trailing zeros / dot
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** Draws numeric labels at grid ticks on both axes, plus x/y end caps. */
    private void drawAxisLabels(int w, int h, double step, int ax, int ay) {
        // Clamp the on-axis label row/column so labels stay on screen even when an
        // axis is scrolled off the view.
        int labelRowY = Math.max(1, Math.min(h - 6, ay + 2));
        int labelColX = Math.max(1, Math.min(w - 10, ax + 2));

        // X-axis tick labels
        double startX = Math.ceil((centerX - (w / 2.0) / scale) / step) * step;
        for (double gx = startX; ; gx += step) {
            int sx = screenX(gx);
            if (sx > w) break;
            if (Math.abs(gx) < 1e-9) continue; // skip origin (labeled by Y axis)
            String label = fmtTick(gx);
            int tx = sx - (label.length() * 4) / 2;
            tx = Math.max(0, Math.min(w - label.length() * 4, tx));
            drawText(tx, labelRowY, label, LABEL_COLOR);
        }

        // Y-axis tick labels
        double startY = Math.ceil((centerY - (h / 2.0) / scale) / step) * step;
        for (double gy = startY; ; gy += step) {
            int sy = screenY(gy);
            if (sy < 0) break;
            if (Math.abs(gy) < 1e-9) continue; // origin
            if (sy > h) continue;
            String label = fmtTick(gy);
            int ty = Math.max(0, Math.min(h - 5, sy - 2));
            drawText(labelColX, ty, label, LABEL_COLOR);
        }

        // Origin "0" and axis name caps in the corners.
        drawText(Math.max(0, Math.min(w - 4, ax + 2)),
                Math.max(0, Math.min(h - 5, ay + 2)), "0", LABEL_COLOR);
        drawText(w - 4, Math.max(0, Math.min(h - 5, ay - 6)), "x", AXIS_COLOR);
        drawText(Math.max(0, Math.min(w - 4, ax + 2)), 0, "y", AXIS_COLOR);
    }


    /** Renders the current graph into the chat box (client thread). */
    public synchronized void renderToChat() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        drawFrame();
        List<net.minecraft.network.chat.Component> lines = display.toColoredComponents();

        net.minecraft.network.chat.MutableComponent out = net.minecraft.network.chat.Component.empty();
        out.append(net.minecraft.network.chat.Component.literal(headerLine()));
        out.append(net.minecraft.network.chat.Component.literal("\n"));
        for (int i = 0; i < lines.size(); i++) {
            out.append(lines.get(i));
            out.append(net.minecraft.network.chat.Component.literal("\n"));
        }
        // A readable scale bar in the bottom-left (normal font, not Braille) so the
        // scale is legible even though the in-canvas tick labels are tiny.
        out.append(net.minecraft.network.chat.Component.literal(scaleBarLine()));

        net.minecraft.client.gui.components.ChatComponent chat = mc.gui.getChat();
        chat.clearMessages(false);
        chat.addClientSystemMessage(out);
    }

    private String headerLine() {
        return String.format(
                " §b§lGRAPHING §7[%d eqn · x=%.2f y=%.2f · %.1f px/u]  §8ESC release · K regrab · ←→↑↓ pan · O/P zoom",
                entries.size(), centerX, centerY, scale);
    }

    /**
     * A big, readable scale bar like {@code |-----10-----|} whose length tracks
     * one "nice" grid step at the current zoom, so the scale is obvious even
     * though the in-canvas Braille tick labels are tiny.
     */
    private String scaleBarLine() {
        double step = niceStep();
        String label = fmtTick(step);
        // Width of one step in chat characters (each Braille cell ≈ 2 px wide),
        // clamped to a sensible readable range.
        int cells = (int) Math.round(step * scale / 2.0);
        int dashes = Math.max(6, Math.min(40, cells));
        int left = (dashes - label.length()) / 2;
        int right = dashes - label.length() - left;
        if (left < 1) left = 1;
        if (right < 1) right = 1;
        StringBuilder bar = new StringBuilder();
        bar.append("§f|§7");
        for (int i = 0; i < left; i++) bar.append('-');
        bar.append("§e").append(label).append("§7");
        for (int i = 0; i < right; i++) bar.append('-');
        bar.append("§f|");
        return " §8scale §r" + bar + " §8= " + label + " unit" + (step == 1 ? "" : "s");
    }
}
