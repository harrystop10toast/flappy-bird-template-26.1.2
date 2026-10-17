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
 * a concrete {@link Path}. GameBox treats each game console/engine as an
 * emulator keyed by file extension, so dropping a {@code .nes} ROM or a
 * {@code .wad} into a search directory makes it launchable by name via
 * {@code /game <filename>} — no code change required.
 *
 * <h2>Search locations</h2>
 * <ol>
 *   <li>{@code <config>/gamebox/games/} — the recommended "drop games here" dir
 *       (created on first use);</li>
 *   <li>{@code <config>/gamebox/};</li>
 *   <li>the Minecraft game directory ({@code run/} in dev);</li>
 *   <li>its parent (the project root in dev, where the bundled Super Mario ROM
 *       lives).</li>
 * </ol>
 *
 * <h2>Recognised extensions → engine</h2>
 * <ul>
 *   <li>{@code .nes} → the pure-Java NES emulator;</li>
 *   <li>{@code .wad} / {@code .iwad} / {@code .pwad} → the DOOM (doomgeneric)
 *       engine.</li>
 * </ul>
 * The map is easy to extend as more engines are added.
 */
public final class GameLibrary {

    /** The actual runnable backends GameBox ships — all render in-chat as Braille. */
    public enum Engine {
        NES, DOOM
    }

    /**
     * A console / platform a game file can belong to. Each system maps to the
     * {@link Engine} that runs it <em>in the Minecraft chat box</em>. GameBox only
     * recognises systems it can actually play in chat; adding a new in-chat engine
     * later is just a matter of adding a {@link System} + {@link Engine} and
     * pointing its extensions at it in {@link #EXT_SYSTEM}.
     */
    public enum System {
        NES("Nintendo (NES/Famicom)", Engine.NES),
        DOOM("DOOM (id Tech 1)", Engine.DOOM);

        private final String displayName;
        private final Engine engine;

        System(String displayName, Engine engine) {
            this.displayName = displayName;
            this.engine = engine;
        }

        public String displayName() {
            return displayName;
        }

        /** The engine that runs this system in chat. */
        public Engine engine() {
            return engine;
        }

        public boolean supported() {
            return engine != null;
        }
    }

    /** A discovered game file, tagged with its {@link System} (and thus engine). */
    public record GameFile(String name, Path path, System system, long sizeBytes) {
        /** The engine that runs this file in chat. */
        public Engine engine() {
            return system.engine();
        }

        public boolean supported() {
            return system.supported();
        }
    }

    /**
     * Lower-case extension (no dot) → {@link System}. Only the extensions GameBox
     * can actually run in chat are recognised. Add a new in-chat engine here to
     * teach GameBox a new system.
     */
    private static final Map<String, System> EXT_SYSTEM = new LinkedHashMap<>();

    private static void map(System sys, String... exts) {
        for (String e : exts) {
            EXT_SYSTEM.put(e.toLowerCase(Locale.ROOT), sys);
        }
    }

    static {
        // DOOM engine (native doomgeneric, rendered into chat as Braille).
        map(System.DOOM, "wad", "iwad", "pwad");
        // NES family (pure-Java emulator, rendered into chat as Braille).
        map(System.NES, "nes", "fc", "famicom", "unf", "unif");
    }

    private GameLibrary() {
    }

    /** The set of recognised extensions (without the leading dot). */
    public static List<String> extensions() {
        return new ArrayList<>(EXT_SYSTEM.keySet());
    }

    /** A few representative, actually-playable extensions, for hints. */
    public static List<String> playableExtensionHints() {
        return List.of("nes", "wad");
    }

    /** The {@link System} for a file name, or {@code null} if the extension is unknown. */
    public static System systemForName(String fileName) {
        String ext = extensionOf(fileName);
        return ext == null ? null : EXT_SYSTEM.get(ext);
    }

    /** The engine for a file name, or {@code null} if the extension is unknown. */
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

    /** The recommended drop-in directory, created on demand. */
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
     * query is itself a readable absolute/relative path, that path is used
     * directly.
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
