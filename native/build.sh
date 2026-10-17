#!/usr/bin/env bash
#
# Fetches doomgeneric (if needed) and builds the GameBox native DOOM bridge.
#
# Usage:
#   ./build.sh              # clone doomgeneric + build + install into the mod
#   ./build.sh --no-install # build only, leave the lib in ./build
#
# doomgeneric + DOOM are GPL-2.0; the produced library is GPL-2.0.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

DOOMGENERIC_REPO="https://github.com/ozkl/doomgeneric.git"
DOOMGENERIC_SRC="doomgeneric/doomgeneric"

if [ ! -d "$DOOMGENERIC_SRC" ]; then
  echo ">> Cloning doomgeneric ..."
  git clone --depth 1 "$DOOMGENERIC_REPO" doomgeneric
fi

echo ">> Building native bridge ..."
if [ "${1:-}" = "--no-install" ]; then
  make DOOMGENERIC_DIR="$DOOMGENERIC_SRC" all
else
  make DOOMGENERIC_DIR="$DOOMGENERIC_SRC" install-mod
fi

echo ">> Done."
echo "   The library is in ./build and (unless --no-install) was copied into"
echo "   ../src/main/resources/natives/<os>/ so it will ship inside the mod jar."
