# Third-party notices

Myspoti is free software under the GNU General Public License v3.0 (see `LICENSE`).
It builds on the projects below; their licences are compatible with the GPL-3.0.

## Beat This! — MIT
https://github.com/CPJKU/beat_this

By Francesco Foscarin, Jan Schlüter and Gerhard Widmer (CPJKU, Johannes Kepler University Linz),
"Beat This! Accurate beat tracking without DBN postprocessing" (ISMIR 2024). AutoMix ships the
`small0` checkpoint, exported to ONNX and quantized to int8 (`base/app/src/main/assets/automix/
beat-this-small0-int8.onnx`); upstream publishes the code and the weights under the MIT licence.
The log-mel front end in `base/automix/.../BeatThis.kt` follows its input contract. The training
recordings are not bundled.

## ONNX Runtime — MIT
https://github.com/microsoft/onnxruntime

Copyright Microsoft Corporation. `com.microsoft.onnxruntime:onnxruntime-android` 1.23.2 runs the
Beat This model on the phone (the desktop bench uses the JVM build of the same version).

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

The local player's equalizer, `base/app/src/main/java/com/music/spotui/di/EqualizerProcessor.kt`, is
adapted from BitChord's `app/src/main/java/com/music/bitchord/playback/EqualizerProcessor.kt` (commit
`b690dc1`): its state-variable filter sections, their coefficients and the gliding of a change. It
stays under the GPL-3.0-or-later and says so in its header.

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

## Apple Music TV — GPL-3.0
https://github.com/JObersi10/apple-music-tv (commit `7e5c594`)

By JObersi10. The Android TV look is ported from it. These files in
`base/app/src/main/java/com/music/spotui/ui/tv/` are adapted from its
`android/app/src/main/java/com/applemusicktv/ui/` files, stay under the GPL-3.0, and say so in
their header:

- `TvParts.kt`, from `components/TopNavBar.kt`, `AlbumCard.kt`, `SpotlightCards.kt` (GradientCard)
  and `Skeleton.kt` (the floating tab pill, the focus scale, halo and border of the cards, the shelf skeleton)
- `TvApp.kt`, from `AppShell.kt`, `screens/HomeScreen.kt` and `screens/PlaylistDetailScreen.kt`
  (the shell, Home's shelves, a list's page)
- `TvNowPlaying.kt`, from `screens/NowPlayingScreen.kt` (the full-screen player, its idle mode and
  the D-pad seek bar)

Only its UI is used: none of its playback, Widevine or network code, and none of its icons (Apple's
SF Symbols), images or brand.

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

## Reorderable — Apache-2.0

`sh.calvin.reorderable:reorderable` 3.1.0 (https://github.com/Calvin-LL/Reorderable), Copyright Calvin Liang,
used as a library (unmodified) for dragging the rows of the Immersive queue. Licence text in
`base/licenses/Apache-2.0.txt`.

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

## InnerTune — GPL-3.0
https://github.com/z-huang/InnerTune (commit `bfba5ec`)

By Zion Huang and the InnerTune contributors. YouTube Music as an account provider
(`base/api-ytmusic/`) follows it for the sign-in url and its "back on music.youtube.com" test
(`app/.../ui/screens/LoginScreen.kt`), the `SAPISIDHASH` auth header and the `WEB_REMIX` request
shape (`innertube/.../InnerTube.kt`), and the library's browse ids (`innertube/.../YouTube.kt`).
`YtmSession.kt`, `InnerTube.kt` and `YtmLibrary.kt` say so in their header and stay under the
GPL-3.0. No API key, stream or download code is taken.

## Mornye Extension — LGPL-3.0-only

`catalog/src/main/resources/app/myspoti/catalog/metadata/{deezer,tidal,ytmusic}.js` are modified versions of the
Deezer, Tidal and YouTube Music extensions of [Mornye-Extension](https://github.com/mornyeapp/Mornye-Extension),
Copyright 2026 zarzet, taken at commit `0c326fa36fc130127d90083808c6b51ea6d83aa2`.

Changes: everything but catalogue metadata was removed (audio, downloads, sign-in, settings of those), and the code
that remains was left as written otherwise. `catalog/tools/strip_metadata.py` performs these changes from the original
files, so they can be checked or redone. The texts of the LGPL-3.0 and of the GPL-3.0 it builds on are next to the
scripts. The ideas behind `catalog/.../Match.kt` also come from Mornye.

Each script runs as source and can be replaced without rebuilding the application.

## uBlock Origin filter lists (uAssets) — GPL-3.0
https://github.com/uBlockOrigin/uAssets (`filters/filters-2020.txt`, commit `dbc8ad17eea0d9f2815553c099643995f7a0ae83`)

By Raymond Hill and the uAssets contributors. The ad blocker for Spotify Connect devices
(`base/api-spotify/.../bridge/AdBlock.kt`) refuses the hosts and paths of
`base/api-spotify/src/main/resources/bridge/page-filter.txt`; the rules marked "uAssets" there are
taken from the list's open.spotify.com block (`2mdn.net`, `adxcel.com`, `amillionads.com`,
`adstudio-assets.scdn.co`, the `scdn.co/mp3-ad/` path), reduced to a host or a host and path. No
filter engine, cosmetic rule or scriptlet is taken.

## EasyList / EasyPrivacy — GPL-3.0 or CC BY-SA 3.0
https://github.com/easylist/easylist (`easyprivacy/easyprivacy_specific.txt`, commit `876bb4a29090827868d93ab8d00960f959e16028`)

By the EasyList authors. The tracker rule marked "EasyPrivacy" in the same list
(`spotifycdn.com/cdn/js/retargeting-pixels.`) comes from it, used under the GPL-3.0.

## Hush — GPL-3.0

By coldMEW. The ad host `s0.2mdn.net` came from its list; it is now covered by uAssets' `2mdn.net`.

## Horologist and Wear Compose Material3 — Apache-2.0
https://github.com/google/horologist

Copyright Google LLC. The watch app's player and library pages (`base/wear`) are built on
Horologist's media and audio UI (`horologist-media-ui-material3`, `horologist-audio-ui-material3`,
`horologist-compose-layout`, 0.8.4-alpha) and AndroidX Wear Compose Material3, used as libraries.
Two icon paths in `base/wear/.../WatchIcons.kt` are Google's Material Icons (Apache-2.0).

## Jetcaster and JetStream samples — Apache-2.0
https://github.com/android/compose-samples (Jetcaster/tv, Jetcaster/core/designsystem), Copyright 2024 The Android Open Source Project
https://github.com/android/tv-samples (JetStreamCompose), Copyright 2023 Google LLC

The Android TV screens (`base/app/src/main/java/com/music/spotui/ui/tv/`) adapt parts of these
samples: spacing and type scale, cover-tinted backgrounds, the two-column list and podcast pages,
cards, focus look (border, scale, glow), focus restoring rows, the seek bar, the home carousel,
the tab bar's sliding indicator and gradient placeholders. Modified for Myspoti, 2026-11. Files
holding adapted code say so at their top. Licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). Neither sample ships a NOTICE file.
