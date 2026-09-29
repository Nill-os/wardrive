#!/bin/bash
# Clone Organic Maps at the commit this patch was made against and apply it.
#   ./apply.sh [target-dir]      (default: ./organicmaps)
set -e
BASE=e24de3c22f76f0b3c5a5b65449f5db0c35f4a147
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-organicmaps}"
if [ ! -d "$DEST/.git" ]; then
  git clone --filter=blob:none https://github.com/organicmaps/organicmaps.git "$DEST"
fi
cd "$DEST"
git checkout -q "$BASE"
git submodule update --init --recursive --depth 1
git am "$HERE"/patches/*.patch
echo "Applied. Build: cd $DEST/android && ./gradlew :app:assembleFdroidDebug  (see Organic Maps' INSTALL.md)"
