package com.example.gamebox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Random;

/**
 * Flappy Bird, rendered into the Minecraft chat box using Unicode Braille
 * (exactly like the original template): each frame clears chat and posts a fresh
 * client-side system message built from Braille rows, so the chat window animates
 * the game. A header line shows score / best / status.
 *
 * <p>Flappy Bird uses its OWN fixed canvas: 32 x 10 Braille cells (64 x 40 px,
 * a 32:10 ratio), independent of the GameBox size slider, and it plays in the
 * player's normal chat (no forced 720px height — that would be unplayable).
 *
 * <p>Unlike the old HUD path, Flappy Bird drives the chat box directly.
 */
public final class FlappyBirdGame implements Game {

    // Flappy Bird uses its OWN fixed canvas with a hardcoded 32:10 (cells) ratio,
    // independent of the GameBox size slider. 32 cols x 10 rows = 64 x 40 pixels;
    // 10 Braille lines + 1 header = 11 chat lines, which fits normal (vanilla) chat.
    private static final int COLS = 32;
    private static final int ROWS = 10;
    private static final int PX_W = COLS * 2; // 64
    private static final int PX_H = ROWS * 4; // 40

    private final BrailleDisplay display = new BrailleDisplay(PX_W, PX_H);

    // Physics
    private static final int BIRD_X = 12;
    private static final float GRAVITY = 0.65f;
    private static final float FLAP_IMPULSE = -3.4f;
    private static final float MAX_FALL = 3.8f;

    // Pipes
    private static final int PIPE_WIDTH = 6;
    private static final int PIPE_GAP = 12;
    private static final int PIPE_DIST = 38;
    private static final int PIPE_COUNT = 2;

    private static final int TICKS_PER_FRAME = 2;

    private boolean sessionActive = false;
    private boolean running = false;
    private boolean gameOver = false;
    /** True after an explicit stop; the flap key must NOT auto-start until /flappybird. */
    private boolean terminated = true;
    private int score = 0;
    private int highScore = 0;
    private int tickCounter = 0;

    private float birdY = 14.0f;
    private float birdVel = 0f;

    private final float[] pipeX = new float[PIPE_COUNT];
    private final int[] pipeGapY = new int[PIPE_COUNT];
    private final boolean[] scored = new boolean[PIPE_COUNT];

    private final Random rng = new Random();

    private final java.util.function.Supplier<String> flapKeyName;

    public FlappyBirdGame(java.util.function.Supplier<String> flapKeyName) {
        this.flapKeyName = flapKeyName;
    }

    @Override
    public String id() {
        return "flappybird";
    }

    @Override
    public String displayName() {
        return "Flappy Bird";
    }

    @Override
    public boolean isActive() {
        return sessionActive;
    }

    /**
     * Whether Flappy currently owns the chat box (it renders every tick while a
     * session exists). Mirrors the other games' {@code ownsChat()} so the shared
     * chat-size override and focus chain can treat Flappy uniformly.
     */
    public boolean ownsChat() {
        return sessionActive && !terminated;
    }

    /**
     * Opens the transparent input-capture overlay (bound to the shared focus key
     * K), so the flap key drives the game and never leaks to Minecraft. Starts a
     * session first if none is running.
     */
    public void focus() {
        if (terminated || !sessionActive) {
            start();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> mc.setScreen(new FlappyInputScreen(this)));
        }
    }

    // ─── Session control ───────────────────────────────────────────────────────

    /** Toggle: start, or freeze/resume if a session already exists. */
    public void toggle() {
        if (sessionActive && running && !gameOver) {
            freeze();
        } else if (sessionActive && !running && !gameOver) {
            resume();
        } else {
            start();
        }
    }

    public void start() {
        sessionActive = true;
        running = true;
        gameOver = false;
        terminated = false;
        score = 0;
        tickCounter = 0;

        display.clear();
        birdY = PX_H / 2f;
        birdVel = 0f;
        for (int i = 0; i < PIPE_COUNT; i++) {
            pipeX[i] = PX_W + 5 + i * PIPE_DIST;
            pipeGapY[i] = randomGapY();
            scored[i] = false;
        }
        playSound(SoundEvents.PARROT_FLY, 1.0f, 1.2f);
        renderToChat();
        // Grab input through the overlay so controls are captured (no mistriggers).
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !(mc.screen instanceof FlappyInputScreen)) {
            mc.execute(() -> mc.setScreen(new FlappyInputScreen(this)));
        }
    }

    public void freeze() {
        if (!sessionActive || !running || gameOver) {
            return;
        }
        running = false;
        renderToChat();
    }

    public void resume() {
        if (!sessionActive || running || gameOver) {
            return;
        }
        running = true;
        playSound(SoundEvents.PARROT_FLY, 1.0f, 1.2f);
        renderToChat();
    }

    @Override
    public void stop() {
        sessionActive = false;
        running = false;
        gameOver = false;
        terminated = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) {
            mc.execute(() -> {
                // Release the input overlay so keys go back to Minecraft.
                if (mc.screen instanceof FlappyInputScreen) {
                    mc.setScreen(null);
                }
                ChatComponent chat = mc.gui.getChat();
                chat.clearMessages(false);
                chat.addClientSystemMessage(Component.literal(
                        "§e[GameBox] §7Flappy Bird stopped. Type §e/game flappy §7to play."));
            });
        }
    }

    /** Called from the key handler when the flap key is pressed. */
    public void onFlapPressed() {
        // After an explicit stop, ignore the flap key until /flappybird restarts it.
        if (terminated) {
            return;
        }
        if (!sessionActive) {
            start();
            birdVel = FLAP_IMPULSE;
        } else if (gameOver) {
            start();
        } else if (!running) {
            resume();
            birdVel = FLAP_IMPULSE;
        } else {
            birdVel = FLAP_IMPULSE;
            playSound(SoundEvents.PARROT_FLY, 0.7f, 1.4f);
        }
    }

    // ─── Tick / physics ──────────────────────────────────────────────────────

    @Override
    public void tick() {
        if (!sessionActive || !running) {
            return;
        }
        tickCounter++;
        if (tickCounter < TICKS_PER_FRAME) {
            return;
        }
        tickCounter = 0;
        if (!gameOver) {
            updatePhysics();
        }
        renderToChat();
    }

    private void updatePhysics() {
        birdVel += GRAVITY;
        if (birdVel > MAX_FALL) {
            birdVel = MAX_FALL;
        }
        birdY += birdVel;

        if (birdY < 0) {
            birdY = 0;
            birdVel = 0;
        }
        if (birdY >= PX_H - 5) {
            birdY = PX_H - 5;
            triggerGameOver();
            return;
        }

        for (int i = 0; i < PIPE_COUNT; i++) {
            pipeX[i] -= 1.0f;
            if (pipeX[i] + PIPE_WIDTH < 0) {
                float maxX = pipeX[0];
                for (int j = 1; j < PIPE_COUNT; j++) {
                    if (pipeX[j] > maxX) {
                        maxX = pipeX[j];
                    }
                }
                pipeX[i] = Math.max(maxX + PIPE_DIST, PX_W);
                pipeGapY[i] = randomGapY();
                scored[i] = false;
            }
            if (!scored[i] && (pipeX[i] + PIPE_WIDTH) < BIRD_X) {
                scored[i] = true;
                score++;
                if (score > highScore) {
                    highScore = score;
                }
                playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
            }
            if (checkCollision(i)) {
                triggerGameOver();
                return;
            }
        }
    }

    private boolean checkCollision(int i) {
        int bx = BIRD_X;
        int by = Math.round(birdY);
        int bw = 4;
        int bh = 3;
        int px = Math.round(pipeX[i]);
        int gy = pipeGapY[i];
        if (bx + bw < px || bx > px + PIPE_WIDTH) {
            return false;
        }
        return by < gy || (by + bh) > (gy + PIPE_GAP);
    }

    private void triggerGameOver() {
        gameOver = true;
        if (score > highScore) {
            highScore = score;
        }
        playSound(SoundEvents.PARROT_FLY, 1.0f, 0.5f);
    }

    private int randomGapY() {
        int minGapY = 3;
        int maxGapY = PX_H - PIPE_GAP - 5;
        return minGapY + rng.nextInt(Math.max(1, maxGapY - minGapY + 1));
    }

    private void playSound(net.minecraft.sounds.SoundEvent sound, float vol, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.playSound(sound, vol, pitch);
        }
    }

    // ─── Rendering: post the frame into the chat box ───────────────────────────

    private void renderToChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        display.clear();
        drawGround();
        drawPipes();
        drawBird();
        List<Component> lines = display.toColoredComponents();

        MutableComponent frame = Component.empty();
        frame.append(Component.literal(headerLine())).append(Component.literal("\n"));
        for (int i = 0; i < lines.size(); i++) {
            frame.append(lines.get(i));
            if (i < lines.size() - 1) {
                frame.append(Component.literal("\n"));
            }
        }

        ChatComponent chat = mc.gui.getChat();
        chat.clearMessages(false);
        chat.addClientSystemMessage(frame);
    }

    private String headerLine() {
        String key = flapKeyName.get();
        String status;
        if (!sessionActive) {
            status = "§7[§ePRESS \"" + key + "\" TO PLAY§7]";
        } else if (gameOver) {
            status = "§7[§cOVER - PRESS \"" + key + "\"§7]";
        } else if (!running) {
            status = "§7[§bFROZEN - PRESS \"" + key + "\"§7]";
        } else {
            status = "§7[§aPLAYING§7]";
        }
        return " §e§lSCORE: §f" + score + "   §6§lBEST: §a" + highScore + "   " + status;
    }

    // Palette (0xRRGGBB)
    private static final int C_BIRD = 0xFFD21E;   // yellow body
    private static final int C_BEAK = 0xFF6A00;   // orange beak
    private static final int C_PIPE = 0x3FBF3F;   // green pipe
    private static final int C_PIPE_RIM = 0x8CFF6A; // light green rim
    private static final int C_GROUND = 0x7A5230; // brown ground
    private static final int C_GRASS = 0x5FD35F;  // grass top
    private static final int C_DEAD = 0xFF4040;   // red when game over

    private void drawBird() {
        int bx = BIRD_X;
        int by = Math.round(birdY);
        int body = gameOver ? C_DEAD : C_BIRD;
        display.set(bx + 1, by, true, body);
        display.set(bx + 2, by, true, body);
        display.set(bx, by + 1, true, body);
        display.set(bx + 1, by + 1, true, body);
        display.set(bx + 2, by + 1, true, body);
        display.set(bx + 3, by + 1, true, C_BEAK); // beak
        display.set(bx + 1, by + 2, true, body);
        display.set(bx + 2, by + 2, true, body);
    }

    private void drawPipes() {
        for (int i = 0; i < PIPE_COUNT; i++) {
            int px = Math.round(pipeX[i]);
            int gy = pipeGapY[i];
            // top pipe
            display.fillRect(px, 0, PIPE_WIDTH, gy, true, C_PIPE);
            if (gy - 1 >= 0) {
                display.fillRect(px - 1, gy - 1, PIPE_WIDTH + 2, 1, true, C_PIPE_RIM);
            }
            // bottom pipe
            int botTop = gy + PIPE_GAP;
            if (botTop < PX_H - 3) {
                display.fillRect(px - 1, botTop, PIPE_WIDTH + 2, 1, true, C_PIPE_RIM);
            }
            display.fillRect(px, botTop + 1, PIPE_WIDTH, PX_H - 2 - (botTop + 1), true, C_PIPE);
        }
    }

    private void drawGround() {
        for (int x = 0; x < PX_W; x++) {
            display.set(x, PX_H - 2, true, C_GRASS);
            display.set(x, PX_H - 1, (x % 2) == 0, C_GROUND);
        }
    }

    @Override
    public List<String> getFrame() {
        return null; // Flappy renders to chat (colored components), not the HUD.
    }
}
