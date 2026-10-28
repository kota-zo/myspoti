# Third-party notices

Myspoti is free software under the GNU General Public License v3.0 (see `LICENSE`).
It builds on the projects below; their licences are compatible with the GPL-3.0.

## Spotui — GPL-3.0
https://github.com/Spotui/Spotui

The DASH-to-FLAC remux (`base/app/src/main/java/com/music/spotui/data/offline/DashFlacRemux.kt`)
is ported from Spotui and stays under the GPL-3.0. The file says so in its header.

## BitChord — GPL-3.0-or-later
https://github.com/kushagrasinghx/BitChord (commit `edc1e6f`)

By the BitChord contributors. The Immersive look is ported from it. These files in
`base/app/src/main/java/com/music/spotui/ui/look/` are adapted from BitChord, stay under the
GPL-3.0-or-later, and say so in their header:

- `MeshGradient.kt`, from `sharedUi/.../ui/player/MeshGradient.kt` (the mesh backdrop and `cyclingMeshPalette`)
- `ThinSlider.kt`, from `sharedUi/.../ui/player/ThinSlider.kt` (the thin seek bar, without Automix)
- `LyricClock.kt`, from `sharedUi/.../ui/player/LyricClock.kt` (the smoothed lyric playhead)
- `SweptLyricLine.kt`, from `sharedUi/.../ui/player/PlayerLyrics.kt` (`SweptLyricLine`, its drawing
  helpers and constants) and `sharedUi/src/androidMain/.../ui/player/PlayerPlatformUi.android.kt`
- `SweptLyricModel.kt`, from `shared/.../data/lyrics/LyricLine.kt` (word timing, lift and glow)

The Immersive UI in `base/app/src/main/java/com/music/spotui/ui/immersive/` (player, lyrics, queue,
tab bar, top bars, sheets, motion, components, Home, Search, Library, Category, album/playlist, artist, profile, podcast and download pages) is
adapted from these BitChord files, each named in the header of the Myspoti file that carries it:
`sharedUi/.../ui/player/` NowPlayingScreen, PlayerControls, PlayerState, PlayerHost, PlayerLyrics,
PlayerQueue; `sharedUi/.../ui/components/` Common, SearchField, Skeletons, SongText, OptimizedHaze;
`sharedUi/.../ui/screens/` HomeScreen, SearchScreen, LibraryScreen, ExploreScreen;
`app/.../ui/components/` MiniPlayer, FrostedTopBar, GlassNavBar, BottomFadeScrim,
floatingtabbar/FloatingTabBar, floatingtabbar/GlassSelectionPill; `app/.../ui/player/PlayerSheetMotion`;
`app/.../ui/screens/` DetailScreen, LibraryPhoneParts; `app/.../ui/utils/IosOverscroll`.

No BitChord fonts, icons, images or brand are included.

Code that BitChord itself carries from other projects, kept with its own header:
- `shell/FloatingTabBar.kt`: compose-floating-tab-bar by Elyes Mansour
  (https://github.com/elyesmansour/compose-floating-tab-bar), Apache-2.0, through Echo-Music
  (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0.
- `shell/GlassSelectionPill.kt`: the pill motion follows liquid_glass_easy
  (https://pub.dev/packages/liquid_glass_easy), MIT License, Copyright (c) 2025 Ahmed Gamil.
- `components/ImmersiveOverscroll.kt`: from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0.
- `shell/ImmersiveGlass.kt` (Liquid Glass): adapted from BitChord's `app/.../ui/components/LiquidGlass.kt`,
  itself adapted from Echo-Music's GlassEffectConfig / `Modifier.liquidGlass`
  (https://github.com/EchoMusicApp/Echo-Music), GPL-3.0. The lens of `shell/GlassSelectionPill.kt` and
  the minimizing chrome of `shell/ImmersiveChrome.kt` follow BitChord's `floatingtabbar/` files.

## Backdrop — Apache-2.0
https://github.com/Kyant0/backdrop (v2.0.0, `io.github.kyant0:backdrop`), Copyright 2025 Kyant0.

The refraction, blur, rim highlight and shadow of the Immersive look's Liquid Glass. Vendored as
source in `base/app/src/main/java/com/music/spotui/ui/immersive/shell/backdrop/`, taken from
BitChord's vendored copy (which adds a backdrop resolution scale and an exported backdrop, used to
keep the GPU cost down; the published artifact has neither). Every file keeps its Apache-2.0 header;
the package is renamed. Licence text in `base/licenses/Apache-2.0.txt` (it also covers Haze and
compose-floating-tab-bar above).

## Haze — Apache-2.0
https://github.com/chrisbanes/haze

The frosted bars of the Immersive look (Gradle dependency `dev.chrisbanes.haze:haze`).

## Inter — SIL Open Font License 1.1
https://github.com/rsms/inter

The Immersive look's typeface (`base/app/src/main/res/font/inter.ttf`), Copyright 2020 The Inter
Project Authors. Cut down to Latin with its weight axis only. Licence text in
`base/licenses/Inter-OFL.txt`.

## Chromium `cast_channel.proto` — BSD-3-Clause
https://chromium.googlesource.com/chromium/src/+/main/components/cast_channel/proto/cast_channel.proto

Copyright The Chromium Authors. Casting to Google Cast devices
(`base/app/src/main/java/com/music/spotui/cast/`) is Myspoti's own CastV2 sender, without the
Google Cast SDK. `CastMessage.kt` codes by hand the `CastMessage` message that this file defines
(its field numbers and types); no Chromium code is included.
