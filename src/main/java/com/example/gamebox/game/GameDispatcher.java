package com.example.gamebox.game;

import com.example.gamebox.FlappyBirdGame;
import com.example.gamebox.GameBoxConfig;
import com.example.gamebox.child.ChildClientController;
import com.example.gamebox.doom.DoomController;
import com.example.gamebox.gb.GbController;
import com.example.gamebox.graphing.GraphingController;
import com.example.gamebox.nes.NesController;
import com.example.gamebox.snes.SnesController;
import com.example.gamebox.trig.TrigController;

import java.util.ArrayList;
import java.util.List;

/**
 * The brain behind the {@code /game} command — GameBox's entry point for playing
 * <em>games</em>. It launches the built-in games by keyword and any imported
 * ROM/WAD by filename, all rendered in the Minecraft chat box as Braille.
 *
 * <p>Non-gaming tools are <strong>not</strong> handled here: the graphing
 * calculator and the trigonometry calculator live under their own top-level
 * commands ({@code /graph} and {@code /trig}).
 *
 * <ul>
 *   <li>{@code /game} — list the built-in games and any imported ROM, + usage;</li>
 *   <li>{@code /game mario} — the built-in Super Mario Bros. (NES);</li>
 *   <li>{@code /game flappy} — the built-in Flappy Bird;</li>
 *   <li>{@code /game doom} — the built-in DOOM (needs a WAD);</li>
 *   <li>{@code /game minecraft} — launch a second real Minecraft into chat;</li>
 *   <li>{@code /game open <filename>} — open an imported ROM from the import
 *       folder; if its system is playable it launches, otherwise GameBox reports
 *       the detected system and that its engine is planned;</li>
 *   <li>{@code /game size <px>} — set the game chat-canvas width;</li>
 *   <li>{@code /game stop} — stop whatever game is currently running.</li>
 * </ul>
 */
public final class GameDispatcher {

    private final DoomController doom;
    private final NesController nes;
    private final GbController gb;
    private final SnesController snes;
    private final FlappyBirdGame flappy;
    private final GraphingController graphing;
    private final TrigController trig;
    private final ChildClientController child;

    public GameDispatcher(DoomController doom, NesController nes, GbController gb,
                          SnesController snes, FlappyBirdGame flappy,
                          GraphingController graphing, TrigController trig,
                          ChildClientController child) {
        this.doom = doom;
        this.nes = nes;
        this.gb = gb;
        this.snes = snes;
        this.flappy = flappy;
        this.graphing = graphing;
        this.trig = trig;
        this.child = child;
    }

    /** Handles the greedy argument of {@code /game <args>}; returns feedback lines. */
    public List<String> handle(String rawArgs) {
        String args = rawArgs == null ? "" : rawArgs.trim();
        if (args.isEmpty() || args.equalsIgnoreCase("list")) {
            return listing();
        }
        String lower = args.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("stop") || lower.equals("quit") || lower.equals("exit")) {
            return one(stopAll());
        }
        if (lower.equals("help") || lower.equals("?")) {
            return help();
        }
        if (lower.equals("flappy") || lower.equals("flappybird") || lower.equals("flappy.bird")) {
            flappy.focus();
            return one("§aFlappy Bird §7started. §eSpace/W/↑/click§7 to flap; "
                    + "§eK§7 regrab · §eEsc§7 release · §e/game stop§7 to end.");
        }
        if (lower.equals("mario") || lower.equals("supermario") || lower.equals("super-mario")
                || lower.equals("smb") || lower.equals("super mario") || lower.equals("super mario bros")
                || lower.equals("supermariobros")) {
            // Super Mario Bros. is a built-in game: launch the bundled ROM
            // directly, no import required.
            widenCanvas();
            return one("§a" + nes.run());
        }
        if (lower.equals("minecraft") || lower.equals("mc")
                || lower.equals("minecraft2") || lower.equals("mc2")) {
            widenCanvas();
            return one("§a" + child.start());
        }
        if (lower.equals("doom")) {
            widenCanvas();
            return one("§a" + doom.play());
        }

        // Subcommands that take an argument: open <file>, size <px>.
        String[] head = splitHead(args);
        String cmd = head[0].toLowerCase(java.util.Locale.ROOT);
        switch (cmd) {
            case "open":
            case "play":
            case "launch":
            case "load":
                return one(open(head[1]));
            case "size":
            case "setsize":
            case "width":
                return one(setSize(head[1]));
            default:
                // Bare filename (no subcommand) is still accepted as an import.
                return one(launch(args));
        }
    }

    /** {@code /game open <filename>} — opens an imported game from the import folder. */
    private String open(String rest) {
        String q = rest == null ? "" : rest.trim();
        if (q.isEmpty()) {
            List<GameLibrary.GameFile> games = GameLibrary.discover();
            if (games.isEmpty()) {
                return "§7Usage: §e/game open <filename>§7. No imported games yet — "
                        + "drop a ROM into §f" + GameLibrary.gamesDir();
            }
            StringBuilder sb = new StringBuilder("§7Usage: §e/game open <filename>§7. Imported: ");
            for (int i = 0; i < games.size(); i++) {
                sb.append("§f").append(games.get(i).name());
                if (i < games.size() - 1) {
                    sb.append("§7, ");
                }
            }
            return sb.toString();
        }
        return launch(q);
    }

    // ─── Graphing handler (used by the top-level /graph command) ──────────────

    /**
     * Handles the argument string for the standalone {@code /graph} command. Kept
     * here so the one place that touches the {@link GraphingController} is beside
     * the rest of GameBox's command glue; {@code GameBoxClient} routes
     * {@code /graph} straight to this.
     */
    public List<String> handleGraph(String rest) {
        String trimmed = rest == null ? "" : rest.trim();
        if (trimmed.isEmpty()) {
            // Bare: toggle on/off (or resume if paused), then list equations.
            if (graphing.isOn() && graphing.isPaused()) {
                graphing.resume();
            } else {
                graphing.toggle();
            }
            return graphing.listing();
        }
        if (trimmed.equalsIgnoreCase("restart")) {
            return one(graphing.restart());
        }
        if (trimmed.equalsIgnoreCase("all")) {
            return graphing.listAll();
        }
        // "<id> <equation>" — first token an integer id, remainder the equation.
        int sp = trimmed.indexOf(' ');
        String first = sp < 0 ? trimmed : trimmed.substring(0, sp);
        Integer id = tryParseInt(first);
        if (id != null) {
            String equation = sp < 0 ? "" : trimmed.substring(sp + 1).trim();
            return one(graphing.modifyEquation(id, equation));
        }
        return one(graphing.addEquation(trimmed));
    }

    // ─── Trig handler (used by the top-level /trig command) ───────────────────

    /**
     * Handles the argument string for the standalone {@code /trig} command,
     * delegating to the {@link TrigController}. Routed here from
     * {@code GameBoxClient} so all of GameBox's command glue stays in one place.
     */
    public List<String> handleTrig(String rest) {
        return trig.handle(rest);
    }

    // ─── Size subcommand (the game chat-canvas width lever) ───────────────────

    private String setSize(String rest) {
        String s = rest == null ? "" : rest.trim();
        if (s.isEmpty()) {
            return "§7Canvas width is §f" + GameBoxConfig.get().chatPixelWidth()
                    + "px§7. Usage: §e/game size <width>§7 (" + GameBoxConfig.MIN_PX_W
                    + ".." + GameBoxConfig.MAX_PX_W + ").";
        }
        int requested;
        try {
            requested = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return "§cNot a number: §7" + s;
        }
        int w = GameBoxConfig.get().setWidthPx(requested);
        int h = GameBoxConfig.get().chatPixelHeight();
        return "§aCanvas set to §f" + w + "x" + h + "px§7 (range " + GameBoxConfig.MIN_PX_W
                + ".." + GameBoxConfig.MAX_PX_W + ").";
    }

    /**
     * Resolves a filename to an imported game, and either launches it (if its
     * system is {@link GameLibrary.Support#PLAYABLE}) or reports honestly that
     * the system is recognised but not yet playable in chat.
     */
    private String launch(String query) {
        GameLibrary.GameFile g = GameLibrary.resolve(query);
        if (g == null) {
            String playable = String.join(", .", GameLibrary.playableExtensionHints());
            String all = String.join(", .", GameLibrary.extensions());
            return "§cNo game \"" + query + "\" found. §7Drop a ROM into §f"
                    + GameLibrary.gamesDir() + "§7, then §e/game§7 to list.\n"
                    + "§8playable now: §f." + playable + "§8 · recognised: §f." + all;
        }

        GameLibrary.System sys = g.system();
        if (!g.playable()) {
            // Recognised, but no in-chat engine yet — say so plainly.
            return "§eImported §f" + g.name() + " §7is a §f" + sys.displayName()
                    + "§7 ROM. §cThat system isn't playable in chat yet§7 "
                    + "(recognised, engine planned). §8Playable now: §f."
                    + String.join(", .", GameLibrary.playableExtensionHints());
        }

        // Playable: the emulator renders into chat as Braille — make it as wide
        // as chat allows, then launch via the system's engine.
        widenCanvas();
        switch (sys.engine()) {
            case NES:
                return "§a" + nes.run(g.path());
            case GB:
                return "§a" + gb.run(g.path());
            case SNES:
                return "§a" + snes.run(g.path());
            default:
                return "§cNo engine wired for \"" + g.name() + "\" (" + sys.displayName() + ").";
        }
    }

    /** Stops whichever game/tool currently owns the chat / is active. */
    private String stopAll() {
        List<String> stopped = new ArrayList<>();
        if (nes.isActive()) {
            stopped.add(nes.stop());
        }
        if (gb.isActive()) {
            stopped.add(gb.stop());
        }
        if (snes.isActive()) {
            stopped.add(snes.stop());
        }
        if (doom.isActive()) {
            stopped.add(doom.stop());
        }
        if (flappy.isActive()) {
            flappy.stop();
            stopped.add("Flappy Bird stopped.");
        }
        if (graphing.isOn()) {
            graphing.toggle(); // turns it off + releases chat
            stopped.add("Graphing calculator off.");
        }
        if (trig.isOn()) {
            trig.handle("off");
            stopped.add("Trig visualizer off.");
        }
        if (child.isActive()) {
            stopped.add(child.stop());
        }
        if (stopped.isEmpty()) {
            return "§7No game is running.";
        }
        return "§7" + String.join(" ", stopped);
    }

    private void widenCanvas() {
        GameBoxConfig.get().setWidthPx(GameBoxConfig.MAX_PX_W);
    }

    // ─── Listing / help ────────────────────────────────────────────────────────

    /** A multi-line listing of built-in games plus any imported ROM. */
    public List<String> listing() {
        List<String> out = new ArrayList<>();
        out.add("§b§l── /game — play a game ──");
        out.add("§7Built-in games (always available):");
        out.add("  §a▶ §emario §8[Super Mario Bros. · NES]");
        out.add("  §a▶ §eflappy §8[Flappy Bird]");
        out.add("  §a▶ §edoom §8[DOOM · needs a WAD]");
        out.add("  §a▶ §eminecraft §8[a second real Minecraft, in chat]");
        List<GameLibrary.GameFile> games = GameLibrary.discover();
        if (games.isEmpty()) {
            out.add("§7Imported games: §8none yet — drop a ROM into:");
            out.add("§8  " + GameLibrary.gamesDir());
            out.add("§8  recognised: §f.nes/.fc §8& §f.gb/.gbc §8(playable), §f.snes/.sfc §8(native core)");
        } else {
            out.add("§7Imported games (open with §e/game open <name>§7):");
            for (GameLibrary.GameFile g : games) {
                String tag = g.playable() ? "§a✔ playable" : "§e⌛ planned";
                out.add(String.format("  §a▶ §e%s §8[%s · %s§8] %s", g.name(),
                        g.system().displayName(), humanSize(g.sizeBytes()), tag));
            }
        }
        out.add("§8usage: §7open <file> §8· §7mario §8· §7flappy §8· §7doom §8· §7minecraft §8· §7size <px> §8· §7stop");
        out.add("§8tools (separate commands): §7/graph §8· §7/trig");
        return out;
    }

    private List<String> help() {
        List<String> out = new ArrayList<>();
        out.add("§b§l── /game — play a game ──");
        out.add("§e/game§7                list built-in + imported games + usage");
        out.add("§e/game mario§7          built-in Super Mario Bros. (K focus · Esc release)");
        out.add("§e/game flappy§7         built-in Flappy Bird (Space flap · K focus · Esc release)");
        out.add("§e/game doom§7           built-in DOOM (needs a WAD in config/gamebox/)");
        out.add("§e/game minecraft§7      launch a second REAL Minecraft (hidden child) into chat");
        out.add("§e/game open <file>§7    open an imported ROM by file name, e.g. §fmygame.nes");
        out.add("§e/game size <px>§7      set the game canvas width (" + GameBoxConfig.MIN_PX_W
                + ".." + GameBoxConfig.MAX_PX_W + ")");
        out.add("§e/game stop§7           stop the running game");
        out.add("§7Import a game by dropping its file into §f" + GameLibrary.gamesDir());
        out.add("§7Recognised systems: §fNES/Famicom §8(.nes/.fc)§7 & §fGame Boy §8(.gb/.gbc) §a✔ playable§7, "
                + "§fSNES §8(.snes/.sfc) §a✔ native core §8(build-snes.sh)");
        out.add("§8(the game dir, its parent, and §fconfig/gamebox/§7 are also scanned).");
        out.add("§7Non-game tools live under their own commands: §e/graph§7 and §e/trig§7.");
        return out;
    }

    // ─── small helpers ───────────────────────────────────────────────────────

    /** Splits "head rest" on the first run of whitespace. */
    private static String[] splitHead(String s) {
        String t = s.trim();
        int sp = -1;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                sp = i;
                break;
            }
        }
        if (sp < 0) {
            return new String[] {t, ""};
        }
        return new String[] {t.substring(0, sp), t.substring(sp + 1).trim()};
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", kb);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", kb / 1024.0);
    }

    private static List<String> one(String line) {
        List<String> out = new ArrayList<>(1);
        out.add(line);
        return out;
    }
}
