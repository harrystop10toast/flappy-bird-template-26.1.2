package com.example.gamebox.trig;

import com.example.gamebox.graphing.Expression;

/**
 * The computational core of the {@code /trig} trigonometry calculator.
 *
 * <p>It is deliberately UI-free: every public method returns plain {@code double}
 * values or small result records, and {@link TrigController} turns those into
 * chat lines. Expression evaluation reuses the graphing calculator's
 * {@link Expression} parser so the full function set ({@code sin cos tan asin …},
 * {@code sqrt}, {@code ln}, {@code ^}, {@code pi}, {@code e}, implicit
 * multiplication, …) is available for free.
 *
 * <h2>Angle mode</h2>
 * {@link Expression} always works in radians. This class holds an {@link #mode}
 * (DEG or RAD, default DEG — what people usually expect from a pocket trig
 * calculator) and rewrites a user expression before parsing so that, in degree
 * mode, {@code sin(30)} means {@code sin(30°)} and {@code asin(0.5)} returns
 * {@code 30}. See {@link #toRadiansExpr(String)}.
 */
public final class TrigCalculator {

    /** How bare angles passed to/from trig functions are interpreted. */
    public enum AngleMode {
        DEG, RAD;

        public String label() {
            return this == DEG ? "degrees" : "radians";
        }
    }

    private AngleMode mode = AngleMode.DEG;

    public AngleMode mode() {
        return mode;
    }

    public void setMode(AngleMode m) {
        this.mode = m;
    }

    // ─── Angle conversions ────────────────────────────────────────────────────

    /** Converts an angle expressed in the current mode into radians. */
    public double toRadians(double angleInMode) {
        return mode == AngleMode.DEG ? Math.toRadians(angleInMode) : angleInMode;
    }

    /** Converts a radian angle into the current mode's unit. */
    public double fromRadians(double angleRadians) {
        return mode == AngleMode.DEG ? Math.toDegrees(angleRadians) : angleRadians;
    }

    // ─── Expression evaluation (angle-mode aware) ─────────────────────────────

    /**
     * Evaluates a scalar expression honouring the current angle mode. In degree
     * mode the forward trig functions take degrees and the inverse trig
     * functions return degrees; in radian mode nothing is rewritten.
     *
     * @throws Expression.ParseException if the expression is malformed
     */
    public double evaluate(String expr) {
        String prepared = mode == AngleMode.DEG ? toRadiansExpr(expr) : expr;
        return Expression.parse(prepared).eval(0.0, 0.0);
    }

    /**
     * Rewrites a degree-mode expression into an equivalent radian-mode one that
     * the shared {@link Expression} evaluator (always radians) computes
     * correctly:
     * <ul>
     *   <li>forward functions get their argument converted deg→rad:
     *       {@code sin(A)} → {@code sin((A)*pi/180)} (also cos, tan, sec, csc,
     *       cot and the hyperbolic set is left alone — those take real
     *       arguments, not angles);</li>
     *   <li>inverse functions get their result converted rad→deg:
     *       {@code asin(V)} → {@code (asin(V)*180/pi)} (also acos, atan and the
     *       arc* aliases).</li>
     * </ul>
     *
     * <p>{@link Expression} does not define {@code sec/csc/cot}, so those are
     * expanded here into their reciprocal forms as well.
     *
     * <p>The scan is identifier-aware so substrings never match (e.g. the "sin"
     * inside "asin" is not rewritten, and "e"/"pi" are untouched).
     */
    static String toRadiansExpr(String expr) {
        String s = expr;
        // Reciprocal functions first (expand into sin/cos/tan), degree-aware.
        s = rewriteFunction(s, "csc", "(1/sin(%ARG%))", true);
        s = rewriteFunction(s, "sec", "(1/cos(%ARG%))", true);
        s = rewriteFunction(s, "cot", "(1/tan(%ARG%))", true);
        // Inverse functions: convert the radian result to degrees.
        s = rewriteFunction(s, "arcsin", "(asin(%ARG%)*180/pi)", false);
        s = rewriteFunction(s, "arccos", "(acos(%ARG%)*180/pi)", false);
        s = rewriteFunction(s, "arctan", "(atan(%ARG%)*180/pi)", false);
        s = rewriteFunction(s, "asin", "(asin(%ARG%)*180/pi)", false);
        s = rewriteFunction(s, "acos", "(acos(%ARG%)*180/pi)", false);
        s = rewriteFunction(s, "atan", "(atan(%ARG%)*180/pi)", false);
        // Forward functions: convert the degree argument to radians.
        s = rewriteFunction(s, "sin", "sin((%ARG%)*pi/180)", false);
        s = rewriteFunction(s, "cos", "cos((%ARG%)*pi/180)", false);
        s = rewriteFunction(s, "tan", "tan((%ARG%)*pi/180)", false);
        return s;
    }

    /**
     * Replaces every call {@code name(...)} in {@code s} with {@code template},
     * where {@code %ARG%} is substituted by the (balanced-parenthesis) argument,
     * which is itself recursively rewritten. {@code degreeArg} controls whether,
     * for reciprocal functions, the inner forward call should treat its argument
     * as degrees (we always route reciprocals through sin/cos/tan which the
     * forward pass then converts, so the arg is left verbatim here).
     */
    private static String rewriteFunction(String s, String name, String template, boolean degreeArg) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (matchesIdentifier(s, i, name)) {
                int afterName = i + name.length();
                int j = afterName;
                while (j < n && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                if (j < n && s.charAt(j) == '(') {
                    int close = matchParen(s, j);
                    if (close > j) {
                        String arg = s.substring(j + 1, close);
                        // Recurse so nested calls (e.g. sin(cos(x))) are handled.
                        String rewrittenArg = rewriteFunction(arg, name, template, degreeArg);
                        out.append(template.replace("%ARG%", rewrittenArg));
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** True if {@code name} occurs at {@code i} not surrounded by identifier chars. */
    private static boolean matchesIdentifier(String s, int i, String name) {
        int end = i + name.length();
        if (end > s.length()) {
            return false;
        }
        if (!s.regionMatches(true, i, name, 0, name.length())) {
            return false;
        }
        if (i > 0 && isIdentChar(s.charAt(i - 1))) {
            return false;
        }
        if (end < s.length() && isIdentChar(s.charAt(end))) {
            return false;
        }
        return true;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Index of the ')' matching the '(' at {@code open}, or -1 if unbalanced. */
    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
    }

    // ─── The six functions at one angle ───────────────────────────────────────

    /** sin, cos, tan, csc, sec, cot of a single angle given in the current mode. */
    public SixValues table(double angleInMode) {
        double r = toRadians(angleInMode);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        double tan = Math.tan(r);
        return new SixValues(sin, cos, tan,
                safeRecip(sin), safeRecip(cos), safeRecip(tan));
    }

    private static double safeRecip(double v) {
        return v == 0.0 ? Double.NaN : 1.0 / v;
    }

    /** The six circular functions of one angle. */
    public record SixValues(double sin, double cos, double tan,
                            double csc, double sec, double cot) {
    }

    // ─── Right-triangle solver ─────────────────────────────────────────────────

    /**
     * A fully-solved right triangle: legs {@code a}, {@code b}, hypotenuse
     * {@code c}, acute angles {@code A} (opposite a) and {@code B} (opposite b),
     * both in the current angle mode, plus the right angle C = 90°, and area.
     */
    public record RightTriangle(double a, double b, double c,
                                double A, double B, double area) {
    }

    /**
     * Solves a right triangle from exactly two independent knowns. Pass
     * {@link Double#NaN} for unknowns. Accepts any solvable pair of:
     * leg {@code a}, leg {@code b}, hypotenuse {@code c}, acute angle {@code A}
     * (opposite a) or acute angle {@code B} (opposite b). Angles are in the
     * current mode. The right angle is C.
     *
     * @throws IllegalArgumentException if the inputs are insufficient or inconsistent
     */
    public RightTriangle solveRight(double a, double b, double c, double A, double B) {
        // Normalise the two angles: A + B = 90°.
        double right = mode == AngleMode.DEG ? 90.0 : Math.PI / 2.0;
        if (!Double.isNaN(A) && Double.isNaN(B)) {
            B = right - A;
        } else if (!Double.isNaN(B) && Double.isNaN(A)) {
            A = right - B;
        }

        int sides = count(a, b, c);
        int angles = (Double.isNaN(A) ? 0 : 1); // A and B are now linked

        if (sides + anglePairCount(A) < 2) {
            throw new IllegalArgumentException(
                    "need two knowns (e.g. two sides, or one side and one acute angle)");
        }

        // Resolve from the available combination.
        if (sides >= 2) {
            // Two sides given → Pythagoras fills the third, then angles.
            if (!Double.isNaN(a) && !Double.isNaN(b)) {
                c = Math.hypot(a, b);
            } else if (!Double.isNaN(a) && !Double.isNaN(c)) {
                requirePositive(c > a, "hypotenuse must exceed leg a");
                b = Math.sqrt(c * c - a * a);
            } else if (!Double.isNaN(b) && !Double.isNaN(c)) {
                requirePositive(c > b, "hypotenuse must exceed leg b");
                a = Math.sqrt(c * c - b * b);
            }
            A = fromRadians(Math.atan2(a, b));
            B = fromRadians(Math.atan2(b, a));
        } else {
            // One side + one acute angle.
            double Ar = toRadians(A);
            if (!Double.isNaN(c)) {              // hypotenuse + angle
                a = c * Math.sin(Ar);
                b = c * Math.cos(Ar);
            } else if (!Double.isNaN(a)) {        // opposite leg + angle
                c = a / Math.sin(Ar);
                b = a / Math.tan(Ar);
            } else if (!Double.isNaN(b)) {        // adjacent leg + angle
                c = b / Math.cos(Ar);
                a = b * Math.tan(Ar);
            } else {
                throw new IllegalArgumentException("need a side length with the angle");
            }
        }

        double area = 0.5 * a * b;
        return new RightTriangle(a, b, c, A, B, area);
    }

    private static int anglePairCount(double A) {
        return Double.isNaN(A) ? 0 : 1;
    }

    private static int count(double... xs) {
        int n = 0;
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                n++;
            }
        }
        return n;
    }

    private static void requirePositive(boolean ok, String why) {
        if (!ok) {
            throw new IllegalArgumentException(why);
        }
    }

    // ─── Oblique triangles: law of sines / cosines ────────────────────────────

    /**
     * A general triangle with sides a, b, c and opposite angles A, B, C (angles
     * in the current mode). Produced by the law-of-sines / law-of-cosines
     * solvers. {@code ambiguous} flags the SSA case that has a second solution.
     */
    public record Triangle(double a, double b, double c,
                           double A, double B, double C,
                           boolean ambiguous, Double altB) {
    }

    /**
     * Law of cosines, SSS: three sides → three angles.
     *
     * @throws IllegalArgumentException if the sides violate the triangle inequality
     */
    public Triangle lawCosinesSSS(double a, double b, double c) {
        if (a <= 0 || b <= 0 || c <= 0) {
            throw new IllegalArgumentException("sides must be positive");
        }
        if (a + b <= c || a + c <= b || b + c <= a) {
            throw new IllegalArgumentException("sides violate the triangle inequality");
        }
        double A = Math.acos((b * b + c * c - a * a) / (2 * b * c));
        double B = Math.acos((a * a + c * c - b * b) / (2 * a * c));
        double C = Math.PI - A - B;
        return new Triangle(a, b, c,
                fromRadians(A), fromRadians(B), fromRadians(C), false, null);
    }

    /**
     * Law of cosines, SAS: two sides and the included angle {@code C} (between
     * a and b, in the current mode) → the third side, then the other angles.
     */
    public Triangle lawCosinesSAS(double a, double b, double includedAngleC) {
        if (a <= 0 || b <= 0) {
            throw new IllegalArgumentException("sides must be positive");
        }
        double Cr = toRadians(includedAngleC);
        double c = Math.sqrt(a * a + b * b - 2 * a * b * Math.cos(Cr));
        double A = Math.acos((b * b + c * c - a * a) / (2 * b * c));
        double B = Math.PI - A - Cr;
        return new Triangle(a, b, c,
                fromRadians(A), fromRadians(B), includedAngleC, false, null);
    }

    /**
     * Law of sines, AAS/ASA: two angles ({@code A}, {@code B}, current mode) and
     * one side {@code a} opposite angle A → the rest. (If your known side is not
     * opposite A, relabel so it is.)
     */
    public Triangle lawSinesAAS(double A, double B, double a) {
        double Ar = toRadians(A);
        double Br = toRadians(B);
        double Cr = Math.PI - Ar - Br;
        if (Cr <= 0) {
            throw new IllegalArgumentException("angles must sum to less than a straight angle");
        }
        double k = a / Math.sin(Ar);
        double b = k * Math.sin(Br);
        double c = k * Math.sin(Cr);
        return new Triangle(a, b, c, A, B, fromRadians(Cr), false, null);
    }

    /**
     * Law of sines, SSA (the "ambiguous case"): sides {@code a} and {@code b}
     * with angle {@code A} opposite {@code a} (current mode). Reports the primary
     * solution and, when it exists, the supplementary angle B' of the second
     * triangle.
     *
     * @throws IllegalArgumentException if no triangle exists
     */
    public Triangle lawSinesSSA(double a, double b, double A) {
        double Ar = toRadians(A);
        double sinB = b * Math.sin(Ar) / a;
        if (sinB > 1.0 + 1e-9) {
            throw new IllegalArgumentException("no triangle: side a is too short for angle A");
        }
        sinB = Math.max(-1.0, Math.min(1.0, sinB));
        double Br = Math.asin(sinB);
        double B = fromRadians(Br);
        double Cr = Math.PI - Ar - Br;
        double c = (Cr > 0) ? a * Math.sin(Cr) / Math.sin(Ar) : Double.NaN;

        // Second solution: B' = 180° - B, valid when A + B' < 180°.
        Double altB = null;
        double Br2 = Math.PI - Br;
        if (Ar + Br2 < Math.PI - 1e-9 && Br2 > 1e-9) {
            altB = fromRadians(Br2);
        }
        boolean ambiguous = altB != null;
        return new Triangle(a, b, c, A, B, fromRadians(Cr), ambiguous, altB);
    }

    // ─── General triangle solver (auto-detects the case) ──────────────────────

    /**
     * A fully-solved triangle plus the detected case label and 2D vertex
     * positions suitable for drawing. Vertices are placed with A at the origin,
     * B on the +x axis at distance c (side c = AB), and C above the x-axis; the
     * coordinates are in the same length units as the sides.
     */
    public record SolveResult(Triangle tri, String kind,
                              double ax, double ay,
                              double bx, double by,
                              double cx, double cy,
                              double area) {
    }

    /**
     * Solves a triangle from any sufficient subset of its six parts — sides
     * {@code a, b, c} (opposite the like-named angles) and angles {@code A, B, C}
     * (current mode). Pass {@link Double#NaN} for unknowns. Detects and handles:
     * <ul>
     *   <li><b>SSS</b> — three sides (law of cosines);</li>
     *   <li><b>SAS</b> — two sides and the included angle (law of cosines);</li>
     *   <li><b>ASA / AAS</b> — two angles and any side (law of sines);</li>
     *   <li><b>SSA</b> — two sides and a non-included angle (law of sines,
     *       possibly ambiguous — the primary solution is returned).</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the knowns are insufficient or inconsistent
     */
    public SolveResult solveTriangle(double a, double b, double c,
                                     double A, double B, double C) {
        int sides = countNonNaN(a, b, c);
        int angles = countNonNaN(A, B, C);

        if (sides + angles < 3) {
            throw new IllegalArgumentException(
                    "need any 3 parts (and at least one side), e.g. a=3 b=4 C=90");
        }
        if (sides == 0) {
            throw new IllegalArgumentException("need at least one side length");
        }

        Triangle t;
        String kind;

        if (sides == 3) {
            t = lawCosinesSSS(a, b, c);
            kind = "SSS";
        } else if (angles >= 2) {
            // ASA / AAS: fill the missing angle, then solve from a known side.
            double straight = mode == AngleMode.DEG ? 180.0 : Math.PI;
            if (Double.isNaN(A)) {
                A = straight - B - C;
            } else if (Double.isNaN(B)) {
                B = straight - A - C;
            } else if (Double.isNaN(C)) {
                C = straight - A - B;
            }
            if (!Double.isNaN(a)) {
                t = lawSinesAAS(A, B, a);
            } else if (!Double.isNaN(b)) {
                Triangle u = lawSinesAAS(B, A, b);
                t = new Triangle(u.b(), u.a(), u.c(), u.B(), u.A(), u.C(), false, null);
            } else {
                Triangle u = lawSinesAAS(C, A, c);
                t = new Triangle(u.b(), u.c(), u.a(), u.B(), u.C(), u.A(), false, null);
            }
            kind = "ASA/AAS";
        } else {
            // sides == 2, exactly one angle. SAS if the angle is the included one.
            if (!Double.isNaN(a) && !Double.isNaN(b) && !Double.isNaN(C)) {
                t = lawCosinesSAS(a, b, C);
                kind = "SAS";
            } else if (!Double.isNaN(b) && !Double.isNaN(c) && !Double.isNaN(A)) {
                Triangle u = lawCosinesSAS(b, c, A);
                t = new Triangle(u.c(), u.a(), u.b(), u.C(), u.A(), u.B(), false, null);
                kind = "SAS";
            } else if (!Double.isNaN(a) && !Double.isNaN(c) && !Double.isNaN(B)) {
                Triangle u = lawCosinesSAS(a, c, B);
                t = new Triangle(u.a(), u.c(), u.b(), u.A(), u.C(), u.B(), false, null);
                kind = "SAS";
            } else {
                // SSA: two sides + a non-included angle (opposite one of them).
                if (!Double.isNaN(a) && !Double.isNaN(b) && !Double.isNaN(A)) {
                    t = lawSinesSSA(a, b, A);
                } else if (!Double.isNaN(a) && !Double.isNaN(c) && !Double.isNaN(A)) {
                    Triangle u = lawSinesSSA(a, c, A);
                    t = new Triangle(u.a(), u.c(), u.b(), u.A(), u.C(), u.B(), u.ambiguous(), u.altB());
                } else if (!Double.isNaN(b) && !Double.isNaN(a) && !Double.isNaN(B)) {
                    Triangle u = lawSinesSSA(b, a, B);
                    t = new Triangle(u.b(), u.a(), u.c(), u.B(), u.A(), u.C(), u.ambiguous(), u.altB());
                } else if (!Double.isNaN(b) && !Double.isNaN(c) && !Double.isNaN(B)) {
                    Triangle u = lawSinesSSA(b, c, B);
                    t = new Triangle(u.c(), u.a(), u.b(), u.C(), u.A(), u.B(), u.ambiguous(), u.altB());
                } else if (!Double.isNaN(c) && !Double.isNaN(a) && !Double.isNaN(C)) {
                    Triangle u = lawSinesSSA(c, a, C);
                    t = new Triangle(u.b(), u.c(), u.a(), u.B(), u.C(), u.A(), u.ambiguous(), u.altB());
                } else if (!Double.isNaN(c) && !Double.isNaN(b) && !Double.isNaN(C)) {
                    Triangle u = lawSinesSSA(c, b, C);
                    t = new Triangle(u.a(), u.c(), u.b(), u.A(), u.C(), u.B(), u.ambiguous(), u.altB());
                } else {
                    throw new IllegalArgumentException(
                            "two sides need an angle opposite one of them (SSA) "
                                    + "or the included angle (SAS)");
                }
                kind = "SSA";
            }
        }

        // Place vertices: A at origin, B at (c, 0), C found from side b and angle A.
        double Ar = toRadians(t.A());
        double ax = 0.0, ay = 0.0;
        double bx = t.c(), by = 0.0;
        double cxp = t.b() * Math.cos(Ar);
        double cyp = t.b() * Math.sin(Ar);
        double area = 0.5 * t.a() * t.b() * Math.sin(toRadians(t.C()));

        return new SolveResult(t, kind, ax, ay, bx, by, cxp, cyp, Math.abs(area));
    }

    private static int countNonNaN(double... xs) {
        int n = 0;
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                n++;
            }
        }
        return n;
    }

    // ─── Reference sheet ───────────────────────────────────────────────────────

    /** A compact list of commonly-used trig identities for {@code /trig id}. */
    public static String[] identities() {
        return new String[] {
            "Pythagorean:  sin^2 + cos^2 = 1 · 1 + tan^2 = sec^2 · 1 + cot^2 = csc^2",
            "Reciprocal:   csc = 1/sin · sec = 1/cos · cot = 1/tan",
            "Quotient:     tan = sin/cos · cot = cos/sin",
            "Even/odd:     sin(-x) = -sin x · cos(-x) = cos x · tan(-x) = -tan x",
            "Sum:          sin(a+b) = sin a cos b + cos a sin b",
            "              cos(a+b) = cos a cos b - sin a sin b",
            "              tan(a+b) = (tan a + tan b)/(1 - tan a tan b)",
            "Difference:   sin(a-b) = sin a cos b - cos a sin b",
            "              cos(a-b) = cos a cos b + sin a sin b",
            "Double:       sin 2x = 2 sin x cos x",
            "              cos 2x = cos^2 x - sin^2 x = 2cos^2 x - 1 = 1 - 2sin^2 x",
            "              tan 2x = 2 tan x /(1 - tan^2 x)",
            "Half:         sin^2 x = (1 - cos 2x)/2 · cos^2 x = (1 + cos 2x)/2",
            "Law of sines: a/sin A = b/sin B = c/sin C",
            "Law of cos.:  c^2 = a^2 + b^2 - 2ab cos C",
        };
    }
}
