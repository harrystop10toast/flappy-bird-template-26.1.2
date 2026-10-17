package com.example.gamebox;

import com.example.gamebox.child.ChildClientController;
import com.example.gamebox.child.ChildInputScreen;
import com.example.gamebox.doom.DoomController;
import com.example.gamebox.game.GameDispatcher;
import com.example.gamebox.graphing.GraphingController;
import com.example.gamebox.nes.NesController;
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
 * <p>Renders 1-bit games into the Minecraft chat box using Unicode Braille. There
 * is a <b>single command</b>, {@code /game}, which does everything:
 *
 * <ul>
 *   <li>{@code /game} / {@code /game list} — list imported games + usage;</li>
 *   <li>{@code /game <filename>} — launch a game by file name (engine
 *       auto-detected from the extension);</li>
 *   <li>{@code /game flappy} — the built-in Flappy Bird;</li>
 *   <li>{@code /game graph …} / {@code /game trig …} — the calculators;</li>
 *   <li>{@code /game size <px>} — set the emulator canvas width;</li>
 *   <li>{@code /game stop} — stop the running game/tool.</li>
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
        games = new GameDispatcher(doom, mario, flappy, graphing, trig, childHost);

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
                && !doomActive && !marioActive && !graphingOn && !trigOn && !childOn) {
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
        } else if (doomActive) {
            doom.renderToChat();
        } else {
            flappy.tick();
        }

        chatOverride.sync(doom.ownsChat() || mario.ownsChat()
                || graphing.ownsChat() || trig.ownsChat()
                || childHost.ownsChat());
    }

    private void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            CommandBuildContext ctx) {

        // /game — the single, universal command. Everything is a subcommand of it
        // (see GameDispatcher): launch a ROM/WAD by name, flappy, graph, trig,
        // size, stop, list.
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
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
