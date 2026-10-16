# Changelog

Each release on GitHub shows its section below. The app shows it too when it offers the update.

## 2.0.0

A new engine, written from scratch, and a cleaner app on top of it.

- **Podcast sponsors skipped**: in an episode, the sponsor reads, self-promotion and "like and subscribe" reminders that listeners marked on SponsorBlock are skipped, with a short message, and show in colour on the seek bar. Settings, Playback, chooses what to skip (intros, outros, recaps and tangents too). Seek back into a segment to hear it. Only the start of a fingerprint of the episode leaves the phone, never which one plays.
- **Lossless downloads**: the offline downloads are now FLAC. Myspoti finds the same recording on a lossless service (Tidal, Qobuz or Amazon) by the track's ISRC and takes it as it is served, never re-encoded — 16-bit CD quality, **24-bit hi-res when it exists**, or AAC re-encoded on the phone to keep them small (in Settings). Tidal (and Qobuz) come through the same signed servers as SpotiFLAC Mobile's extensions, three songs at a time and three segments per song; the smallest quality takes Tidal's own AAC as it is served. A one-off verification in Settings turns them on. A track with no lossless recording anywhere is marked unavailable.
- **Sound quality, chosen at the start**: a first step after sign-in picks the streaming quality (Automatic, Low 24 kbit/s, Normal 96, High 160) and the download quality (off, smallest AAC 96 kbit/s, FLAC CD, FLAC hi-res). Both stay in the new **Settings** page.
- **Connect shows your headphones**: with Bluetooth or wired headphones, their name replaces "This phone", a "This phone" row sends the sound back to the speaker, and a Bluetooth button opens the pairing settings. The quality badge opens a small page with what plays now (codec and bitrate) and the setting.
- **Downloads ask their quality** if you want ("Ask each time"): once for a whole playlist, kept for its new songs.
- **Liked Songs update at once** after a like or an unlike, without reloading the whole list.
- **No more "bop" between two songs** on the phone's speaker: the sound output stays awake across the change.
- **Updates install in the app**: "Update" downloads the new version with a progress bar and hands it to Android's installer; no trip to GitHub. The first time, Android asks to allow installs from Myspoti.
- **Haptics work again**: buttons, likes and the debug icon vibrate.
- **Solid bottom bar** as an option in Settings, Display.
- **Settings page**: sound quality, downloads, data and battery, display and language, in one place (profile menu or Library's account menu).
- **Italian**: Myspoti's own texts are translated too.
- **Headphones unplugged or Bluetooth gone**: the music pauses instead of moving to the phone's speaker.
- **Stuck ads**: an ad that blocks the player is skipped twice, then the player reloads, so the music does not stop for good.
- **Offline listens count**: a downloaded song that plays on the phone is reported to Spotify like a streamed listen — the real play time, the context it started from — so it counts in Wrapped and the top lists. Offline listens are kept and reported once the network is back.

- **New engine**: the app now talks to Spotify through its own API (`:api`), tested against the real Web Player on every change. Screens load faster: catalog, search, playlists and library come straight from Spotify's servers, in parallel, without waiting for the hidden player.
- **Playback works again on every phone**: Spotify silently ignored play commands sent by an Android WebView. They now leave from the app itself, so songs start and the sound plays.
- **Faster start**: the home feed, your library and Liked Songs show at once after a restart, from the last visit, then refresh. The library and Liked Songs load in the background as soon as Spotify is ready.
- **The player closes into the mini player**: pulled down, it uncovers the page you were on, with the mini player and the tabs already in place (no more grey area).
- **Right music videos**: a fan upload (lyrics, sped up, audio) is no longer taken for the artist's clip, even when YouTube Music files it as official. When in doubt, no clip.
- **Home screen widget** (4x1): the current song with previous, play / pause and next.
- **Songs no longer skipped in a partly downloaded list**: some networks (VPN, private DNS) made the app think it was offline, so only the downloaded songs played (#10). Offline, a message now says how many songs of the list are downloaded.
- The **Account** row in Your Library opens your Spotify account page (#7).
- **Playback goes on with the screen off**: the app no longer leaves the foreground between two songs, so Android stops freezing it after the first song.
- **Switching accounts works**: after a logout, signing in with another account shows that account, not the previous one.
- **Sort by** in playlists and Liked Songs: title, artist, album or duration.
- **Hearts show at once** in playlists: the liked songs were only checked after a tap (a playlist in the check made Spotify refuse it). Lists at the top of the library and the home shortcuts load before you open them.
- **Setup screen** after signing in: notifications, local music, Bluetooth and unrestricted battery, each with its own button.
- **Folders in Local Files**: choose the folders shown; WhatsApp, Telegram, recordings and ringtones start hidden.
- **Lock screen and Now Bar**: play / pause in the middle (like, previous, play, next, shuffle) and a centred app icon.
- **New icon**: one green wave line, on the launcher, the launch screen and the status bar.
- **Beta and release install side by side**: betas are "Myspoti Beta", releases "Myspoti", each with its own updates.
- **Music no longer stops after a few songs**: when the player gets stuck between two songs, the next one starts by itself, and the player reloads if that is not enough.
- **Smart shuffle** in playlists and Liked Songs (tap shuffle twice): the list shuffled, with a Spotify recommendation every three songs.
- **Sort by plays in that order**: a sorted playlist plays and queues in the order shown; **date added** joins the sort options.
- **Saved in** shows your folders and the playlists' covers; a folder's menu creates a playlist inside it.
- **Home loads again by itself** after an error, with a Try again button.
- **Spotify account pages** open in the app's language when Spotify has them in it.
- **Foldables and wide screens**: the player keeps its controls on screen.
- **Spotify keeps working after its updates**: the app picks up the Web Player's new operations by itself when Spotify ships a new version.
- **No ads**: the player tells Spotify it cannot play the ad audio formats, so an ad comes with nothing to play and the next song starts within a couple of seconds. Nothing false is reported to Spotify (no ad is ever marked as heard).
- **Podcasts and audiobooks are back**: shows, episodes, chapters, **Your Episodes** in Your Library, and **playback speed** (from the song menu) for episodes.
- **New releases** in Search: what the artists you follow just released, then the latest releases.
- **Browse all** shows Spotify's real categories (pages of playlists and albums), not keyword searches.
- **Jam** in the Connect sheet: join a Jam with an invite link, share and leave. Spotify keeps starting one to Premium accounts.
- **Your recap** (profile menu): your top tracks, artists and albums over 30 days, 12 months or all time, counting Spotify, downloads and local files. It stays on the phone.
- **Spotify account** (profile menu): the account pages (plan, privacy, devices) open in the app, already signed in.
- **Song menu**: **Credits**, and **Exclude from your taste profile**.
- **More lyrics sources** (lyrics settings): Apple Music word by word, NetEase and Genius, when Spotify has no lyrics.
- **Fixes**: your top artists and tracks, the profile and new releases no longer fail when Spotify's public API limits the app; folders keep working after a rename; playlist details save reliably; the app language works on every Android version.
- **Removed for now**: crossfade, and Spotify's own music videos inside the player (YouTube clips still play).

## 1.1.1

- **Data saver** (profile menu): on mobile data Myspoti uses no data at all. Only downloaded songs and Local Files play, downloads wait for Wi-Fi, and the Spotify player comes back on its own on Wi-Fi.
- **Find in a list**: search inside Liked Songs, a playlist or Local Files (title, artist or album).
- **Your Library**: moving a playlist (hold, then drag) works, in the list and in the grid; the drag stopped as soon as it started.
- **Settings** open in full, so the list scrolls without moving the sheet.

## 1.1.0

- **Downloads**: download a song (from its menu), an album, a playlist or Liked Songs, and listen without a connection, from **Downloaded** in Your Library or from the album or playlist page.
  - The audio comes from YouTube Music, as YouTube serves it (no re-encoding). Each song is checked against the original recording; when none matches, the song is marked as not downloadable instead of saving the wrong one.
  - Files stay inside Myspoti. A downloaded album or playlist stays in step: songs added to it are downloaded, songs that left it (a renewed Blend, a removed song) are deleted, unless another downloaded list or your own download keeps them.
  - Once a list is downloaded, its button becomes a bin. In Downloaded, swipe a song from right to left to remove it, or remove everything from the profile menu.
- **Folders**: Your Library shows your Spotify folders and opens them level by level. The + at the top creates a playlist, a folder (a real Spotify folder) or a playlist on this device; a folder can be renamed.
- **Sort and reorder Your Library**: sort by custom order, alphabetical or creator; hold a playlist, then drag it to move it (kept on this phone). Liked Songs, Local Files and Downloaded stay on top.
- **Local files in playlists**: the + of a song in Local Files adds it to a playlist on this device, or to one of your Spotify playlists, under **Added on this device** (Spotify can't receive phone files, so they stay on the phone).
- **The + of a song**: the first tap saves it in Liked Songs; tapping the green check then opens **Saved in** to add it to your playlists. Saved in only lists the playlists you can change, and says so when Spotify refuses a change.
- **Player**: pull it down from the top to close it, like the back button.
- **Go to song radio** in every song's menu (player, search, playlists…).
- **Music videos**: a title with an apostrophe ("J'SAVAIS") no longer gets the clip of another song; instrumental uploads are skipped.
- **Home** in the bottom bar always opens Home.
- **Search**: playlists in the results, and filters for songs, artists, albums and playlists.
- **Podcasts and audiobooks are hidden for now**; they will come back in a later version.
- **Artist pages**: the whole discography, not only the releases Spotify highlights.
- **Language**: French on a French phone, English on any other, with no more mixed languages. **App language** in the profile menu picks another one (Spotify's labels are translated, Myspoti's own texts stay in English).
- **Steadier and lighter player**: the hidden Spotify player stays on a tested version kept on the phone, so a Spotify website update can no longer break the app overnight, and it no longer loads covers, Canvas loops and fonts nobody sees (about half the memory and a quarter of the requests at startup).
- **Restart the player** in the profile menu, for when the music stops responding.
- **Battery saver**: Myspoti's own saver is independent of Android's (which already lowers the screen rate) and only follows its switch. The AMOLED theme is its own choice too, and returning to the app no longer looks up a music video for nothing.
- **Crossfade**: no background work when it is off, and the setting is kept when the player reloads.
- **About** in the profile menu: the version, the [Discord server](https://discord.gg/jXKnGVFnGS) and the source code.
- **Support Myspoti**: Myspoti is free and has no ads; you can now support it on Ko-fi from the profile menu or About. A short reminder appears a day after installing this version, then every 2 weeks; "No thanks" closes it, and nothing is locked behind a donation.
- New versions are checked every 3 hours instead of once a day.
- Your Library still opens Local Files and Downloaded without a connection, and the notification shows the cover of local and downloaded songs.

## 1.0.4

- **Local Files**: a folder in Your Library, right under Liked Songs, with the music stored on your phone. It plays in the app like the rest (player, mini player, notification, lock screen, headset buttons).
- **Spotify Connect**: the notification now follows track changes on the other device; it could stay on the previous song.
- Fewer updates from the player while it plays on another device (less CPU and battery).
- No like button on local files.
- One more Spotify ad server blocked.
- The update window shows what's new.

## 1.0.3

- **Spotify Connect**: music no longer jumps back to the phone or stops by itself.
  - Playing from Liked Songs goes to the device that is actually playing.
  - Swiping the app away no longer pauses a Connect device.
  - A Connect session closed by the system no longer pulls playback back to the phone.
- **Crossfade** (Settings, 0–12 s): the end of a track fades out and the next one fades in.
- **Background playback**: Myspoti asks once to lift battery optimization, so music and Connect keep going with the screen off. Also in Settings.
- **Android Auto** works again: Liked Songs, playlists, albums, artists, search and voice play.
- **Android 10 and 11** are supported.
- The update window matches the app's style.

## 1.0.2

- New welcome screen before signing in, and a new crash screen.
- Every screen follows the phone's language (official labels in 73 languages, English and French for Myspoti's own texts).
- New icons, new notification icon, older screens removed.

## 1.0.1

- **Spotify Connect**: the notification keeps following the other device with the screen off.
- Reopening the app no longer moves playback back to the phone.

## 1.0.0

- First release: Spotify with a free account and no ads, native interface, synced lyrics, official music videos, Spotify Connect.
