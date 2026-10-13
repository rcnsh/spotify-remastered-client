#!/usr/bin/env bash
# Downloads Spotify's Android SDK AARs (App Remote + Auth, Apache-2.0) into app/libs/.
# They are published only as GitHub release assets, not on Maven, so they are fetched and
# checked against pinned SHA-256s rather than committed.
#
#   scripts/fetch-spotify-sdk.sh
set -euo pipefail

tag="v0.8.0-appremote_v2.1.0-auth"
base="https://github.com/spotify/android-sdk/releases/download/$tag"
dest="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
mkdir -p "$dest"

while read -r sha name; do
  file="$dest/$name"
  if [ ! -f "$file" ] || [ "$(shasum -a 256 "$file" | cut -d' ' -f1)" != "$sha" ]; then
    curl -fsSL -o "$file.part" "$base/$name"
    got="$(shasum -a 256 "$file.part" | cut -d' ' -f1)"
    [ "$got" = "$sha" ] || { rm -f "$file.part"; echo "SHA-256 mismatch for $name: $got" >&2; exit 1; }
    mv "$file.part" "$file"
  fi
  echo "ok $name"
done <<'LIST'
b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198 spotify-app-remote-release-0.8.0.aar
2a0710699c79cd0238b3b14e2cd9e34f15321806414541b0fc6c961a4a2d9a9c spotify-auth-release-2.1.0.aar
LIST
