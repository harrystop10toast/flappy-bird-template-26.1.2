package com.example.gamebox.mixin;

import com.example.gamebox.child.ChildMode;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * THE GATE TEST. In the <b>child client only</b>, count calls to
 * {@link GameRenderer#render} and log the frame rate once per second with the
 * marker {@code [GAMEBOX-GATE]}. This is the decisive experiment for the
 * second-client approach on macOS Apple Silicon:
 *
 * <blockquote>Launch the child, bring another app in front for ~60 seconds, and
 * watch the child's log. If {@code [GAMEBOX-GATE] fps=N} keeps printing with
 * {@code N > 0}, a born-hidden GLFW window keeps rendering and the full
 * second-client path is viable (confirmed working on Apple Silicon).</blockquote>
 *
 * <p>Does nothing in the host (normal) client.
 */
@Mixin(GameRenderer.class)
public class GameRendererFpsMixin {

    private static final Logger GATE_LOG = LoggerFactory.getLogger("gamebox/gate");

    private static int gamebox$frames;
    private static long gamebox$windowStartNanos;

    @Inject(method = "render", at = @At("TAIL"))
    private void gamebox$countFps(CallbackInfo ci) {
        if (!ChildMode.isChild()) {
            return;
        }
        // Drive the child's frame capture from here: this runs on the render
        // thread every frame even at the title screen (where the HUD element does
        // not render), which is why capture was never happening before.
        com.example.gamebox.child.ChildClient child = com.example.gamebox.child.ChildClient.get();
        if (child != null) {
            child.onRenderFrame();
        }

        long now = System.nanoTime();
        if (gamebox$windowStartNanos == 0L) {
            gamebox$windowStartNanos = now;
        }
        gamebox$frames++;
        long elapsed = now - gamebox$windowStartNanos;
        if (elapsed >= 1_000_000_000L) {
            double fps = gamebox$frames * 1e9 / elapsed;
            GATE_LOG.info("[GAMEBOX-GATE] fps={} (hidden-window render loop alive)",
                    String.format(java.util.Locale.ROOT, "%.1f", fps));
            gamebox$frames = 0;
            gamebox$windowStartNanos = now;
        }
    }
}
