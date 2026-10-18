package com.example.gamebox;

import com.example.gamebox.child.ChildClientController;
import com.example.gamebox.child.ChildInputScreen;
import com.example.gamebox.doom.DoomController;
import com.example.gamebox.game.GameDispatcher;
import com.example.gamebox.gb.GbController;
import com.example.gamebox.graphing.GraphingController;
import com.example.gamebox.nes.NesController;
import com.example.gamebox.snes.SnesController;
import com.example.gamebox.trig.TrigController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GameBox client entry point.
 *
 * <p>Renders 1-bit games into the Minecraft chat box using Unicode Braille.
 *
 * <p>Games live under <b>{@code /game}</b>:
 * <ul>
 *   <li>{@code /game} / {@code /game list} — list built-in + imported games + usage;</li>
 *   <li>{@code /game mario} — the built-in Super Mario Bros. (NES);</li>
 *   <li>{@code /game flappy} — the built-in Flappy Bird;</li>
 *   <li>{@code /game minecraft} — launch a second real Minecraft into chat;</li>
 *   <li>{@code /game <filename>} — launch an imported ROM/WAD by file name
 *       (engine auto-detected from the extension);</li>
 *   <li>{@code /game size <px>} — set the game canvas width;</li>
 *   <li>{@code /game stop} — stop the running game.</li>
 * </ul>
 *
 * <p>Non-gaming tools are their own top-level commands:
 * <ul>
 *   <li>{@code /graph …} (alias {@code /graphing}) — the graphing calculator;</li>
 *   <li>{@code /trig …} (alias {@code /trigonometry}) — the trig calculator /
 *       triangle visualizer.</li>
 * </ul>
 *
 * <p>The flap key and the shared grab-keyboard key are registered into the
 * vanilla Controls screen (Options → Controls → GameBox).
 */
public final class GameBoxClient implements ClientModInitializer {

    public static final String MOD_ID = "gamebox";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private FlappyBirdGame flappy;
    private final DoomController doom = new DoomController();
    private final NesController mario = new NesController();
    private final GbController gb = new GbController();
    private final SnesController snes = new SnesController();
    private final GraphingController graphing = new GraphingController();
    private final TrigController trig = new TrigController();
    private final ChildClientController childHost = new ChildClientController();
    private final ChatOptionsOverride chatOverride = new ChatOptionsOverride();

    /** Unified /game command dispatcher (built once flappy exists). */
    private GameDispatcher games;

    private boolean prevFlapDown;
    private boolean prevFocusDown;

    @Override
    public void onInitializeClient() {
        GameBoxKeybinds.register();

        flappy = new FlappyBirdGame(() -> GameBoxKeybinds.FLAP.getTranslatedKeyMessage().getString());
        games = new GameDispatcher(doom, mario, gb, snes, flappy, graphing, trig, childHost);

        // If this process is the hidden child client, start the frame/input bridge
        // back to the host. (Child frame capture is driven on the render thread by
        // GameRendererFpsMixin.)
        com.example.gamebox.child.ChildClient.startIfChild();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

        LOGGER.info("GameBox initialised.");
    }

    private void onClientTick(Minecraft client) {
        boolean doomActive = doom.isActive();
        boolean marioActive = mario.isActive();
        boolean gbActive = gb.isActive();
        boolean snesActive = snes.isActive();
        boolean graphingOn = graphing.isOn();
        boolean trigOn = trig.isOn();
        boolean flappyActive = flappy.isActive();
        boolean childOn = childHost.isActive();

        // The shared focus key (K) re-grabs input for whichever feature owns the
        // chat box right now (Minecraft child, trig, graphing, Super Mario, DOOM,
        // or Flappy Bird).
        boolean focusDown = GameBoxKeybinds.DOOM_FOCUS.isDown();
        if (focusDown && !prevFocusDown) {
            if (childOn) {
                client.setScreen(new ChildInputScreen(childHost));
            } else if (trigOn) {
                trig.focus();
            } else if (graphingOn) {
                graphing.focus();
            } else if (marioActive) {
                mario.focus();
            } else if (gbActive) {
                gb.focus();
            } else if (snesActive) {
                snes.focus();
            } else if (doomActive) {
                doom.focus();
            } else if (flappyActive) {
                flappy.focus();
            }
        }
        prevFocusDown = focusDown;

        // Flappy flap edge-detect only when NOTHING is capturing input (no screen
        // open) and no other chat-owning feature is running.
        boolean flapDown = GameBoxKeybinds.FLAP.isDown();
        if (flapDown && !prevFlapDown && client.screen == null
                && !doomActive && !marioActive && !gbActive && !snesActive && !graphingOn && !trigOn && !childOn) {
            flappy.onFlapPressed();
        }
        prevFlapDown = flapDown;

        // One owner of the chat box at a time: the Minecraft child first (an
        // explicit full takeover), then trig / graphing (explicit toggles), then
        // Super Mario, then DOOM, otherwise Flappy ticks.
        if (childOn) {
            childHost.renderToChat();
        } else if (trigOn) {
            trig.renderToChat();
        } else if (graphingOn) {
            graphing.renderToChat();
        } else if (marioActive) {
            mario.renderToChat();
        } else if (gbActive) {
            gb.renderToChat();
        } else if (snesActive) {
            snes.renderToChat();
        } else if (doomActive) {
            doom.renderToChat();
        } else {
            flappy.tick();
        }

        chatOverride.sync(doom.ownsChat() || mario.ownsChat() || gb.ownsChat() || snes.ownsChat()
                || graphing.ownsChat() || trig.ownsChat()
                || childHost.ownsChat());
    }

    private void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            CommandBuildContext ctx) {

        // /game — launch games (built-in or imported). See GameDispatcher.
        dispatcher.register(
                ClientCommands.literal("game")
                        .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                .executes(c -> {
                                    String args = StringArgumentType.getString(c, "args");
                                    for (String line : games.handle(args)) {
                                        c.getSource().sendFeedback(Component.literal(line));
                                    }
                                    return 1;
                                }))
                        .executes(c -> {
                            for (String line : games.handle("")) {
                                c.getSource().sendFeedback(Component.literal(line));
                            }
                            return 1;
                        }));

        // /graph (alias /graphing) — the graphing calculator (a tool, not a game).
        registerTool(dispatcher, "graph", games::handleGraph);
        registerTool(dispatcher, "graphing", games::handleGraph);

        // /trig (alias /trigonometry) — the trig calculator / triangle visualizer.
        registerTool(dispatcher, "trig", games::handleTrig);
        registerTool(dispatcher, "trigonometry", games::handleTrig);
    }

    /**
     * Registers a top-level tool command {@code /name [args]} that forwards its
     * (optional) greedy argument string to {@code handler} and prints each
     * returned line as chat feedback.
     */
    private static void registerTool(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            String name,
            java.util.function.Function<String, java.util.List<String>> handler) {
        dispatcher.register(
                ClientCommands.literal(name)
                        .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                                .executes(c -> {
                                    String args = StringArgumentType.getString(c, "args");
                                    for (String line : handler.apply(args)) {
                                        c.getSource().sendFeedback(Component.literal(line));
                                    }
                                    return 1;
                                }))
                        .executes(c -> {
                            for (String line : handler.apply("")) {
                                c.getSource().sendFeedback(Component.literal(line));
                            }
                            return 1;
                        }));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
