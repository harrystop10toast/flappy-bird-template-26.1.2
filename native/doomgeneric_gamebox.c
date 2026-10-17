/*
 * doomgeneric_gamebox.c
 *
 * GameBox native bridge: a doomgeneric platform layer that exposes a tiny, stable
 * C ABI (the gamebox_doom_* functions) for the Java/JNA side in GameBox.
 *
 * It implements the doomgeneric platform callbacks:
 *   - DG_Init
 *   - DG_DrawFrame
 *   - DG_SleepMs
 *   - DG_GetTicksMs
 *   - DG_GetKey
 *   - DG_SetWindowTitle
 * and drives the engine via doomgeneric_Create()/doomgeneric_Tick().
 *
 * doomgeneric and DOOM are licensed under the GNU GPL v2. Because this file is
 * linked against doomgeneric, the resulting shared library is a GPL-2.0 work.
 * Do NOT ship it with a commercial IWAD; use the shareware DOOM1.WAD or Freedoom.
 *
 * Build: see Makefile / build.sh in this directory. Produces:
 *   libgameboxdoom.so    (Linux)
 *   libgameboxdoom.dylib (macOS)
 *   gameboxdoom.dll      (Windows)
 */

#include "doomgeneric.h"   /* from doomgeneric: declares DG_ScreenBuffer, doomgeneric_Create/Tick, DOOMGENERIC_RESX/Y */
#include "doomkeys.h"      /* key codes (not strictly required here) */

#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>

#if defined(_WIN32)
  #include <windows.h>
  #define GAMEBOX_EXPORT __declspec(dllexport)
#else
  #include <unistd.h>
  #define GAMEBOX_EXPORT __attribute__((visibility("default")))
#endif

/* ───────────────────────── Input queues ───────────────────────── */

#define KEYQUEUE_SIZE 256

typedef struct {
    int pressed;   /* 1 = down, 0 = up   */
    int key;       /* doomgeneric key code */
} KeyEvent;

static KeyEvent s_key_queue[KEYQUEUE_SIZE];
static unsigned int s_key_write = 0;
static unsigned int s_key_read = 0;

/* Accumulated mouse state consumed by doomgeneric's mouse hook. */
static volatile int s_mouse_dx = 0;
static volatile int s_mouse_dy = 0;
static volatile int s_mouse_buttons = 0;

static uint64_t s_start_ms = 0;

static uint64_t now_ms(void) {
#if defined(_WIN32)
    return (uint64_t)GetTickCount64();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
#endif
}

/* ───────────────────── doomgeneric platform callbacks ───────────────────── */

void DG_Init(void) {
    s_start_ms = now_ms();
    s_key_read = s_key_write = 0;
}

void DG_DrawFrame(void) {
    /*
     * doomgeneric has already rendered the current frame into DG_ScreenBuffer
     * (320x200 ARGB). GameBox reads that buffer directly from Java via
     * gamebox_doom_framebuffer(), so there is nothing to blit here.
     */
}

void DG_SleepMs(uint32_t ms) {
#if defined(_WIN32)
    Sleep(ms);
#else
    usleep(ms * 1000);
#endif
}

uint32_t DG_GetTicksMs(void) {
    return (uint32_t)(now_ms() - s_start_ms);
}

/*
 * doomgeneric calls DG_GetKey to pull the next key event.
 * Returns 1 and fills the pressed/doomKey out-params if an event is available,
 * otherwise returns 0.
 */
int DG_GetKey(int *pressed, unsigned char *doomKey) {
    if (s_key_read == s_key_write) {
        return 0;
    }
    KeyEvent ev = s_key_queue[s_key_read % KEYQUEUE_SIZE];
    s_key_read++;
    *pressed = ev.pressed;
    *doomKey = (unsigned char)ev.key;
    return 1;
}

void DG_SetWindowTitle(const char *title) {
    (void)title; /* headless: nothing to do */
}

/*
 * Optional mouse support (doomgeneric does NOT read mouse by default).
 *
 * Stock doomgeneric has no mouse hook, so this function is not called by the
 * unmodified engine — the keyboard path (WASD + Ctrl/Space mapped in Java)
 * fully drives DOOM on its own. To enable mouse-look, apply the small patch in
 * native/mouse-extension.patch (adds a DG_GetMouse() call inside
 * G_BuildTiccmd() in g_game.c). This function then supplies the relative motion
 * and button bitmask GameBox pushes from Java.
 */
int DG_GetMouse(int *dx, int *dy, int *buttons) {
    *dx = s_mouse_dx;
    *dy = s_mouse_dy;
    *buttons = s_mouse_buttons;
    s_mouse_dx = 0;
    s_mouse_dy = 0;
    return 1;
}

/* ───────────────────────── GameBox C ABI (JNA) ───────────────────────── */

/*
 * Persistent copy of the command line. doomgeneric stores the argv pointers
 * (myargv = argv) and dereferences them LONG after gamebox_doom_init returns —
 * e.g. M_CheckParm() during demo playback. JNA frees the temporary native
 * strings it created for the init call as soon as that call returns, which would
 * leave doomgeneric holding dangling pointers (observed as a SIGSEGV in
 * strcasecmp_l <- M_CheckParm). So we deep-copy argv here and keep it alive for
 * the lifetime of the process.
 */
static int    s_argc = 0;
static char **s_argv = NULL;

static void free_saved_argv(void) {
    if (s_argv) {
        for (int i = 0; i < s_argc; i++) {
            free(s_argv[i]);
        }
        free(s_argv);
        s_argv = NULL;
        s_argc = 0;
    }
}

static char *dup_cstr(const char *src) {
    if (!src) {
        return NULL;
    }
    size_t n = strlen(src) + 1;
    char *dst = (char *)malloc(n);
    if (dst) {
        memcpy(dst, src, n);
    }
    return dst;
}

GAMEBOX_EXPORT int gamebox_doom_init(int argc, char **argv) {
    free_saved_argv();

    /* +1 so the array is NULL-terminated, which some arg parsers expect. */
    s_argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (!s_argv) {
        return 1;
    }
    s_argc = argc;
    for (int i = 0; i < argc; i++) {
        s_argv[i] = dup_cstr(argv[i]);
        if (argv[i] && !s_argv[i]) {
            free_saved_argv();
            return 1;
        }
    }
    s_argv[argc] = NULL;

    /* doomgeneric_Create calls DG_Init internally and parses argv (-iwad ...). */
    doomgeneric_Create(s_argc, s_argv);
    return 0;
}

GAMEBOX_EXPORT void gamebox_doom_tick(void) {
    doomgeneric_Tick();
}

GAMEBOX_EXPORT void *gamebox_doom_framebuffer(void) {
    return (void *)DG_ScreenBuffer;
}

GAMEBOX_EXPORT void gamebox_doom_push_key(int pressed, int doomKey) {
    unsigned int next = s_key_write + 1;
    if ((next % KEYQUEUE_SIZE) == (s_key_read % KEYQUEUE_SIZE)) {
        /* queue full: drop the oldest */
        s_key_read++;
    }
    s_key_queue[s_key_write % KEYQUEUE_SIZE].pressed = pressed;
    s_key_queue[s_key_write % KEYQUEUE_SIZE].key = doomKey;
    s_key_write = next;
}

GAMEBOX_EXPORT void gamebox_doom_push_mouse(int dx, int dy, int buttons) {
    s_mouse_dx += dx;
    s_mouse_dy += dy;
    s_mouse_buttons = buttons;
}

GAMEBOX_EXPORT int gamebox_doom_ticks_ms(void) {
    return (int)DG_GetTicksMs();
}

GAMEBOX_EXPORT void gamebox_doom_shutdown(void) {
    /*
     * doomgeneric has no clean teardown entry point; the GameBox engine thread
     * simply stops calling gamebox_doom_tick(). Reset our local queues so a
     * subsequent re-init starts clean within the same process.
     */
    s_key_read = s_key_write = 0;
    s_mouse_dx = s_mouse_dy = 0;
    s_mouse_buttons = 0;
}
