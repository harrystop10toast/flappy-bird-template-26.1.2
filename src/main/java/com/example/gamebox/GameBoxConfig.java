package com.example.gamebox;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persisted GameBox settings: the game chat canvas size, set by <b>width in
 * pixels</b> via {@code /game size <width>}. The height follows a fixed
 * <b>448:280</b> (16:10) pixel ratio, so {@code height = round(width * 280/448)}.
 *
 * <p>Width range is {@value #MIN_PX_W}..{@value #MAX_PX_W} px; {@value #MAX_PX_W}
 * x {@value #MAX_PX_H} px is the largest that fully renders before chat wraps.
 * Pixels map to Braille cells as 2px wide x 4px tall. Stored at
 * {@code config/gamebox/gamebox.properties}.
 */
public final class GameBoxConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("gamebox");

    /** Width range in pixels. */
    public static final int MIN_PX_W = 48;
    public static final int MAX_PX_W = 448;

    /** Locked pixel ratio 448:280 (= 16:10). */
    public static final int RATIO_W = 448;
    public static final int RATIO_H = 280;

    /** Derived max height in pixels (448 * 280/448 = 280). */
    public static final int MAX_PX_H = heightForWidth(MAX_PX_W);

    public static final int DEFAULT_PX_W = 448;

    private static final GameBoxConfig INSTANCE = new GameBoxConfig();

    /** Canonical stored value: DOOM canvas width in pixels. */
    private int widthPx = clampWidth(DEFAULT_PX_W);

    private GameBoxConfig() {
        load();
    }

    public static GameBoxConfig get() {
        return INSTANCE;
    }

    public int chatPixelWidth() {
        return widthPx;
    }

    public int chatPixelHeight() {
        return heightForWidth(widthPx);
    }

    /** Braille columns (2 px per cell). */
    public int chatCols() {
        return widthPx / 2;
    }

    /** Braille rows (4 px per cell). */
    public int chatRows() {
        return chatPixelHeight() / 4;
    }

    /** Height in px for a given width, holding the 448:280 ratio. */
    public static int heightForWidth(int w) {
        return (int) Math.round(w * (double) RATIO_H / RATIO_W);
    }

    private static int clampWidth(int w) {
        return Math.max(MIN_PX_W, Math.min(MAX_PX_W, w));
    }

    /**
     * Sets the DOOM canvas width in pixels (clamped to {@value #MIN_PX_W}..
     * {@value #MAX_PX_W}); height follows the ratio. Returns the clamped width.
     */
    public int setWidthPx(int w) {
        widthPx = clampWidth(w);
        save();
        return widthPx;
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("gamebox").resolve("gamebox.properties");
    }

    private void load() {
        Path f = file();
        if (!Files.isReadable(f)) {
            return;
        }
        try (var in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            widthPx = clampWidth(Integer.parseInt(p.getProperty("widthPx", String.valueOf(DEFAULT_PX_W))));
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("Could not read GameBox config, using defaults: {}", e.toString());
        }
    }

    private void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("widthPx", String.valueOf(widthPx));
            try (var out = Files.newOutputStream(f)) {
                p.store(out, "GameBox settings");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save GameBox config: {}", e.toString());
        }
    }
}
