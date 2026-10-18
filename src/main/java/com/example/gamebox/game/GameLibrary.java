package com.example.gamebox.game;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers "importable" game files on disk and resolves a user-typed filename to
 * a concrete {@link GameFile}. Each game console is keyed by file extension, so
 * dropping a recognised ROM into the import folder makes it openable by name via
 * {@code /game open <filename>} — no code change required.
 *
 * <h2>Recognised systems</h2>
 * GameBox deliberately recognises only three systems — the ones that are either
 * playable now or a realistic pure-Java in-chat emulator target:
 * <ul>
 *   <li><b>NES / Famicom</b> ({@code .nes}, {@code .fc}, {@code .famicom}) —
 *       {@link Support#PLAYABLE} via the built-in pure-Java NES core;</li>
 *   <li><b>Game Boy / Color</b> ({@code .gb}, {@code .gbc}) —
 *       {@link Support#PLAYABLE} via the pure-Java Game Boy (DMG) core;</li>
 *   <li><b>Super NES / Super Famicom</b> ({@code .snes}, {@code .sfc}) —
 *   <li><b>Super NES / Super Famicom</b> ({@code .snes}, {@code .sfc}) —
 *       {@link Support#PLAYABLE} via the native snes9x libretro core (requires
 *       the native bridge built with {@code native/build-snes.sh}).</li>
 * </ul>
 * Any other file type is simply reported as "not a GameBox game". Teaching
 * GameBox to actually play a {@link Support#PLANNED} system is a matter of
 * writing its in-chat engine and flipping its {@link Support} to
 * {@link Support#PLAYABLE}.
 *
 * <h2>Search locations</h2>
 * <ol>
 *   <li>{@code <config>/gamebox/games/} — the import folder (created on first use);</li>
 *   <li>{@code <config>/gamebox/};</li>
 *   <li>the Minecraft game directory ({@code run/} in dev);</li>
 *   <li>its parent (the project root in dev, where the built-in Super Mario ROM ships).</li>
 * </ol>
 */
public final class GameLibrary {

    /** The runnable backends GameBox ships — all render in-chat as Braille. */
    public enum Engine {
        /** Pure-Java NES/Famicom core (powers the built-in Super Mario Bros.). */
        NES,
        /** Pure-Java Game Boy (DMG) core. */
        GB,
        /** Native snes9x libretro core (SNES / Super Famicom). */
        SNES,
        /** Native DOOM (doomgeneric) engine — not an importable console here. */
        DOOM
    }

    /** How well GameBox can actually run a given system. */
    public enum Support {
        /** A working in-chat engine exists; the file can be launched now. */
        PLAYABLE,
        /** Recognised system, but its in-chat engine is not written yet. */
        PLANNED
    }

    /**
     * A console GameBox recognises for import. Each maps to the {@link Engine}
     * that would run it in chat (may be {@code null} while {@link Support#PLANNED})
     * and a {@link Support} status.
     */
    public enum System {
        NES("Nintendo (NES / Famicom)", Engine.NES, Support.PLAYABLE),
        GB("Game Boy / Game Boy Color", Engine.GB, Support.PLAYABLE),
        SNES("Super NES / Super Famicom", Engine.SNES, Support.PLAYABLE);

        private final String displayName;
        private final Engine engine;
        private final Support support;

        System(String displayName, Engine engine, Support support) {
            this.displayName = displayName;
            this.engine = engine;
            this.support = support;
        }

        public String displayName() {
            return displayName;
        }

        /** The engine that runs this system in chat, or {@code null} if none yet. */
        public Engine engine() {
            return engine;
        }

        public Support support() {
            return support;
        }

        public boolean playable() {
            return support == Support.PLAYABLE && engine != null;
        }
    }

    /** A discovered game file, tagged with its {@link System}. */
    public record GameFile(String name, Path path, System system, long sizeBytes) {
        /** The engine that runs this file in chat, or {@code null} if none yet. */
        public Engine engine() {
            return system.engine();
        }

        public Support support() {
            return system.support();
        }

        public boolean playable() {
            return system.playable();
        }
    }

    /**
     * Lower-case extension (no dot) → {@link System}. Only these extensions are
     * recognised; everything else is "not a GameBox game".
     */
    private static final Map<String, System> EXT_SYSTEM = new LinkedHashMap<>();

    private static void map(System sys, String... exts) {
        for (String e : exts) {
            EXT_SYSTEM.put(e.toLowerCase(Locale.ROOT), sys);
        }
    }

    static {
        // NES / Famicom — pure-Java core, playable now.
        map(System.NES, "nes", "fc", "famicom");
        // Game Boy / Color — recognised, engine planned.
        map(System.GB, "gb", "gbc");
        // Super NES / Super Famicom — recognised, engine planned.
        map(System.SNES, "snes", "sfc");
    }

    private GameLibrary() {
    }

    /** The set of recognised extensions (without the leading dot), in registry order. */
    public static List<String> extensions() {
        return new ArrayList<>(EXT_SYSTEM.keySet());
    }

    /** The actually-playable extensions, for hints. */
    public static List<String> playableExtensionHints() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, System> e : EXT_SYSTEM.entrySet()) {
            if (e.getValue().playable()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The {@link System} for a file name, or {@code null} if the extension is unknown. */
    public static System systemForName(String fileName) {
        String ext = extensionOf(fileName);
        return ext == null ? null : EXT_SYSTEM.get(ext);
    }

    /** The engine for a file name, or {@code null} if unknown / not yet playable. */
    public static Engine engineForName(String fileName) {
        System sys = systemForName(fileName);
        return sys == null ? null : sys.engine();
    }

    /** Lower-case extension (no dot) of a filename, or null if it has none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The import folder, created on demand. */
    public static Path gamesDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("gamebox").resolve("games");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // Non-fatal: discovery just skips a missing directory.
        }
        return dir;
    }

    /** All directories scanned for games, in priority order (nulls removed). */
    public static List<Path> searchDirs() {
        Path game = FabricLoader.getInstance().getGameDir();
        Path config = FabricLoader.getInstance().getConfigDir().resolve("gamebox");
        List<Path> dirs = new ArrayList<>();
        dirs.add(gamesDir());
        dirs.add(config);
        if (game != null) {
            dirs.add(game);
            dirs.add(game.getParent());
        }
        dirs.removeIf(p -> p == null);
        return dirs;
    }

    /**
     * Scans the search directories (non-recursively) for files with a recognised
     * extension. The first occurrence of a given (case-insensitive) filename
     * wins, so a copy in {@code games/} shadows one in a lower-priority dir.
     */
    public static List<GameFile> discover() {
        Map<String, GameFile> byName = new LinkedHashMap<>();
        for (Path dir : searchDirs()) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    System sys = systemForName(name);
                    if (sys == null) {
                        return;
                    }
                    String key = name.toLowerCase(Locale.ROOT);
                    if (!byName.containsKey(key)) {
                        long size = 0L;
                        try {
                            size = Files.size(p);
                        } catch (IOException ignored) {
                            // leave size as 0
                        }
                        byName.put(key, new GameFile(name, p, sys, size));
                    }
                });
            } catch (IOException ignored) {
                // Skip unreadable directories.
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Resolves a user-typed game name to a discovered file. Matching is
     * case-insensitive and tolerant of a missing extension (e.g. {@code mario}
     * matches {@code mario.nes}) and of spaces vs. hyphens/underscores. If the
     * query is itself a readable absolute/relative path with a recognised
     * extension, that path is used directly.
     *
     * @return the matching {@link GameFile}, or {@code null} if none matched
     */
    public static GameFile resolve(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim();

        // Direct path (absolute or relative to CWD) with a known extension.
        Path direct = Path.of(q);
        if (Files.isReadable(direct) && Files.isRegularFile(direct)) {
            System sys = systemForName(direct.getFileName().toString());
            if (sys != null) {
                long size = 0L;
                try {
                    size = Files.size(direct);
                } catch (IOException ignored) {
                    // ignore
                }
                return new GameFile(direct.getFileName().toString(), direct, sys, size);
            }
        }

        List<GameFile> all = discover();
        String norm = normalize(q);

        // 1) Exact (normalised) filename match.
        for (GameFile g : all) {
            if (normalize(g.name()).equals(norm)) {
                return g;
            }
        }
        // 2) Match ignoring the extension (query had none, or a different case).
        String normNoExt = stripExt(norm);
        for (GameFile g : all) {
            if (stripExt(normalize(g.name())).equals(normNoExt)) {
                return g;
            }
        }
        // 3) Prefix / contains match on the extension-less name (handy shortcut).
        GameFile best = null;
        for (GameFile g : all) {
            String cand = stripExt(normalize(g.name()));
            if (cand.startsWith(normNoExt) || cand.contains(normNoExt)) {
                if (best == null) {
                    best = g;
                }
            }
        }
        return best;
    }

    /** Collapses case, spaces, hyphens and underscores for lenient matching. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace(' ', '-')
                .replace('_', '-')
                .replaceAll("-+", "-")
                .trim();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
