package com.example.gamebox.trig;

import com.example.gamebox.graphing.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-side glue for the {@code /trig} trigonometry calculator. It parses the
 * free-form argument string of each subcommand, drives {@link TrigCalculator},
 * and returns a list of ready-to-print chat lines (with §-color codes, matching
 * the rest of GameBox). It holds no Minecraft types, so it is trivially testable.
 *
 * <h2>Subcommands</h2>
 * <pre>
 *   /trig &lt;expr&gt;                 evaluate an expression (e.g. sin(30)+cos(0))
 *   /trig eval &lt;expr&gt;            same, explicit
 *   /trig deg | rad               set the angle mode (default: deg)
 *   /trig table &lt;angle&gt;          sin/cos/tan/csc/sec/cot of one angle
 *   /trig solve right k=v k=v     right-triangle solver (a,b,c,A,B)
 *   /trig law sines  …            oblique triangle via law of sines (AAS / SSA)
 *   /trig law cosines …           oblique triangle via law of cosines (SSS / SAS)
 *   /trig id                      list common identities
 *   /trig help                    usage
 * </pre>
 */
public final class TrigController {

    private final TrigCalculator calc = new TrigCalculator();
    private final TrigEngine engine = new TrigEngine();

    /** Whether the live triangle visualizer owns the chat box. */
    private boolean on = false;
    /** When paused, stays "on" but stops re-posting (so a listing is readable). */
    private boolean paused = false;

    public TrigCalculator.AngleMode mode() {
        return calc.mode();
    }

    // ─── Visualizer lifecycle (mirrors GraphingController) ────────────────────

    public synchronized boolean isOn() {
        return on;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** The visualizer owns the chat box only while on and not paused. */
    public synchronized boolean ownsChat() {
        return on && !paused;
    }

    /** Re-posts the triangle frame each tick while on; called from the client tick. */
    public void renderToChat() {
        boolean active;
        synchronized (this) {
            active = on && !paused;
        }
        if (active) {
            engine.renderToChat();
        }
    }

    /** Opens the transparent input overlay to grab the keyboard (bound to K). */
    public synchronized void focus() {
        if (!on) {
            return;
        }
        paused = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new TrigInputScreen(this)));
        }
    }

    private synchronized void turnOn() {
        if (!on) {
            on = true;
            paused = false;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> mc.setScreen(new TrigInputScreen(this)));
            }
        } else {
            paused = false;
        }
    }

    private synchronized boolean toggle() {
        on = !on;
        if (on) {
            paused = false;
            focus();
        } else {
            releaseChat();
        }
        return on;
    }

    private void releaseChat() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                if (mc.screen instanceof TrigInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§b[GameBox] §7Trig visualizer off — chat is yours. "
                                        + "§e/trig show §7to turn it back on."));
            });
        }
    }

    /** Entry point: dispatch a raw argument string to the right handler. */
    public List<String> handle(String rawArgs) {
        String args = rawArgs == null ? "" : rawArgs.trim();
        String lower = args.toLowerCase(Locale.ROOT);

        // Bare /trig → toggle the visualizer and show its current state.
        if (args.isEmpty()) {
            boolean nowOn = toggle();
            if (nowOn) {
                return one("§bTrig visualizer §aON§7. Enter a triangle, e.g. "
                        + "§e/trig a=3 b=4 c=5§7. §8K focus · Esc release · /trig help");
            }
            return one("§bTrig visualizer §cOFF§7. §e/trig show§7 to turn it on.");
        }

        if (lower.equals("help") || lower.equals("?")) {
            return help();
        }
        if (lower.equals("id") || lower.equals("ids") || lower.equals("identities")) {
            return identities();
        }
        if (lower.equals("show") || lower.equals("draw") || lower.equals("on")) {
            turnOn();
            return one("§bTrig visualizer §aON§7. §8K focus · Esc release · "
                    + "enter a triangle like §e/trig a=3 b=4 c=5");
        }
        if (lower.equals("off") || lower.equals("hide")) {
            synchronized (this) {
                on = false;
            }
            releaseChat();
            return one("§bTrig visualizer §cOFF§7.");
        }
        if (lower.equals("clear") || lower.equals("restart")) {
            engine.clear();
            return one("§7Triangle cleared. Enter a new one, e.g. §e/trig a=3 b=4 c=5§7.");
        }
        if (lower.equals("deg") || lower.equals("degrees")) {
            calc.setMode(TrigCalculator.AngleMode.DEG);
            return one("§aAngle mode: §fdegrees§7. e.g. §esin(30)§7 = 0.5");
        }
        if (lower.equals("rad") || lower.equals("radians")) {
            calc.setMode(TrigCalculator.AngleMode.RAD);
            return one("§aAngle mode: §fradians§7. e.g. §esin(pi/6)§7 = 0.5");
        }
        if (lower.equals("mode")) {
            return one("§7Current angle mode: §f" + calc.mode().label());
        }

        String[] head = splitHead(args);
        String cmd = head[0].toLowerCase(Locale.ROOT);
        String rest = head[1];

        switch (cmd) {
            case "eval":
                return evaluate(rest);
            case "table":
                return table(rest);
            case "solve":
                return solve(rest);
            case "law":
                return law(rest);
            default:
                // If the whole thing is triangle key=value tokens (a b c A B C),
                // drive the live visualizer; otherwise treat it as an expression.
                if (looksLikeTriangleInput(args)) {
                    return triangleInput(args);
                }
                return evaluate(args);
        }
    }

    // ─── Live triangle input → solve + visualize ──────────────────────────────

    /** True if every whitespace token is a {@code key=value} with key in a,b,c,A,B,C. */
    private static boolean looksLikeTriangleInput(String args) {
        String[] toks = args.trim().split("\\s+");
        boolean any = false;
        for (String tok : toks) {
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            if (!tok.substring(0, eq).matches("[abcABC]")) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Parses triangle parts, solves the triangle, pushes the result into the
     * visualizer (turning it on), and returns a textual summary too.
     */
    private List<String> triangleInput(String args) {
        double a = Double.NaN, b = Double.NaN, c = Double.NaN;
        double A = Double.NaN, B = Double.NaN, C = Double.NaN;
        for (String tok : args.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                return one("§cBad token §7'" + tok + "'§c — use key=value (a b c A B C).");
            }
            String key = tok.substring(0, eq);
            double val;
            try {
                val = calc.evaluate(tok.substring(eq + 1));
            } catch (RuntimeException e) {
                return one("§cBad value for §f" + key + "§c: §7" + e.getMessage());
            }
            switch (key) {
                case "a": a = val; break;
                case "b": b = val; break;
                case "c": c = val; break;
                case "A": A = val; break;
                case "B": B = val; break;
                case "C": C = val; break;
                default:
                    return one("§cUnknown key §f" + key + "§c (use a b c A B C).");
            }
        }
        try {
            TrigCalculator.SolveResult r = calc.solveTriangle(a, b, c, A, B, C);
            engine.setResult(r, calc.mode().label());
            turnOn();
            TrigCalculator.Triangle t = r.tri();
            List<String> out = new ArrayList<>();
            out.add("§b§lTriangle §8(" + r.kind() + ")§7 drawn in chat. §8K focus · Esc release");
            out.add("  §asides§7: a=§f" + fmt(t.a()) + "§7 b=§f" + fmt(t.b())
                    + "§7 c=§f" + fmt(t.c()));
            out.add("  §dangles§7: A=§f" + fmt(t.A()) + "§7 B=§f" + fmt(t.B())
                    + "§7 C=§f" + fmt(t.C()) + " §8(" + calc.mode().label() + ")");
            out.add("  §7area=§f" + fmt(r.area()));
            if (t.ambiguous() && t.altB() != null) {
                out.add("  §6Ambiguous (SSA): a second triangle exists with B'=§f"
                        + fmt(t.altB()));
            }
            return out;
        } catch (IllegalArgumentException e) {
            engine.setError(e.getMessage());
            return one("§cCannot solve: §7" + e.getMessage());
        }
    }

    // ─── eval ──────────────────────────────────────────────────────────────────

    private List<String> evaluate(String expr) {
        if (expr.isBlank()) {
            return one("§cUsage: §e/trig eval <expression>§7 (e.g. sin(30)+cos(60))");
        }
        try {
            double v = calc.evaluate(expr);
            return one("§b" + expr.trim() + " §7= §f" + fmt(v)
                    + " §8(" + calc.mode().label() + ")");
        } catch (Expression.ParseException e) {
            return one("§cParse error: §7" + e.getMessage());
        } catch (RuntimeException e) {
            return one("§cError: §7" + e.getMessage());
        }
    }

    // ─── table ───────────────────────────────────────────────────────────────

    private List<String> table(String angleStr) {
        if (angleStr.isBlank()) {
            return one("§cUsage: §e/trig table <angle>§7 (angle in " + calc.mode().label() + ")");
        }
        double angle;
        try {
            angle = calc.evaluate(angleStr); // allow expressions like pi/6, 30+15
        } catch (RuntimeException e) {
            return one("§cBad angle: §7" + e.getMessage());
        }
        TrigCalculator.SixValues v = calc.table(angle);
        List<String> out = new ArrayList<>();
        out.add("§b§lTrig table §7for §f" + fmt(angle) + " " + calc.mode().label());
        out.add("  §esin§7 = §f" + fmt(v.sin()) + "   §ecsc§7 = §f" + fmt(v.csc()));
        out.add("  §ecos§7 = §f" + fmt(v.cos()) + "   §esec§7 = §f" + fmt(v.sec()));
        out.add("  §etan§7 = §f" + fmt(v.tan()) + "   §ecot§7 = §f" + fmt(v.cot()));
        return out;
    }

    // ─── solve right ───────────────────────────────────────────────────────────

    private List<String> solve(String rest) {
        String[] head = splitHead(rest);
        String kind = head[0].toLowerCase(Locale.ROOT);
        if (!kind.equals("right")) {
            return one("§cUsage: §e/trig solve right a=3 b=4§7  (keys: a b c A B)");
        }
        // Legs use lowercase a/b/c, acute angles uppercase A/B — case matters, so
        // parse the raw tokens directly rather than through a case-folding map.
        return solveRightClean(head[1]);
    }

    /**
     * Right-triangle solve with unambiguous keys. Legs/hyp use lowercase
     * {@code a b c}; acute angles use uppercase {@code A B}.
     */
    private List<String> solveRightClean(String tokens) {
        double a = Double.NaN, b = Double.NaN, c = Double.NaN, A = Double.NaN, B = Double.NaN;
        if (tokens.isBlank()) {
            return one("§cUsage: §e/trig solve right a=3 b=4§7  (keys: a b c A B)");
        }
        for (String tok : tokens.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                return one("§cBad token §7'" + tok + "'§c — use key=value (a b c A B).");
            }
            String key = tok.substring(0, eq);
            String valStr = tok.substring(eq + 1);
            double val;
            try {
                val = calc.evaluate(valStr);
            } catch (RuntimeException e) {
                return one("§cBad value for §f" + key + "§c: §7" + e.getMessage());
            }
            switch (key) {
                case "a": a = val; break;
                case "b": b = val; break;
                case "c": c = val; break;
                case "A": A = val; break;
                case "B": B = val; break;
                default:
                    return one("§cUnknown key §f" + key + "§c. Use a b c (sides) or A B (acute angles).");
            }
        }
        try {
            TrigCalculator.RightTriangle t = calc.solveRight(a, b, c, A, B);
            List<String> out = new ArrayList<>();
            out.add("§b§lRight triangle §8(right angle at C)");
            out.add("  §esides§7: a = §f" + fmt(t.a()) + "§7, b = §f" + fmt(t.b())
                    + "§7, c = §f" + fmt(t.c()) + " §8(hyp)");
            out.add("  §eangles§7: A = §f" + fmt(t.A()) + "§7, B = §f" + fmt(t.B())
                    + "§7, C = §f" + fmt(right()) + " §8(" + calc.mode().label() + ")");
            out.add("  §earea§7 = §f" + fmt(t.area()));
            return out;
        } catch (IllegalArgumentException e) {
            return one("§cCannot solve: §7" + e.getMessage());
        }
    }

    private double right() {
        return calc.mode() == TrigCalculator.AngleMode.DEG ? 90.0 : Math.PI / 2.0;
    }

    // ─── law sines / cosines ─────────────────────────────────────────────────

    private List<String> law(String rest) {
        String[] head = splitHead(rest);
        String which = head[0].toLowerCase(Locale.ROOT);
        String tokens = head[1];
        java.util.Map<String, Double> kv = parseLawKeyValues(tokens);
        if (kv == null) {
            return one("§cBad input. Keys: a b c (sides), A B C (angles). "
                    + "e.g. §e/trig law cosines a=5 b=7 C=40");
        }
        double a = kv.getOrDefault("a", Double.NaN);
        double b = kv.getOrDefault("b", Double.NaN);
        double c = kv.getOrDefault("c", Double.NaN);
        double A = kv.getOrDefault("A", Double.NaN);
        double B = kv.getOrDefault("B", Double.NaN);
        double C = kv.getOrDefault("C", Double.NaN);

        try {
            if (which.equals("cosines") || which.equals("cos")) {
                return lawCosines(a, b, c, A, B, C);
            }
            if (which.equals("sines") || which.equals("sin")) {
                return lawSines(a, b, c, A, B, C);
            }
            return one("§cUsage: §e/trig law sines|cosines <key=value …>");
        } catch (IllegalArgumentException e) {
            return one("§cCannot solve: §7" + e.getMessage());
        }
    }

    private List<String> lawCosines(double a, double b, double c,
                                    double A, double B, double C) {
        int sides = countKnown(a, b, c);
        if (sides == 3) {
            return triangleLines("§b§lLaw of cosines §8(SSS)", calc.lawCosinesSSS(a, b, c));
        }
        // SAS: two sides and the included angle.
        if (!Double.isNaN(a) && !Double.isNaN(b) && !Double.isNaN(C)) {
            return triangleLines("§b§lLaw of cosines §8(SAS)", calc.lawCosinesSAS(a, b, C));
        }
        if (!Double.isNaN(b) && !Double.isNaN(c) && !Double.isNaN(A)) {
            // relabel so the included angle is C and its two sides are a,b
            TrigCalculator.Triangle t = calc.lawCosinesSAS(b, c, A);
            // t has sides (b,c,->a) with angles (B,C,A). Re-wrap for display.
            return triangleLines("§b§lLaw of cosines §8(SAS)",
                    new TrigCalculator.Triangle(t.c(), t.a(), t.b(),
                            t.C(), t.A(), t.B(), false, null));
        }
        if (!Double.isNaN(a) && !Double.isNaN(c) && !Double.isNaN(B)) {
            TrigCalculator.Triangle t = calc.lawCosinesSAS(a, c, B);
            return triangleLines("§b§lLaw of cosines §8(SAS)",
                    new TrigCalculator.Triangle(t.a(), t.c(), t.b(),
                            t.A(), t.C(), t.B(), false, null));
        }
        return one("§cLaw of cosines needs §fSSS§c (a b c) or §fSAS§c "
                + "(two sides + the included angle). e.g. a=5 b=7 C=40");
    }

    private List<String> lawSines(double a, double b, double c,
                                  double A, double B, double C) {
        // AAS/ASA: two angles + one side. Fill the third angle, then solve from
        // the side opposite a known angle.
        int angles = countKnown(A, B, C);
        if (angles >= 2) {
            if (Double.isNaN(A)) {
                A = thirdAngle(B, C);
            } else if (Double.isNaN(B)) {
                B = thirdAngle(A, C);
            } else if (Double.isNaN(C)) {
                C = thirdAngle(A, B);
            }
            // Need a side; use whichever is given, relabel so it is 'a' opp 'A'.
            if (!Double.isNaN(a)) {
                return triangleLines("§b§lLaw of sines §8(AAS/ASA)", calc.lawSinesAAS(A, B, a));
            }
            if (!Double.isNaN(b)) {
                TrigCalculator.Triangle t = calc.lawSinesAAS(B, A, b);
                return triangleLines("§b§lLaw of sines §8(AAS/ASA)",
                        new TrigCalculator.Triangle(t.b(), t.a(), t.c(),
                                t.B(), t.A(), t.C(), false, null));
            }
            if (!Double.isNaN(c)) {
                TrigCalculator.Triangle t = calc.lawSinesAAS(C, A, c);
                return triangleLines("§b§lLaw of sines §8(AAS/ASA)",
                        new TrigCalculator.Triangle(t.b(), t.c(), t.a(),
                                t.B(), t.C(), t.A(), false, null));
            }
            return one("§cLaw of sines (AAS) needs a side length too.");
        }

        // SSA (ambiguous case): two sides + an angle opposite one of them.
        if (!Double.isNaN(a) && !Double.isNaN(b) && !Double.isNaN(A)) {
            return ssaLines(calc.lawSinesSSA(a, b, A), "B");
        }
        if (!Double.isNaN(a) && !Double.isNaN(c) && !Double.isNaN(A)) {
            TrigCalculator.Triangle t = calc.lawSinesSSA(a, c, A);
            return ssaLines(new TrigCalculator.Triangle(t.a(), t.c(), t.b(),
                    t.A(), t.C(), t.B(), t.ambiguous(), t.altB()), "C");
        }
        if (!Double.isNaN(b) && !Double.isNaN(a) && !Double.isNaN(B)) {
            TrigCalculator.Triangle t = calc.lawSinesSSA(b, a, B);
            return ssaLines(new TrigCalculator.Triangle(t.b(), t.a(), t.c(),
                    t.B(), t.A(), t.C(), t.ambiguous(), t.altB()), "A");
        }
        return one("§cLaw of sines needs §fAAS/ASA§c (two angles + a side) or "
                + "§fSSA§c (two sides + an angle opposite one). e.g. A=40 B=60 a=5");
    }

    private List<String> ssaLines(TrigCalculator.Triangle t, String ambigLabel) {
        List<String> out = triangleLines("§b§lLaw of sines §8(SSA)", t);
        if (t.ambiguous() && t.altB() != null) {
            out.add("  §6Ambiguous case: a second triangle exists with "
                    + ambigLabel + "' = §f" + fmt(t.altB()) + " §8(" + calc.mode().label() + ")");
        }
        return out;
    }

    private double thirdAngle(double x, double y) {
        double straight = calc.mode() == TrigCalculator.AngleMode.DEG ? 180.0 : Math.PI;
        return straight - x - y;
    }

    private List<String> triangleLines(String title, TrigCalculator.Triangle t) {
        List<String> out = new ArrayList<>();
        out.add(title);
        out.add("  §esides§7: a = §f" + fmt(t.a()) + "§7, b = §f" + fmt(t.b())
                + "§7, c = §f" + fmt(t.c()));
        out.add("  §eangles§7: A = §f" + fmt(t.A()) + "§7, B = §f" + fmt(t.B())
                + "§7, C = §f" + fmt(t.C()) + " §8(" + calc.mode().label() + ")");
        return out;
    }

    // ─── identities / help ─────────────────────────────────────────────────────

    private List<String> identities() {
        List<String> out = new ArrayList<>();
        out.add("§b§l── Trig identities ──");
        for (String line : TrigCalculator.identities()) {
            out.add("§7" + line);
        }
        return out;
    }

    private List<String> help() {
        List<String> out = new ArrayList<>();
        out.add("§b§l── /trig — trigonometry calculator ──  §8(mode: " + calc.mode().label() + ")");
        out.add("§6Visualizer:");
        out.add("§e/trig§7                 toggle the live triangle visualizer on/off");
        out.add("§e/trig a=3 b=4 c=5§7     solve + draw a triangle (any 3 parts incl. a side)");
        out.add("§8                       keys: §aa b c§8 sides, §dA B C§8 angles — SSS/SAS/ASA/AAS/SSA");
        out.add("§e/trig show §7| §eclear§7    turn visualizer on · clear the triangle");
        out.add("§8                       §7K§8 focus · §7Esc§8 release (triangle auto-fits the canvas)");
        out.add("§6Instant text:");
        out.add("§e/trig eval <expr>§7     evaluate, e.g. §fsin(30)+cos(60)§7, §f2*tan(45)^2");
        out.add("§e/trig deg §7| §erad§7       set angle mode (default §fdeg§7)");
        out.add("§e/trig table <angle>§7   sin/cos/tan/csc/sec/cot of one angle");
        out.add("§e/trig solve right …§7   right triangle, keys §fa b c§7 (sides) §fA B§7 (angles)");
        out.add("§e/trig law cosines …§7   SSS §f(a b c)§7 or SAS §f(2 sides + included angle)§7");
        out.add("§e/trig law sines …§7     AAS/ASA §f(2 angles + a side)§7 or SSA §f(ambiguous)§7");
        out.add("§e/trig id§7              common identities");
        out.add("§7Supports §fpi e §7, §f^ sqrt ln log §7, and implicit §f2x§7 — reuses the grapher's math.");
        return out;
    }

    // ─── parsing helpers ───────────────────────────────────────────────────────

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

    /**
     * Parses "key=value key=value" where keys are single letters a/b/c (sides) or
     * A/B/C (angles), case-sensitive, evaluating each value with the calculator.
     * Returns {@code null} on any malformed token.
     */
    private java.util.Map<String, Double> parseLawKeyValues(String tokens) {
        java.util.Map<String, Double> map = new java.util.LinkedHashMap<>();
        if (tokens.isBlank()) {
            return null;
        }
        for (String tok : tokens.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = tok.substring(0, eq);
            if (!key.matches("[abcABC]")) {
                return null;
            }
            try {
                map.put(key, calc.evaluate(tok.substring(eq + 1)));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return map;
    }

    private static int countKnown(double... xs) {
        int n = 0;
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                n++;
            }
        }
        return n;
    }

    /** Formats a double compactly: integers without a decimal, else up to 6 sig. */
    static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "undefined";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "∞" : "-∞";
        }
        if (Math.abs(v) < 1e-10) {
            v = 0.0;
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.6g", v);
        // Trim trailing zeros in the mantissa for readability.
        if (s.contains(".") && !s.contains("e") && !s.contains("E")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    private static List<String> one(String line) {
        List<String> out = new ArrayList<>(1);
        out.add(line);
        return out;
    }
}
