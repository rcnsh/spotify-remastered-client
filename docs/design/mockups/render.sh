#!/usr/bin/env bash
# Exports each phone in mockups.html as a 2x PNG (docs/design/mockups/png/<screen>.png).
# Needs a headless Chromium: CHROME=/path/to/chrome-headless-shell, or Playwright's copy.
#   docs/design/mockups/render.sh [screen-id]...
set -euo pipefail
cd "$(dirname "$0")"
chrome="${CHROME:-$(ls -d "$HOME"/Library/Caches/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-*/chrome-headless-shell 2>/dev/null | sort -V | tail -1)}"
[ -x "$chrome" ] || { echo "No headless Chromium; set CHROME" >&2; exit 1; }

screens=("$@")
[ ${#screens[@]} -gt 0 ] || screens=(home search results library playlist album artist np queue devices menu)
mkdir -p png
for s in "${screens[@]}"; do
  "$chrome" --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --window-size=390,844 --virtual-time-budget=6000 \
    --screenshot="png/$s.png" "file://$PWD/mockups.html?only=$s" >/dev/null 2>&1
  echo "png/$s.png"
done
