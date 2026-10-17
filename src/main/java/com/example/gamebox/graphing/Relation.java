package com.example.gamebox.graphing;

import java.util.ArrayList;
import java.util.List;

/**
 * A graphable relation: an equation plus zero or more constraints.
 *
 * <h2>Equation</h2>
 * The input is split on a single top-level {@code '='} into {@code lhs} and
 * {@code rhs} (each a two-variable {@link Expression}); the relation's implicit
 * form is {@code F(x,y) = lhs - rhs}, whose zero set is the curve. Examples:
 * <ul>
 *   <li>{@code y = sin(x)}      → F = sin(x) - y</li>
 *   <li>{@code x = sin(y)}      → F = sin(y) - x</li>
 *   <li>{@code 1/y = x}         → F = 1/y - x</li>
 *   <li>{@code x^2 + y^2 = 9}   → F = x^2+y^2 - 9  (a circle)</li>
 * </ul>
 * A bare expression with no {@code '='} is treated as {@code y = expr}. A leading
 * {@code "y ="} / {@code "f(x) ="} is just the normal equation form and needs no
 * special handling.
 *
 * <h2>Constraints</h2>
 * Any number of trailing {@code {…}} blocks restrict where the curve draws, e.g.
 * {@code y = sin(x) {y<0}} or {@code x^2+y^2=9 {x>0}{y>0}}. Each block is a
 * boolean {@link Expression} (using comparison operators); a point is drawn only
 * if every constraint evaluates to true (non-zero) there.
 */
public final class Relation {

    private final String source;        // original text (trimmed)
    private final Expression f;         // F(x,y) whose zero set is the curve
    private final List<Expression> constraints;
    private final String error;         // parse error, or null

    private Relation(String source, Expression f, List<Expression> constraints, String error) {
        this.source = source;
        this.f = f;
        this.constraints = constraints;
        this.error = error;
    }

    public String source() {
        return source;
    }

    public String error() {
        return error;
    }

    public boolean ok() {
        return error == null && f != null;
    }

    public boolean hasConstraints() {
        return !constraints.isEmpty();
    }

    public int constraintCount() {
        return constraints.size();
    }

    /** F(x,y); its sign change between neighbouring samples marks the curve. */
    public double f(double x, double y) {
        return f.eval(x, y);
    }

    /** True if every constraint holds (is non-zero) at (x,y). */
    public boolean constraintsHold(double x, double y) {
        for (Expression c : constraints) {
            double v = c.eval(x, y);
            if (!(v != 0.0) || Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }

    // ─── Parsing ────────────────────────────────────────────────────────────

    public static Relation parse(String input) {
        String src = input.trim();
        try {
            // Peel off trailing {constraint} blocks first.
            List<String> constraintTexts = new ArrayList<>();
            String body = extractConstraints(src, constraintTexts);

            // Split the equation body on a single top-level '=' (not <=,>=,==,!=).
            int eq = topLevelEquals(body);
            Expression f;
            if (eq < 0) {
                // Bare expression → y = expr  →  F = expr - y
                Expression rhs = Expression.parse(body);
                f = Expression.parse("(" + body + ") - y");
                // (parse twice is fine; keep rhs referenced to validate it)
                if (rhs == null) {
                    throw new Expression.ParseException("empty expression");
                }
            } else {
                String lhsText = body.substring(0, eq).trim();
                String rhsText = body.substring(eq + 1).trim();
                if (lhsText.isEmpty() || rhsText.isEmpty()) {
                    throw new Expression.ParseException("equation needs both sides of '='");
                }
                // F = (lhs) - (rhs)
                f = Expression.parse("(" + lhsText + ") - (" + rhsText + ")");
            }

            List<Expression> cons = new ArrayList<>();
            for (String ct : constraintTexts) {
                cons.add(Expression.parse(ct));
            }
            return new Relation(src, f, cons, null);
        } catch (RuntimeException e) {
            return new Relation(src, null, new ArrayList<>(), e.getMessage());
        }
    }

    /**
     * Removes trailing {@code {…}} blocks from the input, adding their inner text
     * (as boolean constraints) to {@code out}, and returns the remaining equation
     * body.
     *
     * <p>A brace may also use the Desmos-style domain form {@code {body : cond}}
     * (or {@code {body ; cond}}): the part before the colon becomes part of the
     * equation body and the part after becomes a constraint. Multiple conditions
     * may be comma-separated after the colon.
     */
    private static String extractConstraints(String s, List<String> out) {
        String body = s;
        while (true) {
            String t = body.trim();
            if (t.isEmpty() || t.charAt(t.length() - 1) != '}') {
                return t;
            }
            int open = t.lastIndexOf('{');
            if (open < 0) {
                return t; // unbalanced — let the parser report it
            }
            String inner = t.substring(open + 1, t.length() - 1).trim();
            String prefix = t.substring(0, open).trim();

            int colon = topLevelColon(inner);
            if (colon >= 0) {
                // {body : conditions} — body joins the equation, conditions are
                // constraints (comma-separated allowed).
                String eqPart = inner.substring(0, colon).trim();
                String condPart = inner.substring(colon + 1).trim();
                for (String cond : splitTopLevelCommas(condPart)) {
                    if (!cond.isBlank()) {
                        out.add(0, cond.trim());
                    }
                }
                // The brace's body becomes the equation body (prepended to anything
                // already outside the braces, which is normally empty).
                body = (prefix + " " + eqPart).trim();
                return body;
            }

            if (!inner.isEmpty()) {
                out.add(0, inner);
            }
            body = prefix;
        }
    }

    /** Index of a top-level ':' (depth 0), or -1. */
    private static int topLevelColon(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{') depth++;
            else if (c == ')' || c == '}') depth--;
            else if ((c == ':' || c == ';') && depth == 0) return i;
        }
        return -1;
    }

    /** Splits on top-level commas (depth 0). */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{') depth++;
            else if (c == ')' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** Finds the index of a lone top-level '=' (ignoring <=,>=,==,!=,parens). */
    private static int topLevelEquals(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? s.charAt(i - 1) : '\0';
                char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
                if (prev == '<' || prev == '>' || prev == '!' || prev == '='
                        || next == '=') {
                    continue; // part of a comparison operator
                }
                return i;
            }
        }
        return -1;
    }
}
