# Myspoti

[![Latest release](https://img.shields.io/github/v/release/kota-zo/myspoti?label=release)](https://github.com/kota-zo/myspoti/releases/latest)
[![License: GPL-3.0](https://img.shields.io/github/license/kota-zo/myspoti)](LICENSE)
[![Discord](https://img.shields.io/badge/Discord-join-5865F2?logo=discord&logoColor=white)](https://discord.gg/jXKnGVFnGS)

**Spotify without ads, on a free account.** Your own Spotify account, playlists and Wrapped, in a native Android app that blocks the ads, with offline downloads through extensions you install.

<p align="center">
  <img src="docs/screenshots/banner.jpg" alt="Myspoti in its two looks: the Immersive home and player, the Classic player and an artist page" width="100%">
</p>

<p align="center">
  <a href="https://github.com/kota-zo/myspoti/releases/latest"><b>⬇ Download the latest APK</b></a>
</p>

<p align="center">
  <a href="https://ko-fi.com/myspoti"><img src="https://storage.ko-fi.com/cdn/kofi6.png?v=6" alt="Support Myspoti on Ko-fi" height="56"></a>
  <a href="https://github.com/sponsors/kota-zo"><img src="https://img.shields.io/badge/Sponsor_on_GitHub-%E2%9D%A4-ea4aaa?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="Sponsor kota-zo on GitHub" height="56"></a>
</p>

## Why Myspoti

### 🚫 Spotify with no ads, on a free account
No audio ads, no video ads, no banners, and no 30-second break between songs. Myspoti switches off the ad breaks of Spotify's own player, so a free account plays like Premium: pick any song, skip as much as you want. No Premium, no modified Spotify app, nothing to root.

### ⬇️ Offline downloads, through extensions
Myspoti has no download source of its own. Install a **download-source extension** (Settings, Extensions) and you can download a song, an album, a playlist or all your Liked Songs, and listen with no connection: on the plane, in the metro, with no data. The extension decides where the files come from and in which formats (FLAC, M4A or MP3); Myspoti keeps them encrypted on your phone, refuses a file that is not the song's length, and keeps downloaded playlists in step: songs you add on Spotify download by themselves.

### ✨ And everything you expect from Spotify, and more
Your home, search, library, playlists, podcasts, Spotify Connect and Android Auto, plus **word-by-word lyrics**, **official music videos** and **song meanings from Genius**.

## What is it?

Myspoti is a Spotify app for Android that you install yourself. You sign in with your usual Spotify account, on Spotify's own login page: Myspoti never sees your password. A free account is enough.

Under the hood, the official Spotify Web Player plays the music, hidden inside the app, with your phone's DRM. Myspoti draws its own screens on top and drives it. Your plays are reported to Spotify exactly as in a browser: they count for the artists, in your history and in your Wrapped.

## Get started

1. Download the APK from the [latest release](https://github.com/kota-zo/myspoti/releases/latest) and open it (Android 10 or later). Android asks once to allow installs from your browser or file manager.
2. Tap **Log in with Spotify** and sign in.
3. Pick the sound quality, allow what you want (notifications, Bluetooth, local files…), and you are in.

New versions are offered inside the app: one tap downloads and installs them.

Each release carries one APK per architecture: `arm64-v8a` for almost every phone from the last ten years, `armeabi-v7a` for older or entry-level 32-bit phones (an app such as CPU-Z or Device Info shows which one yours is; when in doubt, take `arm64-v8a`, and if it refuses to install, `armeabi-v7a`). The in-app update picks the right one by itself.

Obtainium (add `https://github.com/kota-zo/myspoti`; the stable app and the beta app are two apps and each release carries their APKs named for the channel and the architecture):
- **Stable** (`app.myspoti.stable`): leave "Include prereleases" off and set the APK filter to `^myspoti-stable-.*-arm64-v8a\.apk$` (or `-armeabi-v7a` on a 32-bit phone).
- **Beta** (`app.myspoti`): turn "Include prereleases" on and set the APK filter to `^myspoti-beta-.*-arm64-v8a\.apk$` (or `-armeabi-v7a` on a 32-bit phone).
- Betas are named `<next version>-beta.<n>` (`2.2.1-beta.1`, then `2.2.1-beta.2`...), marked prerelease on GitHub, and always newer than the last stable.

## What you get

**Listening**
- No ads (see above).
- Home, search, Browse, New releases, Your Library with folders, artist, album, playlist and profile pages, with Spotify's own content.
- Pick any song: shuffle (Spotify's, or Myspoti's own that plays every song once before any repeats), Smart Shuffle, the queue (drag to reorder), repeat (all, the song for good, the song once), crossfade, sleep timer.
- For extension, downloaded and device songs: a 10-band **equalizer** with presets, playback speed and skip silence; **Stats for nerds** shows the codec, sample rate, bit depth, bitrate and output of what plays.
- Podcasts and audiobooks, with their own player (speed, ±15 s, mark as played).
- Spotify Connect, Jam, Bluetooth with your headphones' name, lock screen, notification, widget and Android Auto.

**Lyrics and videos**
- Synced lyrics, word by word when a source has the timing (or line by line, your choice in Settings), with translation.
- **Song meanings from Genius**: a light bulb for the story of the song, and a long press on an underlined line for its explanation, translated into your language.
- **Official music videos**, in sync with the song, in full screen if you like. Play them silently over the song, or hear the clip's own sound while the song keeps counting on Spotify.

**Your playlists**
- Create, edit (name, description, cover), sort, make public, private or collaborative.
- **Add songs** from a Spotify search inside the playlist, with suggestions.
- **Collaborative playlists**: invite friends with a link, see who is in, and who added each song.
- Share any song, album, artist or playlist as a **Spotify Code** (scannable in the Spotify app) or a link.

**Offline**
- With a download-source extension installed: download songs, albums, playlists and Liked Songs, in the formats the extension offers (or re-encoded to small AAC on the phone).
- Downloads are encrypted and stay in Myspoti. Lists you downloaded stay in step with Spotify.
- Your local audio files too.

**Extensions**
- Scripts written by other people, in a sandbox: lyrics from other sources, where a song's audio or download comes from, Home sections, menu entries, tools that use your Spotify account.
- Installing shows what an extension will do (each action with its author's reason, the critical ones marked) and asks for one tick: you install it on your own responsibility. An extension gets every action it lists, or is not installed; an update that asks for more waits for your OK and shows only what changes.
- With an audio-source extension on, every song you play (from a list, search, an artist, Recents, a link or shuffle) is asked there first, and plays on Spotify when it has nothing.
- An extension can bring its own settings to Settings, Playback or Downloads (its quality, per network if it likes): the first source of the order shows its own, and an extension above Spotify replaces Spotify's quality settings there.
- For authors: [the extensions API](docs/extensions/API.md), and a desktop runner to test without a phone.

**And also**
- Your recap: your top songs, artists and albums, kept on your phone.
- 11 languages: English, French, German, Spanish, Italian, Portuguese, Brazilian Portuguese, Greek, Russian, Polish and Chinese (Simplified).
- Data saver, battery saver, AMOLED black, accent colour.

## New since 1.1

Version 2.0 is a new app, rebuilt from the ground up.

- 🚀 **A new engine**: faster screens, quicker skips, and playback that recovers by itself when Spotify's player stumbles.
- 🚫 **Ads stopped at the source** instead of skipped.
- 🎧 **Downloads through extensions** instead of built-in YouTube audio, encrypted on your phone.
- 🎤 **Word-by-word lyrics**, from more sources, with **Genius song meanings**.
- 🎬 **Music videos with their own sound**, in full screen, in better quality.
- 👥 **Collaborative playlists**, an **Add songs** screen, and full playlist editing.
- 🔀 **Smart Shuffle**, a draggable queue, crossfade.
- 🎙️ **Podcasts and audiobooks** are back, with their own player and downloads.
- 📷 **Spotify Codes** to share, and profiles, Recents, Browse and New releases.
- 🌍 **Four new languages**, in-app updates and a diagnostic report you can send in one tap.

Every change is in the [changelog](CHANGELOG.md).

## Screenshots

Two looks, switched in Settings, Display.

**Immersive**

| Home | Player |
|:---:|:---:|
| <img src="docs/screenshots/immersive-home.jpg" width="200" alt="Immersive home"> | <img src="docs/screenshots/immersive-player.jpg" width="200" alt="Immersive player"> |

**Classic**

| Home | Player | Artist | Album |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" width="200" alt="Classic home"> | <img src="docs/screenshots/player.jpg" width="200" alt="Classic player"> | <img src="docs/screenshots/artist.jpg" width="200" alt="Artist"> | <img src="docs/screenshots/album.jpg" width="200" alt="Album"> |

## Questions

**Is it safe for my account?** Myspoti talks to Spotify the way its web player does, and you sign in on Spotify's own page. Blocking ads is still against Spotify's Terms of Use (see the disclaimer below).

**"Spotify refused to play this"?** Some new or free accounts get refused by Spotify. Settings → **Export a diagnostic report** and send it on [Discord](https://discord.gg/jXKnGVFnGS) or in an [issue](https://github.com/kota-zo/myspoti/issues): it holds the app's recent log (what Spotify answered) and your phone's model, never your password or your login tokens.

**Do downloads count in my Wrapped?** They always count in Myspoti's own recap. With **Sync downloaded songs with Spotify** (Settings → Playback, on by default), a downloaded song played online also plays muted on Spotify so your other devices see and control it; whether Spotify counts that play is up to Spotify and not guaranteed. Offline, or with the setting off, the file plays only from the phone and nothing is reported. Streamed songs and music videos count as usual.

**What leaves my phone?** Your plays go to Spotify. Lyrics are looked up on lyrics sites, song meanings on Genius, translations on Google Translate, music videos on YouTube, and downloads on the hosts your download-source extension names (shown before you install it). Nothing is sent to the developer.

## Not included

- No DRM circumvention, and no Spotify audio download: offline songs come from the download-source extension you install, and a song it has no file for is marked unavailable. Myspoti ships no download source of its own.
- No ad is ever marked as heard.

## Disclaimer

Blocking ads goes against Spotify's Terms of Use, and Spotify may suspend accounts that do it. Use this app at your own risk. If you can, support artists with Premium or by buying their music.

Not affiliated with or endorsed by Spotify. Spotify is a trademark of Spotify AB.

## Support

Myspoti is free, has no ads and is made in my spare time. If you enjoy it, you can support it on [Ko-fi](https://ko-fi.com/myspoti) or [GitHub Sponsors](https://github.com/sponsors/kota-zo), also from **About** in the app. Nothing is locked behind a donation.

News, help and ideas: the [Discord server](https://discord.gg/jXKnGVFnGS). Bugs and requests: [GitHub issues](https://github.com/kota-zo/myspoti/issues).

## For developers

The Android project is in `base/`, split in two:

- **`:api`** is the contract the UI talks to: plain Kotlin models and interfaces (player, search, catalog, library, playlists, home, podcasts, users, lyrics, devices, Jam).
- **`:api-spotify`** implements it on Spotify's Web Player. Playback stays in the official player, in a hidden WebView (`:host-android`), driven by a small bridge script. Catalog, search and library calls go straight to Spotify's servers. When Spotify ships a new Web Player, the app reads its new operation list by itself.
- **`:host-cdp`** runs the same API on desktop Chrome, so the whole API is tested against the real Web Player without a phone.

More: [`base/api-spotify/README.md`](base/api-spotify/README.md) and [`docs/api`](docs/api).

Requirements: JDK 17+ and the Android SDK (compileSdk 37).

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

Every push builds and tests on GitHub Actions. A `vX.Y.Z` tag publishes a stable APK in Releases and a `vX.Y.Z-beta.N` tag a beta one (a prerelease, `tools/check_tag.py` checks the number).

## Credits

- UI derived from [Spotui](https://github.com/Spotui/Spotui) / [Metrolist](https://github.com/MetrolistGroup/Metrolist) (GPL-3.0); Spotui also gave the DASH-to-FLAC remux.
- The Immersive look (player, lyrics, queue, glass tab bar and pages) is ported from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0-or-later).
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0): music videos.
- Lyrics: [LRCLIB](https://lrclib.net), LyricsPlus, lrc.red, Kugou and NetEase; song meanings: [Genius](https://genius.com).
- [Material Icons](https://github.com/google/material-design-icons) (Apache 2.0) and the [Manrope](https://github.com/googlefonts/manrope) font (SIL OFL 1.1, see `base/licenses/`).

Full notices: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## License

GPL-3.0. If you reuse this code, you must credit Myspoti, keep the license notices and
publish your complete source under GPL-3.0. Closed-source or uncredited copies are
violations and will be reported to the hosting platform (DMCA takedown). See also
[`AGENTS.md`](AGENTS.md).
