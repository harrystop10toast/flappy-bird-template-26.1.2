package com.example.gamebox.graphing;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * High-level control surface for the graphing calculator, driven by the
 * {@code /graphing} command and the shared focus hotkey (K). Owns the single
 * {@link GraphingEngine}, the on/off toggle, and the input overlay.
 *
 * <p>Rendering behaviour mirrors DOOM / Super Mario: while the calculator is
 * "on" it owns the chat box (clearing + re-posting a colored Braille frame);
 * turning it off releases the chat so normal messages are readable. Equations
 * persist across on/off toggles — only {@code restart} clears them.
 */
public final class GraphingController {

    private final GraphingEngine engine = new GraphingEngine();
    private boolean on = false;
    /** When paused, the calculator stays "on" but stops re-posting to chat, so a
     *  printed equation listing is not instantly buried by the next frame. */
    private boolean paused = false;

    // ─── On/off toggle ─────────────────────────────────────────────────────

    /** Toggles the calculator on/off (does NOT delete equations). */
    public synchronized boolean toggle() {
        on = !on;
        if (on) {
            paused = false;
            focus();
        } else {
            releaseChat();
        }
        return on;
    }

    public synchronized boolean isOn() {
        return on;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Freezes chat rendering without turning the calculator off. */
    public synchronized void pause() {
        paused = true;
    }

    /** Resumes chat rendering (re-grabs input via the overlay). */
    public synchronized void resume() {
        if (on) {
            paused = false;
            focus();
        }
    }

    /** The calculator owns the chat box only while on and not paused. */
    public synchronized boolean ownsChat() {
        return on && !paused;
    }

    private void turnOnIfNeeded() {
        if (!on) {
            on = true;
            paused = false;
        }
    }

    // ─── Equation management (returns user feedback text) ─────────────────────

    /** Adds an equation/relation and ensures the calculator is on. */
    public synchronized String addEquation(String text) {
        if (text == null || text.isBlank()) {
            return "Nothing to add — give a relation, e.g. /graphing x=sin(y) {y<0}.";
        }
        GraphingEngine.Entry e = engine.add(text);
        turnOnIfNeeded();
        if (e.error() != null) {
            return "Added #" + e.id() + " \"" + e.text() + "\" §cbut it failed to parse: " + e.error();
        }
        return "Added #" + e.id() + ": " + e.text();
    }

    /** Modifies (or, with blank text, deletes) the relation at id. */
    public synchronized String modifyEquation(int id, String text) {
        if (text == null || text.isBlank()) {
            boolean removed = engine.delete(id);
            return removed ? "Deleted #" + id + "." : "No equation #" + id + " to delete.";
        }
        GraphingEngine.Entry e = engine.modify(id, text);
        if (e == null) {
            return "No equation #" + id + ". Use /graphing <equation> to add one.";
        }
        turnOnIfNeeded();
        if (e.error() != null) {
            return "Modified #" + id + " \"" + e.text() + "\" §cbut it failed to parse: " + e.error();
        }
        return "Modified #" + id + ": " + e.text();
    }

    /** Deletes every equation (keeps the calculator on/off state as-is). */
    public synchronized String restart() {
        int n = engine.count();
        engine.clear();
        engine.resetView();
        return "Cleared " + n + " equation" + (n == 1 ? "" : "s") + " and reset the view.";
    }

    /** A multi-line, labeled listing of all equations with their numeric ids. */
    public synchronized List<String> listing() {
        java.util.List<String> out = new java.util.ArrayList<>();
        List<GraphingEngine.Entry> es = engine.entries();
        out.add("§b§l── Equations ──");
        if (es.isEmpty()) {
            out.add("§7(none — add one with §e/graphing <equation>§7)");
            return out;
        }
        for (GraphingEngine.Entry e : es) {
            String colorHex = String.format("#%06X", e.color());
            // Tag the id in the curve's own color so the list is a legend.
            String idTag = colorComponentPrefix(e.color()) + "#" + e.id() + "§r";
            String line = "§7id " + idTag + "§7:  §f" + e.text();
            if (e.error() != null) {
                line += "  §c(parse error: " + e.error() + ")";
            }
            out.add(line + "  §8" + colorHex);
        }
        out.add("§8edit: §7/graphing <id> <eqn> · delete: §7/graphing <id> · clear: §7/graphing restart");
        return out;
    }

    /** Returns a §-color prefix matching an 0xRRGGBB value (nearest legacy code). */
    private static String colorComponentPrefix(int rgb) {
        // Map to the closest of Minecraft's 16 legacy chat colors for the id tag.
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        char code;
        boolean bright = (r + g + b) > 384;
        if (r > g && r > b) code = bright ? 'c' : '4';
        else if (g > r && g > b) code = bright ? 'a' : '2';
        else if (b > r && b > g) code = bright ? 'b' : '9';
        else if (r > b && g > b) code = bright ? 'e' : '6'; // yellow-ish
        else if (r > g && b > g) code = bright ? 'd' : '5'; // magenta-ish
        else code = bright ? 'f' : '7';
        return "§" + code;
    }

    /**
     * Pauses live rendering first (so the printed list is not instantly buried by
     * the next frame), then returns the labeled listing. Used by {@code /graphing
     * all}. Re-grab with the focus key (K) or {@code /graphing} to resume.
     *
     * <p>We only pause and drop the input overlay here — we must NOT clear chat,
     * because the caller prints the listing synchronously right after this
     * returns, whereas any {@code mc.execute(...)} clear would run on the next
     * tick and wipe that freshly-printed list.
     */
    public synchronized List<String> listAll() {
        if (on) {
            pause();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                // Client command handlers run on the client thread, so clear the
                // stale graph frame synchronously *now* (before the caller prints
                // the list). Dropping the input overlay can be deferred.
                if (mc.gui != null) {
                    mc.gui.getChat().clearMessages(false);
                }
                if (mc.screen instanceof GraphingInputScreen) {
                    mc.execute(() -> {
                        if (mc.screen instanceof GraphingInputScreen) {
                            mc.setScreen(null);
                        }
                    });
                }
            }
        }
        return listing();
    }

    // ─── Pan / zoom (called from the input overlay) ──────────────────────────

    public void pan(double fx, double fy) {
        engine.panByViewFraction(fx, fy);
    }

    public void zoomIn() {
        engine.zoom(1.25);
    }

    public void zoomOut() {
        engine.zoom(1.0 / 1.25);
    }

    // ─── Chat ownership / focus ───────────────────────────────────────────────

    /** Renders the graph into chat each tick while on and not paused; client thread. */
    public void renderToChat() {
        if (on && !paused) {
            engine.renderToChat();
        }
    }

    /** Opens the input overlay to grab the keyboard (bound to the K focus key). */
    public synchronized void focus() {
        if (!on) {
            return;
        }
        paused = false; // grabbing input resumes live rendering
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new GraphingInputScreen(this)));
        }
    }

    private void releaseChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                if (mc.screen instanceof GraphingInputScreen) {
                    mc.setScreen(null);
                }
                mc.gui.getChat().clearMessages(false);
                mc.gui.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§b[GameBox] §7Graphing calculator off — chat is yours. "
                                        + "§e/graphing §7to toggle it back on."));
            });
        }
    }
}
