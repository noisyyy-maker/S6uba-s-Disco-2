# Disco Music (Fabric, Minecraft 26.2)

Lightweight in-game music player. Client-side only.

## Use
1. Drop `.mp3`, `.ogg` or `.wav` files into `mods/music` (loose files in `mods/` are found too).
   Name them `Artist - Title.ogg` to show the artist.
2. Press **M** in game (rebindable) to open the menu.
3. Keys (all rebindable in Settings > Keys or Options > Controls):
   K pause/play, J previous, L next, Up/Down volume.

## Build the jar (no install needed)
1. Create a free GitHub repo and upload everything in this folder.
2. Open the Actions tab, run "build" (it also runs on every push).
3. Download the `disco-music-jar` artifact. Put the jar in `mods/` with Fabric API.

Or locally: install JDK 25 and Gradle 9.x, then run `gradle build`. Jar: `build/libs/`.

## If the build fails
Versions are in `gradle.properties` (Minecraft 26.2, Loader 0.19.3, Fabric API 0.161.0+26.2).
Check https://fabricmc.net/develop for current numbers. Paste any compiler errors back to Claude.
