package com.example.gamebox;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * A 1-bit framebuffer that is rendered as Unicode Braille characters
 * (U+2800 .. U+28FF). Each Braille glyph packs a 2x4 block of pixels, so a
 * W x H pixel buffer collapses to a grid of (W/2) columns x (H/4) rows of glyphs.
 *
 * <h2>Color</h2>
 * A Braille glyph is 1-bit per pixel, so true per-pixel color is impossible.
 * Instead each <em>cell</em> (one glyph = a 2x4 block) carries a single RGB color.
 * {@link #blitArgb(int[], int, int)} records the average color of the source
 * pixels covered by each cell, and callers can also set a cell's color explicitly
 * with {@link #setCellColor(int, int, int)}. {@link #toColoredComponents()} emits
 * one {@link Component} per row, split into colored runs.
 *
 * <h2>Braille bit map (per the GameBox spec)</h2>
 * Within one 2x4 cell, a pixel at (row, col) maps to the following bit:
 * <pre>
 *   (0,0)=0x01  (0,1)=0x08
 *   (1,0)=0x02  (1,1)=0x10
 *   (2,0)=0x04  (2,1)=0x20
 *   (3,0)=0x40  (3,1)=0x80
 * </pre>
 * The glyph code point is {@code 0x2800 + bits}.
 *
 * <h2>Dithering</h2>
 * {@link #setGray(int, int, int)} and {@link #blitArgb(int[], int, int)} apply a
 * 4x4 Bayer ordered-dithering threshold so continuous-tone (e.g. DOOM) frames
 * reduce cleanly to 1-bit before packing.
 */
public final class BrailleDisplay {

    /** Default pixel buffer width. */
    public static final int DEFAULT_WIDTH = 96;
    /** Default pixel buffer height. */
    public static final int DEFAULT_HEIGHT = 60;

    /** Default glyph color when a cell has no explicit color. */
    private static final int DEFAULT_RGB = 0xFFFFFF;

    private static final int BRAILLE_BASE = 0x2800;

    private static final int[][] BIT = {
            {0x01, 0x08}, // row 0
            {0x02, 0x10}, // row 1
            {0x04, 0x20}, // row 2
            {0x40, 0x80}, // row 3
    };

    private static final int[][] BAYER4 = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5},
    };

    private final int width;
    private final int height;
    private final int cols;
    private final int rows;
    private final boolean[] pixels;
    /** Per-cell RGB (0xRRGGBB); -1 means "unset / use default". */
    private final int[] cellColor;

    public BrailleDisplay() {
        this(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public BrailleDisplay(int width, int height) {
        this.width = ((width + 1) / 2) * 2;
        this.height = ((height + 3) / 4) * 4;
        this.cols = this.width / 2;
        this.rows = this.height / 4;
        this.pixels = new boolean[this.width * this.height];
        this.cellColor = new int[this.cols * this.rows];
        java.util.Arrays.fill(this.cellColor, -1);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    /** Clears all pixels and cell colors. */
    public void clear() {
        java.util.Arrays.fill(pixels, false);
        java.util.Arrays.fill(cellColor, -1);
    }

    public void set(int x, int y, boolean on) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        pixels[y * width + x] = on;
    }

    /** Sets a pixel and tints the cell it belongs to. */
    public void set(int x, int y, boolean on, int rgb) {
        set(x, y, on);
        if (on) {
            setCellColor(x / 2, y / 4, rgb);
        }
    }

    public boolean get(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false;
        }
        return pixels[y * width + x];
    }

    public void fillRect(int x, int y, int w, int h, boolean on) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                set(xx, yy, on);
            }
        }
    }

    /** Filled rectangle that also tints every covered cell. */
    public void fillRect(int x, int y, int w, int h, boolean on, int rgb) {
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
                set(xx, yy, on, rgb);
            }
        }
    }

    /** Sets the RGB (0xRRGGBB) color for the cell at glyph coordinates (cx, cy). */
    public void setCellColor(int cx, int cy, int rgb) {
        if (cx < 0 || cx >= cols || cy < 0 || cy >= rows) {
            return;
        }
        cellColor[cy * cols + cx] = rgb & 0xFFFFFF;
    }

    public void setGray(int x, int y, int gray) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        int threshold = (BAYER4[y & 3][x & 3] * 255) / 16;
        pixels[y * width + x] = gray > threshold;
    }

    public void blitGray(int[] gray, int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        for (int y = 0; y < height; y++) {
            int sy = (y * srcH) / height;
            for (int x = 0; x < width; x++) {
                int sx = (x * srcW) / width;
                int g = gray[sy * srcW + sx] & 0xFF;
                setGray(x, y, g);
            }
        }
    }

    /**
     * Blits a 32-bit ARGB source image: luminance-dithers each pixel on/off AND
     * records a per-cell average color so the frame renders in color. Used for
     * DOOM's 320x200 frame.
     */
    public void blitArgb(int[] argb, int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0) {
            return;
        }
        // Accumulate per-cell color sums (only over lit pixels so colors stay vivid).
        long[] rSum = new long[cols * rows];
        long[] gSum = new long[cols * rows];
        long[] bSum = new long[cols * rows];
        int[] count = new int[cols * rows];

        for (int y = 0; y < height; y++) {
            int sy = (y * srcH) / height;
            for (int x = 0; x < width; x++) {
                int sx = (x * srcW) / width;
                int c = argb[sy * srcW + sx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                int threshold = (BAYER4[y & 3][x & 3] * 255) / 16;
                boolean on = lum > threshold;
                pixels[y * width + x] = on;
                if (on) {
                    int ci = (y / 4) * cols + (x / 2);
                    rSum[ci] += r;
                    gSum[ci] += g;
                    bSum[ci] += b;
                    count[ci]++;
                }
            }
        }
        for (int i = 0; i < cols * rows; i++) {
            if (count[i] > 0) {
                int r = (int) (rSum[i] / count[i]);
                int g = (int) (gSum[i] / count[i]);
                int b = (int) (bSum[i] / count[i]);
                // Boost toward full brightness so dark DOOM scenes stay legible.
                cellColor[i] = boost(r, g, b);
            } else {
                cellColor[i] = -1;
            }
        }
    }

    /** Scales a color up so its brightest channel is near full, preserving hue. */
    private static int boost(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        if (max <= 0) {
            return 0x202020;
        }
        int scale = Math.min(255, 200 + max); // lift midtones
        r = Math.min(255, r * scale / max);
        g = Math.min(255, g * scale / max);
        b = Math.min(255, b * scale / max);
        return (r << 16) | (g << 8) | b;
    }

    /** Mono packing (no color). */
    public List<String> toLines() {
        List<String> lines = new ArrayList<>(rows);
        StringBuilder sb = new StringBuilder(cols);
        for (int row = 0; row < rows; row++) {
            sb.setLength(0);
            int baseY = row * 4;
            for (int col = 0; col < cols; col++) {
                sb.append(glyph(col, baseY));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    /**
     * Colored packing: returns one {@link Component} per row. Consecutive cells
     * of the same color are merged into a single styled run to keep the component
     * small.
     */
    public List<Component> toColoredComponents() {
        List<Component> out = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            int baseY = row * 4;
            MutableComponent line = Component.empty();
            StringBuilder run = new StringBuilder();
            int runColor = Integer.MIN_VALUE;
            for (int col = 0; col < cols; col++) {
                char ch = glyph(col, baseY);
                int rgb = cellColor[row * cols + col];
                if (rgb < 0) {
                    rgb = DEFAULT_RGB;
                }
                if (rgb != runColor) {
                    flushRun(line, run, runColor);
                    runColor = rgb;
                }
                run.append(ch);
            }
            flushRun(line, run, runColor);
            out.add(line);
        }
        return out;
    }

    private static void flushRun(MutableComponent line, StringBuilder run, int rgb) {
        if (run.length() == 0) {
            return;
        }
        line.append(Component.literal(run.toString())
                .withStyle(Style.EMPTY.withColor(rgb)));
        run.setLength(0);
    }

    private char glyph(int col, int baseY) {
        int baseX = col * 2;
        int bits = 0;
        for (int cy = 0; cy < 4; cy++) {
            for (int cx = 0; cx < 2; cx++) {
                if (get(baseX + cx, baseY + cy)) {
                    bits |= BIT[cy][cx];
                }
            }
        }
        return (char) (BRAILLE_BASE + bits);
    }

    public static char packCell(boolean[][] cell) {
        int bits = 0;
        for (int cy = 0; cy < 4; cy++) {
            for (int cx = 0; cx < 2; cx++) {
                if (cell[cy][cx]) {
                    bits |= BIT[cy][cx];
                }
            }
        }
        return (char) (BRAILLE_BASE + bits);
    }

    /** The packed Braille glyph for cell (col, row). For per-cell wire encoding. */
    public char glyphAt(int col, int row) {
        return glyph(col, row * 4);
    }

    /** The RGB (0xRRGGBB) of cell (col, row), or white if unset. For wire encoding. */
    public int cellColorAt(int col, int row) {
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return DEFAULT_RGB;
        }
        int rgb = cellColor[row * cols + col];
        return rgb < 0 ? DEFAULT_RGB : rgb;
    }
}
