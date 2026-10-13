# Design

## Mockups
`mockups/mockups.html` (open in a browser) holds every screen of the client, with the data
source for each in its caption. Palette is sampled from the user's Spicetify theme; artwork
is placeholder. `mockups/render.sh` exports each phone to `mockups/png/<screen>.png` (2x).

Palette: base `#1e2233`, surface `#23283d`, selected `#32364a`, accent `#7289da`,
progress `#1ed760` on `#485686`, heart `#ef5b74`. Type: Figtree (+ Hiragino for JP).
In code: `Palette` and `Type` in `ui/theme`.

## Constraints from the platform
- Artwork comes from Spotify's image CDN; sizes are fixed by what the API returns.
- Free accounts: shuffle-only and ads are enforced by Spotify and must be presented
  honestly.
- Spotify's own notification, lock screen and Android Auto UI stay Spotify's.
