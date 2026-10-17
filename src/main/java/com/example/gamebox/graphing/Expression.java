package com.example.gamebox.graphing;

import java.util.ArrayList;
import java.util.List;

/**
 * A recursive-descent parser/evaluator for two-variable expressions
 * {@code f(x, y)} used by the graphing calculator. It compiles an expression
 * string once into a {@link Node} tree and evaluates it cheaply for many points.
 *
 * <h2>Operations</h2>
 * <ul>
 *   <li>Arithmetic: {@code + - * / ^}, parentheses, unary minus, implicit
 *       multiplication ({@code 2x}, {@code 3sin(x)});</li>
 *   <li>Modulo: {@code a mod b} or {@code a % b};</li>
 *   <li>Variables {@code x}, {@code y}; constants {@code pi}, {@code e};</li>
 *   <li>Literal absolute value: {@code |expr|} (same as {@code abs(expr)});</li>
 *   <li>Comparisons for constraints: {@code < <= > >= = == !=}, including the
 *       chained form {@code a <= x <= b};</li>
 *   <li>Unary functions: {@code sin cos tan asin acos atan arcsin arccos arctan
 *       sinh cosh tanh sqrt cbrt abs exp ln log log2 floor ceil round sign sgn};</li>
 *   <li>Multi-argument functions: {@code min(a,b) max(a,b) mod(a,b)
 *       log(base,x) root(n,x)};</li>
 *   <li>Numerical calculus (in {@code x}): {@code derivative(expr)} (alias
 *       {@code deriv}, {@code d/dx}) and {@code integral(expr)} (antiderivative
 *       from 0, alias {@code integrate}).</li>
 * </ul>
 */
public final class Expression {

    /** A compiled expression node over variables (x, y). */
    public interface Node {
        double eval(double x, double y);
    }

    private final Node root;
    private final String source;

    private Expression(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    public double eval(double x, double y) {
        return root.eval(x, y);
    }

    /** Convenience single-variable evaluation (y defaults to 0). */
    public double eval(double x) {
        return root.eval(x, 0);
    }

    public String source() {
        return source;
    }

    /** Parses an arithmetic/relational expression (comparisons allowed). */
    public static Expression parse(String input) {
        Parser p = new Parser(preprocess(input));
        Node n = p.parseRelation();
        p.expectEnd();
        return new Expression(n, input.trim());
    }

    /** Light textual normalisation for a few notations before parsing. */
    private static String preprocess(String s) {
        String r = s;
        // d/dx f  →  derivative(f)
        r = r.replaceAll("(?i)d\\s*/\\s*dx\\s*", "derivative");
        // nth-root: sqrt[n]{expr} or sqrt[n](expr) → root(n, expr)
        r = convertNthRoot(r);
        // LaTeX-ish \sqrt, \sin, etc.: drop a leading backslash before names.
        r = r.replaceAll("\\\\([a-zA-Z]+)", "$1");
        // \lfloor x \rfloor → floor(x), \lceil x \rceil → ceil(x)
        r = r.replace("lfloor", "floor(").replace("rfloor", ")");
        r = r.replace("lceil", "ceil(").replace("rceil", ")");
        return r;
    }

    /** Rewrites {@code sqrt[n]{body}} / {@code sqrt[n](body)} into {@code root(n, body)}. */
    private static String convertNthRoot(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (s.regionMatches(true, i, "sqrt[", 0, 5)) {
                int close = s.indexOf(']', i + 5);
                if (close > 0) {
                    String n = s.substring(i + 5, close).trim();
                    int bodyStart = close + 1;
                    char open = bodyStart < s.length() ? s.charAt(bodyStart) : '\0';
                    char closeCh = open == '{' ? '}' : (open == '(' ? ')' : '\0');
                    if (closeCh != '\0') {
                        int depth = 0, j = bodyStart;
                        for (; j < s.length(); j++) {
                            char c = s.charAt(j);
                            if (c == open) depth++;
                            else if (c == closeCh) { depth--; if (depth == 0) break; }
                        }
                        if (j < s.length()) {
                            String body = s.substring(bodyStart + 1, j);
                            out.append("root(").append(n).append(",")
                               .append(convertNthRoot(body)).append(")");
                            i = j + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** Thrown when an expression cannot be parsed. */
    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    // ─── Parser ──────────────────────────────────────────────────────────────

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void expectEnd() {
            skipWs();
            if (pos < s.length()) {
                throw new ParseException("Unexpected '" + s.charAt(pos) + "' at position " + pos);
            }
        }

        /** expr with an optional chained comparison → 1.0 / 0.0 (for constraints). */
        Node parseRelation() {
            Node left = parseExpr();
            skipWs();
            String op = matchRelOp();
            if (op == null) {
                return left;
            }
            Node mid = parseExpr();
            Node first = compare(left, op, mid);

            // Chained inequality: a <= x <= b  →  (a<=x) AND (x<=b)
            skipWs();
            String op2 = matchRelOp();
            if (op2 == null) {
                return first;
            }
            Node right = parseExpr();
            Node second = compare(mid, op2, right);
            Node f = first, se = second;
            return (x, y) -> (f.eval(x, y) != 0 && se.eval(x, y) != 0) ? 1 : 0;
        }

        private Node compare(Node l, String op, Node r) {
            switch (op) {
                case "<":  return (x, y) -> l.eval(x, y) <  r.eval(x, y) ? 1 : 0;
                case "<=": return (x, y) -> l.eval(x, y) <= r.eval(x, y) ? 1 : 0;
                case ">":  return (x, y) -> l.eval(x, y) >  r.eval(x, y) ? 1 : 0;
                case ">=": return (x, y) -> l.eval(x, y) >= r.eval(x, y) ? 1 : 0;
                case "!=": return (x, y) -> l.eval(x, y) != r.eval(x, y) ? 1 : 0;
                default:   return (x, y) -> l.eval(x, y) == r.eval(x, y) ? 1 : 0;
            }
        }

        private String matchRelOp() {
            skipWs();
            if (pos >= s.length()) {
                return null;
            }
            char c = peek();
            char n = pos + 1 < s.length() ? s.charAt(pos + 1) : '\0';
            if (c == '<') { pos++; if (peek() == '=') { pos++; return "<="; } return "<"; }
            if (c == '>') { pos++; if (peek() == '=') { pos++; return ">="; } return ">"; }
            if (c == '=') { pos++; if (peek() == '=') { pos++; } return "=="; }
            if (c == '!' && n == '=') { pos += 2; return "!="; }
            return null;
        }

        Node parseExpr() {
            Node left = parseTerm();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '+') {
                    pos++;
                    Node r = parseTerm();
                    Node l = left;
                    left = (x, y) -> l.eval(x, y) + r.eval(x, y);
                } else if (c == '-') {
                    pos++;
                    Node r = parseTerm();
                    Node l = left;
                    left = (x, y) -> l.eval(x, y) - r.eval(x, y);
                } else {
                    return left;
                }
            }
        }

        Node parseTerm() {
            Node left = parseUnary();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*') {
                    pos++;
                    Node r = parseUnary();
                    Node l = left;
                    left = (x, y) -> l.eval(x, y) * r.eval(x, y);
                } else if (c == '/') {
                    pos++;
                    Node r = parseUnary();
                    Node l = left;
                    left = (x, y) -> l.eval(x, y) / r.eval(x, y);
                } else if (c == '%') {
                    pos++;
                    Node r = parseUnary();
                    Node l = left;
                    left = (x, y) -> mod(l.eval(x, y), r.eval(x, y));
                } else if (matchKeyword("mod")) {
                    Node r = parseUnary();
                    Node l = left;
                    left = (x, y) -> mod(l.eval(x, y), r.eval(x, y));
                } else {
                    return left;
                }
            }
        }

        /** Matches a lowercase keyword followed by a non-identifier boundary. */
        private boolean matchKeyword(String kw) {
            skipWs();
            int end = pos + kw.length();
            if (end > s.length()) {
                return false;
            }
            if (!s.substring(pos, end).toLowerCase().equals(kw)) {
                return false;
            }
            // must not be part of a longer identifier
            if (end < s.length() && Character.isLetterOrDigit(s.charAt(end))) {
                return false;
            }
            pos = end;
            return true;
        }

        Node parseUnary() {
            skipWs();
            char c = peek();
            if (c == '-') {
                pos++;
                Node u = parseUnary();
                return (x, y) -> -u.eval(x, y);
            }
            if (c == '+') {
                pos++;
                return parseUnary();
            }
            return parsePower();
        }

        /** Power binds tighter than unary minus (so -x^2 = -(x^2)); right-assoc. */
        Node parsePower() {
            Node base = parsePrimary();
            skipWs();
            if (peek() == '^') {
                pos++;
                Node exp = parseUnary();
                Node b = base;
                return (x, y) -> Math.pow(b.eval(x, y), exp.eval(x, y));
            }
            return base;
        }

        Node parsePrimary() {
            skipWs();
            char c = peek();
            Node node;
            if (c == '(') {
                pos++;
                node = parseExpr();
                skipWs();
                if (peek() != ')') {
                    throw new ParseException("Expected ')' at position " + pos);
                }
                pos++;
            } else if (c == '|') {
                // Literal absolute value |expr|
                pos++;
                Node inner = parseExpr();
                skipWs();
                if (peek() != '|') {
                    throw new ParseException("Expected closing '|'");
                }
                pos++;
                Node in = inner;
                node = (x, y) -> Math.abs(in.eval(x, y));
            } else if (Character.isDigit(c) || c == '.') {
                node = parseNumber();
                skipWs();
                char d = peek();
                if (d == '(' || (Character.isLetter(d) && !isModAhead())) {
                    Node coeff = node;
                    Node rest = parsePower();
                    node = (x, y) -> coeff.eval(x, y) * rest.eval(x, y);
                }
            } else if (Character.isLetter(c)) {
                node = parseIdentifier();
            } else {
                throw new ParseException("Unexpected '" + (c == '\0' ? "end of input" : c)
                        + "' at position " + pos);
            }
            return node;
        }

        /** True if the upcoming identifier is the 'mod' keyword (so we don't treat
         *  it as implicit multiplication after a number). */
        private boolean isModAhead() {
            skipWs();
            int end = pos;
            while (end < s.length() && Character.isLetter(s.charAt(end))) {
                end++;
            }
            return s.substring(pos, end).equalsIgnoreCase("mod");
        }

        Node parseNumber() {
            int start = pos;
            while (pos < s.length()
                    && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = save;
                }
            }
            double value;
            try {
                value = Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ParseException("Bad number '" + s.substring(start, pos) + "'");
            }
            final double v = value;
            return (x, y) -> v;
        }

        Node parseIdentifier() {
            int start = pos;
            while (pos < s.length() && Character.isLetterOrDigit(s.charAt(pos))) {
                pos++;
            }
            String name = s.substring(start, pos).toLowerCase();

            switch (name) {
                case "x": return (x, y) -> x;
                case "y": return (x, y) -> y;
                case "pi": return (x, y) -> Math.PI;
                case "e": return (x, y) -> Math.E;
                default: break;
            }

            // Function call: name '(' args ')'  OR  bare "name primary" (e.g. sin y)
            skipWs();
            if (peek() == '(') {
                pos++;
                List<Node> args = new ArrayList<>();
                skipWs();
                if (peek() != ')') {
                    args.add(parseExpr());
                    skipWs();
                    while (peek() == ',') {
                        pos++;
                        args.add(parseExpr());
                        skipWs();
                    }
                }
                if (peek() != ')') {
                    throw new ParseException("Expected ')' after " + name + "(…)");
                }
                pos++;
                return applyFunction(name, args);
            }
            if (isUnaryFunction(name)) {
                Node arg = parsePower();
                List<Node> args = new ArrayList<>();
                args.add(arg);
                return applyFunction(name, args);
            }
            throw new ParseException("Unknown name '" + name + "'");
        }

        private static boolean isUnaryFunction(String name) {
            switch (name) {
                case "sin": case "cos": case "tan":
                case "asin": case "acos": case "atan":
                case "arcsin": case "arccos": case "arctan":
                case "sinh": case "cosh": case "tanh":
                case "sqrt": case "cbrt": case "abs": case "exp":
                case "ln": case "log2":
                case "floor": case "ceil": case "round": case "sign": case "sgn":
                case "derivative": case "deriv": case "integral": case "integrate":
                    return true;
                default:
                    return false;
            }
        }

        private Node applyFunction(String name, List<Node> args) {
            // Multi-argument functions
            switch (name) {
                case "min": requireArgs(name, args, 2); {
                    Node a = args.get(0), b = args.get(1);
                    return (x, y) -> Math.min(a.eval(x, y), b.eval(x, y));
                }
                case "max": requireArgs(name, args, 2); {
                    Node a = args.get(0), b = args.get(1);
                    return (x, y) -> Math.max(a.eval(x, y), b.eval(x, y));
                }
                case "mod": requireArgs(name, args, 2); {
                    Node a = args.get(0), b = args.get(1);
                    return (x, y) -> mod(a.eval(x, y), b.eval(x, y));
                }
                case "root": requireArgs(name, args, 2); {
                    Node n = args.get(0), v = args.get(1);
                    return (x, y) -> nthRoot(v.eval(x, y), n.eval(x, y));
                }
                case "log":
                    // log(x) = base 10; log(base, x) = log base
                    if (args.size() == 1) {
                        Node a = args.get(0);
                        return (x, y) -> Math.log10(a.eval(x, y));
                    }
                    requireArgs(name, args, 2); {
                        Node base = args.get(0), v = args.get(1);
                        return (x, y) -> Math.log(v.eval(x, y)) / Math.log(base.eval(x, y));
                    }
                default:
                    break;
            }

            // Single-argument functions
            requireArgs(name, args, 1);
            Node a = args.get(0);
            switch (name) {
                case "sin": return (x, y) -> Math.sin(a.eval(x, y));
                case "cos": return (x, y) -> Math.cos(a.eval(x, y));
                case "tan": return (x, y) -> Math.tan(a.eval(x, y));
                case "asin": case "arcsin": return (x, y) -> Math.asin(a.eval(x, y));
                case "acos": case "arccos": return (x, y) -> Math.acos(a.eval(x, y));
                case "atan": case "arctan": return (x, y) -> Math.atan(a.eval(x, y));
                case "sinh": return (x, y) -> Math.sinh(a.eval(x, y));
                case "cosh": return (x, y) -> Math.cosh(a.eval(x, y));
                case "tanh": return (x, y) -> Math.tanh(a.eval(x, y));
                case "sqrt": return (x, y) -> Math.sqrt(a.eval(x, y));
                case "cbrt": return (x, y) -> Math.cbrt(a.eval(x, y));
                case "abs": return (x, y) -> Math.abs(a.eval(x, y));
                case "exp": return (x, y) -> Math.exp(a.eval(x, y));
                case "ln": return (x, y) -> Math.log(a.eval(x, y));
                case "log2": return (x, y) -> Math.log(a.eval(x, y)) / Math.log(2);
                case "floor": return (x, y) -> Math.floor(a.eval(x, y));
                case "ceil": return (x, y) -> Math.ceil(a.eval(x, y));
                case "round": return (x, y) -> (double) Math.round(a.eval(x, y));
                case "sign": case "sgn": return (x, y) -> Math.signum(a.eval(x, y));
                case "derivative": case "deriv":
                    // Numerical derivative in x (central difference).
                    return (x, y) -> {
                        double h = 1e-5 * (1 + Math.abs(x));
                        return (a.eval(x + h, y) - a.eval(x - h, y)) / (2 * h);
                    };
                case "integral": case "integrate":
                    // Numerical antiderivative from 0 to x (Simpson's rule).
                    return (x, y) -> integrateFrom0(a, x, y);
                default:
                    throw new ParseException("Unknown function '" + name + "'");
            }
        }

        private static void requireArgs(String name, List<Node> args, int n) {
            if (args.size() != n) {
                throw new ParseException(name + " expects " + n + " argument"
                        + (n == 1 ? "" : "s") + ", got " + args.size());
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }

    // ─── Numeric helpers ──────────────────────────────────────────────────────

    /** Floored modulo (matches mathematical a mod b for positive b). */
    private static double mod(double a, double b) {
        if (b == 0) {
            return Double.NaN;
        }
        double r = a - Math.floor(a / b) * b;
        return r;
    }

    /** Real nth root, handling odd roots of negatives (e.g. cube root of -8). */
    private static double nthRoot(double value, double n) {
        if (n == 0) {
            return Double.NaN;
        }
        if (value < 0) {
            double rn = Math.rint(n);
            if (Math.abs(rn - n) < 1e-9 && ((long) rn & 1L) == 1L) {
                return -Math.pow(-value, 1.0 / n);
            }
            return Double.NaN; // even/non-integer root of a negative
        }
        return Math.pow(value, 1.0 / n);
    }

    /** Simpson's-rule antiderivative of f from 0 to x (even sub-interval count). */
    private static double integrateFrom0(Node f, double x, double y) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return 0;
        }
        int n = 200; // sub-intervals (even)
        double h = x / n;
        double sum = f.eval(0, y) + f.eval(x, y);
        for (int i = 1; i < n; i++) {
            double xi = i * h;
            sum += (i % 2 == 0 ? 2 : 4) * f.eval(xi, y);
        }
        return sum * h / 3.0;
    }
}
