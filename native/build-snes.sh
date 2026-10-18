#!/usr/bin/env bash
#
# Builds the GameBox native SNES bridge (libgameboxsnes) and, optionally, the
# snes9x libretro core it loads at runtime.
#
# The bridge (snes9x_gamebox.c) is a self-contained libretro *frontend*: it needs
# only a C compiler. The actual SNES emulation lives in the snes9x libretro core
# (snes9x_libretro.{so,dylib,dll}), which this script can fetch and build for you.
#
# Usage:
#   ./build-snes.sh                 # build bridge + snes9x core + install both into the mod
#   ./build-snes.sh --bridge-only   # build only the bridge (you supply the core)
#   ./build-snes.sh --no-install    # build, but leave artifacts in ./build
#
# snes9x's license is non-commercial. The produced artifacts inherit that; do not
# distribute them commercially, and run only ROMs you are legally entitled to use.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

BRIDGE_ONLY=0
INSTALL=1
for arg in "$@"; do
  case "$arg" in
    --bridge-only) BRIDGE_ONLY=1 ;;
    --no-install)  INSTALL=0 ;;
    *) echo "unknown arg: $arg"; exit 2 ;;
  esac
done

UNAME_S="$(uname -s)"
case "$UNAME_S" in
  Darwin) OSDIR=macos;  CORE=snes9x_libretro.dylib ;;
  Linux)  OSDIR=linux;  CORE=snes9x_libretro.so ;;
  *)      OSDIR=windows; CORE=snes9x_libretro.dll ;;
esac

echo ">> Building GameBox SNES bridge ..."
if [ "$INSTALL" = "1" ]; then
  make -f Makefile.snes install-mod
else
  make -f Makefile.snes all
fi

if [ "$BRIDGE_ONLY" = "1" ]; then
  echo ">> Bridge built. Supply a snes9x libretro core yourself and point the mod"
  echo "   at it with -Dgamebox.snes.core=/abs/path/to/$CORE (or env GAMEBOX_SNES_CORE)."
  exit 0
fi

SNES9X_REPO="https://github.com/libretro/snes9x.git"
SNES9X_DIR="snes9x"

if [ ! -d "$SNES9X_DIR" ]; then
  echo ">> Cloning snes9x (libretro) ..."
  git clone --depth 1 "$SNES9X_REPO" "$SNES9X_DIR"
fi

echo ">> Building snes9x libretro core ..."
# The libretro Makefile lives in libretro/ in the snes9x tree.
make -C "$SNES9X_DIR/libretro" -f Makefile

# Locate the produced core (name/location varies a little by platform).
FOUND="$(find "$SNES9X_DIR" -name "$CORE" -o -name 'snes9x_libretro*.so' \
          -o -name 'snes9x_libretro*.dylib' -o -name 'snes9x_libretro*.dll' 2>/dev/null | head -1 || true)"

if [ -z "$FOUND" ]; then
  echo "!! Could not find the built snes9x core. Check $SNES9X_DIR/libretro output."
  exit 1
fi

echo ">> Built core: $FOUND"
if [ "$INSTALL" = "1" ]; then
  mkdir -p "../src/main/resources/natives/$OSDIR"
  cp "$FOUND" "../src/main/resources/natives/$OSDIR/$CORE"
  echo ">> Installed $CORE into src/main/resources/natives/$OSDIR/"
  echo "   GameBox will extract both the bridge and the core at runtime."
fi

echo ">> Done."
