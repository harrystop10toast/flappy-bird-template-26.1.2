/*
 * snes9x_gamebox.c
 *
 * GameBox native SNES bridge: a tiny libretro frontend that loads a SNES
 * libretro core (snes9x) at runtime and exposes a small, stable C ABI (the
 * gamebox_snes_* functions) for the Java/JNA side in GameBox — mirroring the
 * DOOM bridge's gamebox_doom_* ABI.
 *
 * Design: rather than statically compiling the whole snes9x source tree, this
 * frontend dynamically loads the prebuilt libretro core (snes9x_libretro.{so,
 * dylib,dll}) via dlopen/LoadLibrary and drives it through the standard libretro
 * entry points (retro_init, retro_load_game, retro_run, retro_set_*_callback …).
 * The core hands us a frame through the video-refresh callback each retro_run();
 * we convert it to a fixed 256x224 0xAARRGGBB buffer that GameBox dithers to
 * Braille — exactly like the DOOM 320x200 path.
 *
 * Licensing: snes9x is distributed under its own non-commercial license and the
 * libretro glue is GPL-style; treat the built artifact with the same posture as
 * the DOOM/doomgeneric component (do not ship it commercially, use only ROMs you
 * are legally entitled to run).
 *
 * The core path is taken from the environment variable GAMEBOX_SNES_CORE, or
 * falls back to "snes9x_libretro" on the system search path.
 */

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <time.h>

#if defined(_WIN32)
  #include <windows.h>
  #define GAMEBOX_EXPORT __declspec(dllexport)
  typedef HMODULE dynlib_t;
  static dynlib_t dl_open(const char *p) { return LoadLibraryA(p); }
  static void *dl_sym(dynlib_t h, const char *s) { return (void *)GetProcAddress(h, s); }
  static void dl_close(dynlib_t h) { if (h) FreeLibrary(h); }
#else
  #include <dlfcn.h>
  #include <unistd.h>
  #define GAMEBOX_EXPORT __attribute__((visibility("default")))
  typedef void *dynlib_t;
  static dynlib_t dl_open(const char *p) { return dlopen(p, RTLD_NOW | RTLD_LOCAL); }
  static void *dl_sym(dynlib_t h, const char *s) { return dlsym(h, s); }
  static void dl_close(dynlib_t h) { if (h) dlclose(h); }
#endif

/* ───────────────────────── libretro ABI (subset) ───────────────────────── */

#define RETRO_API_VERSION 1

#define RETRO_PIXEL_FORMAT_0RGB1555 0
#define RETRO_PIXEL_FORMAT_XRGB8888 1
#define RETRO_PIXEL_FORMAT_RGB565   2

#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31
#define RETRO_ENVIRONMENT_GET_CAN_DUPE 3
#define RETRO_ENVIRONMENT_SET_VARIABLES 16
#define RETRO_ENVIRONMENT_GET_VARIABLE 15
#define RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE 17
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27

#define RETRO_DEVICE_JOYPAD 1

/* Joypad button ids (libretro) — must match SnesKeys on the Java side. */
#define RETRO_DEVICE_ID_JOYPAD_B      0
#define RETRO_DEVICE_ID_JOYPAD_Y      1
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2
#define RETRO_DEVICE_ID_JOYPAD_START  3
#define RETRO_DEVICE_ID_JOYPAD_UP     4
#define RETRO_DEVICE_ID_JOYPAD_DOWN   5
#define RETRO_DEVICE_ID_JOYPAD_LEFT   6
#define RETRO_DEVICE_ID_JOYPAD_RIGHT  7
#define RETRO_DEVICE_ID_JOYPAD_A      8
#define RETRO_DEVICE_ID_JOYPAD_X      9
#define RETRO_DEVICE_ID_JOYPAD_L      10
#define RETRO_DEVICE_ID_JOYPAD_R      11
#define JOYPAD_IDS 16

struct retro_game_info {
    const char *path;
    const void *data;
    size_t      size;
    const char *meta;
};

struct retro_system_av_info {
    struct { double aspect_ratio; unsigned base_width, base_height, max_width, max_height; } geometry;
    struct { double fps, sample_rate; } timing;
};

typedef void  (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void  (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t(*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void  (*retro_input_poll_t)(void);
typedef int16_t(*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);
typedef int   (*retro_environment_t)(unsigned cmd, void *data);

/* Core function pointers resolved from the loaded libretro core. */
typedef void     (*fn_void)(void);
typedef unsigned (*fn_u)(void);
typedef void     (*fn_set_env)(retro_environment_t);
typedef void     (*fn_set_vr)(retro_video_refresh_t);
typedef void     (*fn_set_as)(retro_audio_sample_t);
typedef void     (*fn_set_asb)(retro_audio_sample_batch_t);
typedef void     (*fn_set_ip)(retro_input_poll_t);
typedef void     (*fn_set_is)(retro_input_state_t);
typedef int      (*fn_load)(const struct retro_game_info *);
typedef void     (*fn_av)(struct retro_system_av_info *);

static struct {
    dynlib_t handle;
    fn_void      retro_init;
    fn_void      retro_deinit;
    fn_void      retro_run;
    fn_void      retro_reset;
    fn_void      retro_unload_game;
    fn_set_env   set_environment;
    fn_set_vr    set_video_refresh;
    fn_set_as    set_audio_sample;
    fn_set_asb   set_audio_sample_batch;
    fn_set_ip    set_input_poll;
    fn_set_is    set_input_state;
    fn_load      load_game;
    fn_av        get_system_av_info;
} core;

/* ───────────────────────── Frame + input state ───────────────────────── */

#define FB_W 256
#define FB_H 224

static uint32_t s_framebuffer[FB_W * FB_H];
static int      s_pixel_format = RETRO_PIXEL_FORMAT_RGB565; /* snes9x default */
static int16_t  s_buttons[JOYPAD_IDS];
static uint64_t s_start_ms = 0;
static int      s_loaded = 0;
static char     s_core_path[4096];

static uint64_t now_ms(void) {
#if defined(_WIN32)
    return (uint64_t)GetTickCount64();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
#endif
}

/* Convert one source pixel (in the current libretro pixel format) to 0xFFRRGGBB. */
static inline uint32_t to_argb(const uint8_t *src, int fmt) {
    if (fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
        uint32_t p = *(const uint32_t *)src;      /* 0x00RRGGBB */
        return 0xFF000000u | (p & 0x00FFFFFFu);
    } else if (fmt == RETRO_PIXEL_FORMAT_RGB565) {
        uint16_t p = *(const uint16_t *)src;
        uint32_t r = (p >> 11) & 0x1F;
        uint32_t g = (p >> 5)  & 0x3F;
        uint32_t b =  p        & 0x1F;
        r = (r << 3) | (r >> 2);
        g = (g << 2) | (g >> 4);
        b = (b << 3) | (b >> 2);
        return 0xFF000000u | (r << 16) | (g << 8) | b;
    } else { /* 0RGB1555 */
        uint16_t p = *(const uint16_t *)src;
        uint32_t r = (p >> 10) & 0x1F;
        uint32_t g = (p >> 5)  & 0x1F;
        uint32_t b =  p        & 0x1F;
        r = (r << 3) | (r >> 2);
        g = (g << 3) | (g >> 2);
        b = (b << 3) | (b >> 2);
        return 0xFF000000u | (r << 16) | (g << 8) | b;
    }
}

/* ───────────────────────── libretro callbacks ───────────────────────── */

static void cb_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) {
        return; /* duped frame — keep last framebuffer */
    }
    int bpp = (s_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) ? 4 : 2;
    unsigned w = width  > FB_W ? FB_W : width;
    unsigned h = height > FB_H ? FB_H : height;
    const uint8_t *base = (const uint8_t *)data;

    /* Clear to black first in case the frame is smaller than our fixed buffer. */
    for (int i = 0; i < FB_W * FB_H; i++) {
        s_framebuffer[i] = 0xFF000000u;
    }
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = base + (size_t)y * pitch;
        uint32_t *dst = &s_framebuffer[y * FB_W];
        for (unsigned x = 0; x < w; x++) {
            dst[x] = to_argb(row + (size_t)x * bpp, s_pixel_format);
        }
    }
}

static size_t cb_audio_batch(const int16_t *data, size_t frames) {
    (void)data;             /* GameBox is a visual port — audio discarded. */
    return frames;
}
static void cb_audio_sample(int16_t l, int16_t r) { (void)l; (void)r; }

static void cb_input_poll(void) { /* state pushed from Java; nothing to poll */ }

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || id >= JOYPAD_IDS) {
        return 0;
    }
    return s_buttons[id];
}

static int cb_environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (data) {
                s_pixel_format = *(const int *)data;
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            if (data) {
                *(int *)data = 1;
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (data) {
                *(const char **)data = ".";
            }
            return 1;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            if (data) {
                *(int *)data = 0;
            }
            return 1;
        }
        default:
            return 0; /* unsupported env call */
    }
}

/* ───────────────────────── Core loading ───────────────────────── */

static int resolve_core(void) {
    const char *path = (s_core_path[0] != '\0') ? s_core_path : getenv("GAMEBOX_SNES_CORE");
#if defined(_WIN32)
    const char *fallback = "snes9x_libretro.dll";
#elif defined(__APPLE__)
    const char *fallback = "snes9x_libretro.dylib";
#else
    const char *fallback = "snes9x_libretro.so";
#endif
    const char *target = (path && path[0]) ? path : fallback;

    core.handle = dl_open(target);
    if (!core.handle) {
        return -1;
    }

    core.retro_init          = (fn_void)   dl_sym(core.handle, "retro_init");
    core.retro_deinit        = (fn_void)   dl_sym(core.handle, "retro_deinit");
    core.retro_run           = (fn_void)   dl_sym(core.handle, "retro_run");
    core.retro_reset         = (fn_void)   dl_sym(core.handle, "retro_reset");
    core.retro_unload_game   = (fn_void)   dl_sym(core.handle, "retro_unload_game");
    core.set_environment     = (fn_set_env)dl_sym(core.handle, "retro_set_environment");
    core.set_video_refresh   = (fn_set_vr) dl_sym(core.handle, "retro_set_video_refresh");
    core.set_audio_sample    = (fn_set_as) dl_sym(core.handle, "retro_set_audio_sample");
    core.set_audio_sample_batch = (fn_set_asb)dl_sym(core.handle, "retro_set_audio_sample_batch");
    core.set_input_poll      = (fn_set_ip) dl_sym(core.handle, "retro_set_input_poll");
    core.set_input_state     = (fn_set_is) dl_sym(core.handle, "retro_set_input_state");
    core.load_game           = (fn_load)   dl_sym(core.handle, "retro_load_game");
    core.get_system_av_info  = (fn_av)     dl_sym(core.handle, "retro_get_system_av_info");

    if (!core.retro_init || !core.retro_run || !core.load_game
            || !core.set_environment || !core.set_video_refresh
            || !core.set_input_state || !core.set_input_poll) {
        dl_close(core.handle);
        core.handle = NULL;
        return -2;
    }
    return 0;
}

/* ───────────────────────── Public gamebox_snes_* ABI ───────────────────────── */

/* Optionally set the absolute path to the snes9x libretro core before init.
 * If unset, init falls back to env GAMEBOX_SNES_CORE then the default name. */
GAMEBOX_EXPORT void gamebox_snes_set_core_path(const char *path) {
    if (path && path[0]) {
        strncpy(s_core_path, path, sizeof(s_core_path) - 1);
        s_core_path[sizeof(s_core_path) - 1] = '\0';
    } else {
        s_core_path[0] = '\0';
    }
}

/* Returns 0 on success; negative on failure:
 *   -1 core library not found/loadable
 *   -2 core missing required symbols
 *   -3 ROM file unreadable
 *   -4 retro_load_game failed
 */
GAMEBOX_EXPORT int gamebox_snes_init(const char *rom_path) {
    if (s_loaded) {
        return 0;
    }
    memset(s_buttons, 0, sizeof(s_buttons));
    for (int i = 0; i < FB_W * FB_H; i++) {
        s_framebuffer[i] = 0xFF000000u;
    }

    int rc = resolve_core();
    if (rc != 0) {
        return rc;
    }

    core.set_environment(cb_environment);
    core.retro_init();
    core.set_video_refresh(cb_video_refresh);
    core.set_audio_sample(cb_audio_sample);
    core.set_audio_sample_batch(cb_audio_batch);
    core.set_input_poll(cb_input_poll);
    core.set_input_state(cb_input_state);

    /* Load ROM from file (data=NULL => core reads the path itself). */
    FILE *f = fopen(rom_path, "rb");
    if (!f) {
        return -3;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return -3; }
    void *buf = malloc((size_t)sz);
    if (!buf) { fclose(f); return -3; }
    size_t rd = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    if (rd != (size_t)sz) { free(buf); return -3; }

    struct retro_game_info info;
    memset(&info, 0, sizeof(info));
    info.path = rom_path;
    info.data = buf;
    info.size = (size_t)sz;
    info.meta = NULL;

    int ok = core.load_game(&info);
    free(buf);
    if (!ok) {
        return -4;
    }

    s_start_ms = now_ms();
    s_loaded = 1;
    return 0;
}

GAMEBOX_EXPORT void gamebox_snes_tick(void) {
    if (s_loaded && core.retro_run) {
        core.retro_run();
    }
}

GAMEBOX_EXPORT void *gamebox_snes_framebuffer(void) {
    return (void *)s_framebuffer;
}

GAMEBOX_EXPORT int gamebox_snes_resx(void) { return FB_W; }
GAMEBOX_EXPORT int gamebox_snes_resy(void) { return FB_H; }

/* button index is a RETRO_DEVICE_ID_JOYPAD_* value; pressed is 1/0. */
GAMEBOX_EXPORT void gamebox_snes_push_button(int index, int pressed) {
    if (index >= 0 && index < JOYPAD_IDS) {
        s_buttons[index] = pressed ? 1 : 0;
    }
}

GAMEBOX_EXPORT void gamebox_snes_reset(void) {
    if (s_loaded && core.retro_reset) {
        core.retro_reset();
    }
}

GAMEBOX_EXPORT int gamebox_snes_ticks_ms(void) {
    return (int)(now_ms() - s_start_ms);
}

GAMEBOX_EXPORT void gamebox_snes_shutdown(void) {
    if (!s_loaded) {
        return;
    }
    if (core.retro_unload_game) {
        core.retro_unload_game();
    }
    if (core.retro_deinit) {
        core.retro_deinit();
    }
    if (core.handle) {
        dl_close(core.handle);
        core.handle = NULL;
    }
    s_loaded = 0;
}
