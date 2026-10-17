# GameBox

A Fabric mod for **Minecraft 26.1.2** (Java 25) that renders 1-bit games directly
onto the in-game HUD using **Unicode Braille** characters (U+2800–U+28FF). Each
Braille glyph packs a 2×4 block of pixels, giving a surprisingly high-resolution
mono display inside a tiny area of the screen.

Ships with three built-in games (no import needed):

- **Super Mario Bros.** — a pure-Java NES emulator (6502 CPU + 2C02 PPU), played
  with `/game mario`.
- **Flappy Bird** — pure Java, always available (`/game flappy`).
- **DOOM** — powered by the native [`doomgeneric`](https://github.com/ozkl/doomgeneric)
  engine via JNA. Requires building a small native bridge and supplying a WAD.

You can also drop your own `.nes` ROMs or `.wad` files into `config/gamebox/games/`
and launch them by name with `/game <filename>`.

---

## Features

- **`BrailleDisplay`** core: a configurable 1-bit framebuffer (default 96×60 →
  **15 lines × 48 chars**) that packs 2×4 pixel blocks into Braille glyphs, with
  **4×4 Bayer ordered dithering** so grayscale sources (DOOM) reduce cleanly to
  1-bit.
- **Two render targets:**
  - **Flappy Bird** animates the **chat box** — each frame clears chat and posts
    a fresh client-side system message of Braille rows (a 70×36 canvas = 9 rows +
    a score header = 10 chat lines), exactly like the original template.
  - **DOOM** draws onto the **HUD** via Fabric's HUD element API (see the note
    below), over the world rather than in chat.
- **`/game`** command: launch the built-in games (`mario`, `flappy`, `minecraft`)
  or any imported `.nes`/`.wad` by name, set the canvas size, and stop.
- **`/graph`** and **`/trig`** commands: the (non-game) graphing and trigonometry
  calculators, each rendered in chat as Braille.
- DOOM input: a transparent full-screen overlay captures the keyboard/mouse,
  grabs the cursor, and maps GLFW keys to DOOM key codes.

### A note on `HudRenderCallback`

The original brief asked for `HudRenderCallback`. That event **does not exist** in
Fabric API for Minecraft 26.1.2 — the HUD pipeline was rewritten around a
render-state extractor. GameBox therefore registers a
`net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement` via
`HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, …)`. This is the
direct, idiomatic equivalent for this version: the element's
`extractRenderState(GuiGraphicsExtractor, DeltaTracker)` is exactly where HUD
drawing happens. See `GameHudRenderer`.

---

## Building the mod (Java side)

Standard Fabric Loom, non-remapping, Java 25:

```bash
./gradlew build
```

The jar is written to `build/libs/`. JNA (`jna` + `jna-platform`) is bundled into
the jar via Loom's `include` (jar-in-jar), so no extra downloads are needed at
runtime.

Flappy Bird works immediately. DOOM additionally needs the native library and a
WAD (below).

---

## Building the native DOOM bridge

DOOM is run by `doomgeneric` (GPL-2.0) through a thin C bridge
(`native/doomgeneric_gamebox.c`) that implements the doomgeneric platform
callbacks (`DG_Init`, `DG_DrawFrame`, `DG_SleepMs`, `DG_GetTicksMs`, `DG_GetKey`,
`DG_SetWindowTitle`) and exposes a small C ABI for Java/JNA
(`gamebox_doom_init`, `gamebox_doom_tick`, `gamebox_doom_framebuffer`,
`gamebox_doom_push_key`, `gamebox_doom_push_mouse`, `gamebox_doom_ticks_ms`,
`gamebox_doom_shutdown`).

### One-shot build

```bash
cd native
./build.sh            # clones doomgeneric, builds, and installs into the mod
# or:
./build.sh --no-install   # build only (library stays in native/build)
```

This produces, per platform:

| OS       | Library file            |
|----------|-------------------------|
| Linux    | `libgameboxdoom.so`     |
| macOS    | `libgameboxdoom.dylib`  |
| Windows  | `gameboxdoom.dll`       |

Requirements: a C toolchain (`clang`/`gcc`, or MinGW on Windows), `make`, and
`git`. The framebuffer resolution is pinned to **320×200** (`-DDOOMGENERIC_RESX=320
-DDOOMGENERIC_RESY=200`) to match the Java side. No SDL/Allegro is required — the
SDL/Allegro sound backends are excluded and DOOM audio is stubbed out (GameBox is
a visual port).

### Manual build

```bash
cd native
git clone --depth 1 https://github.com/ozkl/doomgeneric.git
make DOOMGENERIC_DIR=doomgeneric/doomgeneric all          # -> native/build/
make DOOMGENERIC_DIR=doomgeneric/doomgeneric install-mod  # copy into the mod
```

### How the mod finds the native library

Resolution order (first hit wins):

1. **`-Dgamebox.doom.lib=/absolute/path/to/libgameboxdoom.ext`** JVM argument.
2. A bundled copy inside the jar at **`/natives/<os>/<libname>`** (this is what
   `make install-mod` populates, under `src/main/resources/natives/<os>/`).
3. The OS library search path, by the name **`gameboxdoom`**.

> **Licensing:** the native library links doomgeneric and is therefore a GPL-2.0
> work. `make install-mod` is **not** run by default, so the shipped mod jar does
> not bundle a GPL binary unless you choose to. If you distribute a jar that
> includes `natives/`, distribute it under GPL-2.0 and never bundle a commercial
> IWAD.

### Optional: mouse-look

Stock doomgeneric ignores the mouse, so DOOM is driven entirely by the keyboard
by default. To enable mouse-look/turn, apply the small engine patch described in
`native/mouse-extension.patch` and rebuild. The bridge already exports
`DG_GetMouse` and the mod already pushes mouse deltas.

---

## Getting a WAD

DOOM needs an IWAD. Use a **free, non-commercial** one:

- **Shareware `DOOM1.WAD`** — the original shareware episode (freely
  redistributable). Widely mirrored; search for "doom shareware DOOM1.WAD".
- **Freedoom** — fully free assets: <https://freedoom.github.io/>
  (`freedoom1.wad` / `freedoom2.wad`).

Place the WAD in one of:

- `<minecraft game dir>/config/gamebox/DOOM1.WAD`
  (also accepts `doom1.wad`, `DOOM.WAD`, `freedoom1.wad`, `freedoom2.wad`), or
- any path given via **`-Dgamebox.doom.wad=/absolute/path/to/your.wad`**.

Do **not** use a commercial WAD you are not licensed to redistribute.

---

## Playing

### Commands

**The `/game` command** launches games — the built-in ones by keyword and any
imported ROM/WAD by filename. GameBox treats each console/engine as an emulator
keyed by file extension, so you import a game simply by dropping its file into
`config/gamebox/games/` and launching it by name.

| Command | Effect |
|---|---|
| `/game` | List built-in games + any imported ROM/WAD + usage |
| `/game mario` | Start the built-in **Super Mario Bros.** (NES) — no import needed (aliases: `supermario`, `smb`) |
| `/game flappy` | Start the built-in Flappy Bird |
| `/game minecraft` | Launch a second real Minecraft (hidden child) into chat |
| `/game <filename>` | Launch an imported game by file name — engine auto-detected: `.nes`→NES, `.wad`→DOOM. E.g. `/game doom1.wad`, `/game mygame.nes` (the extension is optional and matching is case/`-`/`_`-insensitive) |
| `/game size <px>` | Set the game canvas width (48..448 px; height follows the 448:280 ratio) |
| `/game stop` | Stop whichever game is running |

Launching a game through `/game` also widens the chat canvas to its maximum so
the picture is as large as chat allows. Imported games are discovered in
`config/gamebox/games/` (the recommended drop-in folder, created on first use),
`config/gamebox/`, the game directory, and its parent.

**In-chat only.** Every game GameBox runs renders **inside the Minecraft chat
box** as Braille. It ships two engines: the pure-Java **NES** emulator (`.nes`,
`.fc`) — which powers the built-in Super Mario Bros. — and the native **DOOM**
engine (`.wad`, `.pwad`). Those are the extensions `/game` recognises for
imported files; a file of any other type is simply reported as not a GameBox
game. (GameBox deliberately does **not** shell out to external standalone
emulators — a game playing in its own window outside Minecraft defeats the point.)
Adding a new system later means writing an in-chat engine and pointing its
extension at it in `GameLibrary`.

**Non-game tools are separate commands.** The calculators are *not* games, so
they live under their own top-level commands rather than under `/game`:

| Command | Effect |
|---|---|
| `/graph [equation]` | Toggle/drive the graphing calculator (e.g. `/graph sin(x)`, `/graph restart`, `/graph all`). Alias: `/graphing` |
| `/trig [args]` | Trig calculator / triangle visualizer (`/trig help` for the full syntax). Alias: `/trigonometry` |

### Default keybinds (rebindable in the vanilla Controls menu)

| Action | Default |
|---|---|
| Flappy Bird: flap / start | `Space` |
| Shared game focus / re-grab input | `K` |

### DOOM controls (fixed GLFW→DOOM mapping)

While DOOM is focused, the transparent input overlay captures these keys, so they
drive DOOM and **never** move your Minecraft player.

| Keys | Action |
|---|---|
| `W` / `S` | Move forward / back |
| `A` / `D` | Strafe left / right |
| `Q` / `E` | Turn (look) left / right |
| Arrow keys | Turn (←/→) and move (↑/↓) — classic DOOM |
| `F` / `Ctrl` / left mouse | Fire |
| `Space` / right mouse | Use / open |
| `Shift` | Run |
| `Tab` | Automap |
| `Enter` / `Esc` | Menu confirm / back |
| `1`–`9` | Weapon select |
| `Y` / `N` | Menu yes / no |
| Mouse move | Look/turn (requires the optional engine patch) |

### Super Mario Bros. controls (fixed GLFW→NES mapping)

Super Mario Bros. 1 runs on a **pure-Java NES emulator** (6502 CPU + 2C02 PPU +
NROM/mapper-0 cartridge) and renders in color into the chat box with the same
Braille technique as DOOM. While Mario is focused, a transparent input overlay
captures these keys so they drive the game and **never** move your Minecraft
player. Press **Esc** to release input, and the **DOOM/Super Mario focus key**
(`K`, default) to re-grab it.

| Keys | NES button |
|---|---|
| Arrow keys / `W` `A` `S` `D` | D-pad |
| `X` / `J` | A (jump) |
| `Z` | B (run / fireball) |
| `Enter` | Start |
| `Right Shift` / `Space` | Select |
| `Esc` | Release input (game keeps running) |
| `K` | Re-grab input |

The ROM is read from the game directory (its parent in a dev run — i.e. the
project root, where `Super Mario Bros. (Europe) (Rev A).nes` lives), from
`config/gamebox/`, or from a path given via `-Dgamebox.nes.rom=/abs/path.nes`.
Only free/legally-obtained ROMs should be used.

Press Minecraft's **Esc** while in the DOOM overlay to release input without
stopping the game; re-grab it with the DOOM focus key (`K`).

### Graphing calculator (`/graph`)

A 2D function grapher that plots `y = f(x)` equations in color into the chat box
using the same Braille technique as DOOM and Super Mario. The view has labeled
axes — numeric tick labels along x and y (a tiny 3×5 bitmap font), an origin `0`,
and `x`/`y` axis caps — plus a big, readable **scale bar** on the bottom line
(normal font, e.g. `|-----2-----|  = 2 units`) that tracks the current zoom, since
the in-canvas Braille tick labels are necessarily tiny. Toggle it with
`/graph` (alias `/graphing`); it keeps your equations across on/off toggles (only
`/graph restart` clears them). Each equation gets a numeric id and a distinct
curve color; `/graph` (bare) and `/graph all` print the id/equation/color
list — `/graph all` first **pauses** the live graph so the list is not
instantly buried by the next frame (press `K` or `/graph` to resume).

Expressions support `+ - * / ^`, the modulo operator (`a mod b` or `a % b`),
parentheses, unary minus, the variables `x` and `y`, the constants `pi` and `e`,
literal absolute-value bars `|f(x)|`, and a broad function set:

- **unary:** `sin cos tan asin acos atan` (and `arcsin arccos arctan`),
  `sinh cosh tanh`, `sqrt cbrt abs exp ln log log2 floor ceil round sign/sgn`;
- **multi-argument:** `min(a,b) max(a,b) mod(a,b) log(base,x) root(n,x)`, plus
  the nth-root form `sqrt[n]{…}`;
- **numerical calculus (in x):** `derivative(f)` (aliases `deriv`, `d/dx`) and
  `integral(f)` (antiderivative from 0, alias `integrate`).

Implicit multiplication (`2x`, `3sin(x)`) and bare function application (`sin y`)
are accepted. (Write `2*|x|`, not `2|x|`, when multiplying into an absolute value.)

Equations are **versatile relations**, not just `y = f(x)`. Any `lhs = rhs` is
plotted as the implicit curve where `lhs - rhs = 0`, so all of these work:

```
y = sin(x)
x = sin(y)
1/y = x
x^2 + y^2 = 9        (a circle)
x^2 - y^2 = 1        (a hyperbola)
```

A bare expression with no `=` is treated as `y = …` (e.g. `/graph 0.5*x^2 - 3`).

**Constraints** restrict where a curve draws: append one or more `{…}` blocks with
a comparison (chained inequalities like `a <= x <= b` are allowed), or use the
Desmos-style domain form `{body : condition}`. Examples:

```
/graph y = sin(x) {y<0}          only the parts dipping below the x-axis
/graph x^2+y^2=9 {x>0}{y>0}      the circle's first-quadrant arc only
/graph {x^2 : -1<=x<=1}          a parabola drawn only on -1 ≤ x ≤ 1
```

While focused (press **K** to grab, **Esc** to release), the view is driven like a
terminal Desmos:

| Keys | Action |
|---|---|
| ← / → | Pan left / right |
| ↑ / ↓ | Pan up / down |
| `O` | Zoom in |
| `P` | Zoom out |
| `Esc` | Release input (calculator stays on) |
| `K` | Re-grab input |

---

## Project layout

```
build.gradle, gradle.properties, settings.gradle   Fabric Loom (Java 25) setup
src/main/resources/
  fabric.mod.json                                   mod metadata (client entrypoint)
  assets/gamebox/font/braille.json                  Braille font provider (resource pack)
  assets/gamebox/lang/en_us.json                    keybind translations
src/main/java/com/example/gamebox/
  BrailleDisplay.java        96x60 buffer, Bayer dithering, 2x4 Braille packing
  Game.java, GameManager.java  shared game abstraction + active-game tracking
  GameHudRenderer.java       HUD element that draws the active game
  FlappyBirdGame.java        Flappy Bird (renders into BrailleDisplay)
  GameBoxKeybinds.java       rebindable key mappings
  GameBoxScreen.java         the /gamebox GUI
  GameBoxClient.java         entrypoint: keybinds, HUD, ticking, commands
  doom/
    DoomNative.java          JNA binding to the native bridge
    DoomKeys.java            GLFW -> DOOM key-code mapping
    DoomEngine.java          35Hz engine thread, framebuffer -> Braille
    DoomInputScreen.java     transparent input-capture overlay
    DoomController.java      play/pause/resume/stop/status + WAD resolution
native/
  doomgeneric_gamebox.c      doomgeneric platform layer + gamebox_doom_* ABI
  Makefile, build.sh         native build (fetches doomgeneric)
  mouse-extension.patch      optional mouse-look engine patch
```

---

## Licensing

- GameBox mod source: **CC0-1.0** (as inherited from the template).
- The DOOM native component links **doomgeneric** and **DOOM**, which are
  **GPL-2.0**. Any distributed build that includes the native library (or a
  jar bundling it) is a GPL-2.0 work. Use only free, redistributable WADs.
