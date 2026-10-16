# Myspoti

[![Latest release](https://img.shields.io/github/v/release/kota-zo/myspoti?label=release)](https://github.com/kota-zo/myspoti/releases/latest)
[![License: GPL-3.0](https://img.shields.io/github/license/kota-zo/myspoti)](LICENSE)
[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/jXKnGVFnGS)

**Spotify on Android, free account, no ads.** A native Jetpack Compose client with synced lyrics and official music videos.

<p align="center">
  <img src="docs/screenshots/banner.jpg" alt="Myspoti: home, player, artist and album screens" width="100%">
</p>

<p align="center">
  <a href="https://github.com/kota-zo/myspoti/releases/latest"><b>⬇ Download the latest APK</b></a>
</p>

The official Spotify Web Player runs in a hidden WebView and does the actual playback, with the phone's DRM and Spotify's own reporting. The native UI drives it, so plays count on your account exactly as they do in a browser. You always sign in yourself, on Spotify's own login page.

<p align="center">
  <a href="https://ko-fi.com/kotazo"><img src="https://storage.ko-fi.com/cdn/kofi6.png?v=6" alt="Support Myspoti on Ko-fi" height="56"></a>
</p>

## Features

- **No ads.** The ad audio is refused, so the player skips each ad within a second. It works with a free account: no Premium, no patched Spotify app, and the DRM is left untouched.
- Home (with Music, Podcasts and Audiobooks filters), search (songs, artists, albums, playlists, podcasts, episodes), **New releases**, Spotify's real **Browse** categories, library (folders, sorting, drag to reorder), artist, album and playlist pages, with Spotify's official labels.
- **Podcasts and audiobooks**: shows, episodes, chapters, Your Episodes, playback speed.
- **Jam**: join a Jam with an invite link.
- **Hide songs and artists**: a song or an artist hidden from its menu is skipped whenever it comes up, in any playlist, radio or shuffle (Spicetify's Trash Bin). The list moves to and from Spicetify desktop by copy and paste, in Settings.
- Synced lyrics: Spotify first, then [LRCLIB](https://lrclib.net); optionally Apple Music (word by word), NetEase and Genius.
- **Your recap**: your top tracks, artists and albums, counting Spotify, downloads and local files. It stays on your phone.
- Official music videos, when one exists. The video plays muted, in sync with the song. The app only uses YouTube videos marked as official clips, and shows nothing when no clip exists.
- Spotify Connect, media notification, lock screen, Bluetooth and Android Auto.
- **Offline downloads.** Download a song, an album, a playlist or your Liked Songs, then play them without a connection. The audio comes lossless (FLAC, 16-bit or 24-bit hi-res when it exists), or as Tidal's own AAC for the smallest files: the very recording, found on Tidal (or Qobuz) by its exact ISRC, through the same signed servers as [SpotiFLAC Mobile](https://github.com/zarzet/SpotiFLAC-Mobile)'s extensions, after a one-off verification in Settings. Each file is checked against the recording's official preview (looked up by ISRC), so a live take, a remix or a cover is rejected. Files stay in Myspoti's private storage.
- **Sound quality and Settings.** Streaming quality (Automatic, Low, Normal, High) and download quality are picked at the first start and kept in one Settings page, with data saver, battery, display and language.
- **Connect with your headphones.** Their name shows instead of "This phone", one tap sends the sound back to the speaker, and a quality badge shows what plays now. The music pauses when headphones or Bluetooth go away.
- English, French and Italian.

## How it works

The app is split in two:

- **`:api`** is the contract the UI talks to: plain Kotlin models and interfaces (player, search, catalog, library, playlists, home, podcasts, users, lyrics, devices, Jam).
- **`:api-spotify`** implements it on Spotify's Web Player. Playback stays in the official player, in a hidden WebView (`:host-android`), driven by a small bridge script. Catalog, search and library calls go straight to Spotify's servers, which is faster. When Spotify ships a new Web Player, the app reads its new operation list from the public files by itself.
- **`:host-cdp`** runs the same API on desktop Chrome: the whole API is tested against the real Web Player, without a phone.

Details for developers: [`base/api-spotify/README.md`](base/api-spotify/README.md) and [`docs/api`](docs/api).

## Screenshots

| Home | Player | Artist | Album |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" width="200" alt="Home"> | <img src="docs/screenshots/player.jpg" width="200" alt="Player"> | <img src="docs/screenshots/artist.jpg" width="200" alt="Artist"> | <img src="docs/screenshots/album.jpg" width="200" alt="Album"> |

## Not included

- No DRM circumvention. No Amazon stream decryption.
- No Spotify audio download. Offline downloads are resolved from lossless catalogues through the open-backends community of SpotiFLAC; a track with no lossless source is simply marked unavailable.
- Downloaded songs played on the phone are reported to Spotify as if Spotify had streamed them, although their audio did not come from Spotify (play time as measured on the phone; sent later when offline). Spotify counts them like streamed listens, in Wrapped and in artists' play counts, and may treat such reports as artificial streaming. No ad is marked as heard.

## Credits

- [SpotiFLAC](https://github.com/spotbye/SpotiFLAC) (MIT) — the lossless resolving idea, its community backend protocol and the gated-session flow.
- [Spicetify morphe-patches](https://github.com/spicetify/morphe-patches) (GPL-3.0) — the Trash Bin: hidden songs and artists, skipped whenever they play.
- [Spotui](https://github.com/Spotui/Spotui) (GPL-3.0) — the Kotlin port of the resolver, the signed-session verification screen and the DASH-to-FLAC remux that Myspoti builds on.
- [SpotiFLAC Mobile](https://github.com/zarzet/SpotiFLAC-Mobile) (MIT) and its [extensions](https://github.com/zarzet/SpotiFLAC-Extension) (Apache-2.0) — the signed download sessions and the Tidal / Qobuz download requests.

Full notices: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Disclaimer

Blocking ads goes against Spotify's Terms of Use, and Spotify may suspend accounts that do it. Use this app at your own risk. If you can, support artists with Premium or by buying their music.

## Support

Myspoti is free, has no ads and is made in my spare time. If you enjoy it, you can support it on [Ko-fi](https://ko-fi.com/kotazo), also from **About** in the app's profile menu. Nothing in the app is locked behind a donation.

<p align="center">
  <a href="https://ko-fi.com/kotazo"><img src="https://storage.ko-fi.com/cdn/kofi6.png?v=6" alt="Support Myspoti on Ko-fi" height="56"></a>
</p>

News, help and ideas: join the [Discord server](https://discord.gg/jXKnGVFnGS). Bugs and requests are also welcome in [GitHub issues](https://github.com/kota-zo/myspoti/issues).

## Build

The Android project is in `base/`. Requirements: JDK 17+ and the Android SDK (compileSdk 37).

```bash
cd base
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Live API tests (desktop Chrome with a signed-in Web Player):

```bash
google-chrome --user-data-dir="$HOME/.myspoti-chrome" --remote-debugging-port=9222
./gradlew :host-cdp:test -Pcdp=http://localhost:9222
```

Every push builds and tests on GitHub Actions. A `v*` tag publishes a signed APK in Releases; the app finds it, downloads it and installs it through Android's installer.

## Credits

- UI derived from [Spotui](https://github.com/Spotui/Spotui) / [Metrolist](https://github.com/MetrolistGroup/Metrolist) (GPL-3.0).
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0).
- [Material Icons](https://github.com/google/material-design-icons) (Apache 2.0).
- [Figtree](https://github.com/erikdkennedy/figtree) font (SIL OFL 1.1, see `base/licenses/`).

## License

GPL-3.0.

Not affiliated with or endorsed by Spotify. Spotify is a trademark of Spotify AB.
