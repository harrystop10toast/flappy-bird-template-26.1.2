# GameBox native DOOM bridge Makefile
#
# Builds a shared library (libgameboxdoom.{so,dylib} / gameboxdoom.dll) from the
# doomgeneric core sources plus the GameBox platform bridge (doomgeneric_gamebox.c).
#
# Prerequisites:
#   - A C toolchain (gcc/clang, or MinGW/MSVC on Windows).
#   - The doomgeneric source tree. Run ./build.sh to fetch it automatically, or
#     set DOOMGENERIC_DIR to an existing checkout:
#       make DOOMGENERIC_DIR=/path/to/doomgeneric/doomgeneric
#
# doomgeneric + DOOM are GPL-2.0. The resulting library is therefore GPL-2.0.

DOOMGENERIC_DIR ?= doomgeneric/doomgeneric
BRIDGE          := doomgeneric_gamebox.c
OUTDIR          ?= build

UNAME_S := $(shell uname -s)

CC      ?= cc
# NOTE: DOOMGENERIC_RESX/Y default to 640x400 in doomgeneric. GameBox expects a
# 320x200 ARGB framebuffer (matching DoomNative.DOOMGENERIC_RESX/RESY on the Java
# side), so we pin them here. Keep these in sync with DoomNative.java.
#
# We intentionally do NOT define FEATURE_SOUND: that keeps i_sound.c on its
# no-SDL stub path (dummy.c supplies I_InitTimidityConfig). GameBox is a visual
# port and does not route DOOM audio.
CFLAGS  ?= -O2 -fPIC -fvisibility=hidden -DNDEBUG \
           -DDOOMGENERIC_RESX=320 -DDOOMGENERIC_RESY=200 \
           -Wno-pointer-to-int-cast -Wno-absolute-value
INCLUDES := -I$(DOOMGENERIC_DIR)

# doomgeneric core sources EXCEPT:
#   - its own platform files (doomgeneric_*.c) — we provide our own bridge;
#   - the SDL / Allegro sound+music backends, which require external libraries.
# With FEATURE_SOUND undefined, i_sound.c compiles as a silent stub.
EXCLUDE := $(wildcard $(DOOMGENERIC_DIR)/doomgeneric_*.c) \
           $(DOOMGENERIC_DIR)/i_sdlsound.c \
           $(DOOMGENERIC_DIR)/i_sdlmusic.c \
           $(DOOMGENERIC_DIR)/i_allegrosound.c \
           $(DOOMGENERIC_DIR)/i_allegromusic.c
CORE_SRCS := $(filter-out $(EXCLUDE), $(wildcard $(DOOMGENERIC_DIR)/*.c))
SRCS := $(CORE_SRCS) $(BRIDGE)

ifeq ($(UNAME_S),Darwin)
  LIBNAME := libgameboxdoom.dylib
  LDFLAGS := -dynamiclib -install_name @rpath/$(LIBNAME)
  OSDIR   := macos
else ifeq ($(OS),Windows_NT)
  LIBNAME := gameboxdoom.dll
  LDFLAGS := -shared
  OSDIR   := windows
else
  LIBNAME := libgameboxdoom.so
  LDFLAGS := -shared
  OSDIR   := linux
endif

LIBS := -lm

.PHONY: all clean install-mod

all: $(OUTDIR)/$(LIBNAME)

$(OUTDIR)/$(LIBNAME): $(SRCS)
	@mkdir -p $(OUTDIR)
	$(CC) $(CFLAGS) $(INCLUDES) $(SRCS) $(LDFLAGS) $(LIBS) -o $@
	@echo "Built $@"

# Copies the built library into the mod's bundled natives resource folder so it
# ships inside the jar (jar-in-jar is only for JNA; the native lib is a plain
# resource extracted at runtime).
install-mod: all
	@mkdir -p ../src/main/resources/natives/$(OSDIR)
	cp $(OUTDIR)/$(LIBNAME) ../src/main/resources/natives/$(OSDIR)/$(LIBNAME)
	@echo "Installed $(LIBNAME) into src/main/resources/natives/$(OSDIR)/"

clean:
	rm -rf $(OUTDIR)
