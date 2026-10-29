# Changelog

Each release on GitHub shows its section below. The app shows it too when it offers the update. Every detail: [docs/CHANGELOG-full.md](docs/CHANGELOG-full.md).

## 2.2.0

- 📺 **Chromecast** for extension, downloaded and device songs, without Google Play Services: Cast devices show up in the device picker and the song streams from your phone over your network.
- 📱 **Tablets, foldables and Samsung DeX**: a navigation rail in Classic, a side-by-side bar in Immersive, more grid columns, two-pane players, free rotation, resizable windows and keyboard shortcuts.
- 🚗 **Android Auto**: Home, Library and Downloads tabs with artwork, search, Start radio, and downloaded songs that play offline.
- 🎬 **Canvas in the Immersive player**: the song's clip plays behind the player and the cover opens into it.
- 🎙️ **Podcasts**: unplayed first or hide played, New marks on fresh episodes, played ticks and progress bars, and new releases of followed shows open the show.
- ➕ **Queue whole lists**: Play next and Add to queue for playlists, albums and Liked Songs. With an extension source, your queue follows on Spotify.
- 🎤 **Lyrics**: tap a word to play from that word, lyrics from another catalogue no longer drift, and Genius notes in Immersive.
- 🔁 **Repeat once**, and repeat one restarts at 0:00 on extension songs.
- 🔊 **Smoother sound**: no click on skips and pauses, and a crossfade that really fades, extension, downloaded and device songs included.
- 💾 **Downloads belong to their account**: signing out puts them aside, signing back in brings them back.
- 🛡️ **Safer extensions**: their streams and downloads cannot reach your local network, every redirect is checked, Connect works with them below Android 13, and their errors are clearer.
- 🛠️ **Fixes**: the liked heart is right after a restart and after a like made in the official app; the audio source line no longer pushes the title up; shuffle and repeat always in reach in the Immersive player, with a landscape layout; clickable links in descriptions; song count next to a playlist's duration; artist releases in full date order; Spotify local files in playlists; and the bugs reported on Discord.
- 🌍 Translations of everything new.

## 2.1.0

The first stable release since 2.0.1, with everything from the 2.0.2 betas (their notes are below) and more.

- ✨ **New Immersive look** (optional, Apple-Music-like): Liquid Glass bars that fold away when you scroll, animated player and lyrics, glass sheets, and its own pages for albums, folders, local playlists, extensions and podcast episodes. Switch in Settings, Display; Classic stays the default. Each look keeps its own **accent colour** and shows only its own settings.
- 🧩 **Extensions**: audio and download sources are extensions now, ordered as you like with Spotify among them, found on GitHub, GitLab and Codeberg. Safer: public hosts only, new permissions need your OK, a crash no longer takes the app down.
- 📱 **Spotify Connect with extension sources**: when an extension plays a Spotify song, the phone stays a Connect device. The official Spotify apps and your other devices see it and control it, and the song counts on Spotify through a real muted play at low quality. Turn it off in Settings if you prefer to save data and battery.
- 📊 **Plays count on Spotify**, Recents included. **Listening stats** and **friend activity**.
- 👥 **Collaborative playlists**: leave one, or manage it as the owner.
- 🚫 **Fewer "Spotify refused to play this"** (402) and no songs skipping on their own; better ad blocking (more ad hosts, ads found by their metadata, reloads that stay silent).
- 🔀 **Shuffle in Immersive** goes through shuffle, smart shuffle and off, as in Classic.
- 🔎 **A song played from search** goes on with related songs, no longer with your Liked Songs.
- 📻 **Radios**: the playing-from header and the song title open the right page and album.
- 👋 **New onboarding**: how Myspoti works, then what extensions are and where to find them (shown once after the update too).
- 👆 **Easier taps**: wider back and profile buttons, and the glass bars no longer let taps through to the page.
- 🎧 **Headphones and car**: 15 s skips for podcasts, Android Auto that keeps playing, background playback that keeps running.
- ⚡ **Faster and lighter**: pages and Liked Songs kept on the phone, cached lyrics and searches, a smaller image cache, smoother lists and animations, Remove animations honoured everywhere.
- 🌍 **More languages**: Chinese, Russian, Polish, Turkish, Indonesian, and Spanish for Argentina and Mexico.

## 2.0.2-beta.8

- 🎚️ **Source order** (Settings, Playback and Downloads): put your audio and download sources in the order you want; each song is asked from top to bottom. Spotify has its own place too, first if you like. Each source shows only what matters, details on a tap. Installing a second source no longer clashes with the first.
- 🔄 **Updates**: a Check now button in Settings, About, and the app looks more often (at most once an hour, again 10 min after a failed check).
- 💎 **Liquid Glass** on the top bar of every tab, with a rounded bottom; the blur follows tab changes live. Fixes going back from a playlist that stayed on the playlist.
- ↕️ **Immersive queue**: a dropped song goes straight to its place, without jumping back first.
- 🎙️ **Podcasts**: the headphone next and previous buttons skip 15 s forward and back (Settings, Playback).
- 📖 **Genius** song meanings in the Immersive look.
- 🔒 **Safer extensions**: network calls limited to public hosts, an update that asks for more needs your OK, Spotify writes granted one by one, an extension crash no longer takes the app down.
- 🌍 More texts translated.

## 2.0.2-beta.7

- 💎 **Real Liquid Glass** (Immersive): lens refraction, specular rim and a live blur of what scrolls behind, now also on phones with system window blurs off (Android 13+; live blur on Android 12). Softer springs and press feedback across the look.
- ⏭️ **Songs skipping on their own**: when Spotify drops several songs in a row, the app stops and tells you instead of chaining through your queue. Diagnostic reports now say why every song changed.
- ↕️ **Immersive queue**: dragged songs stay where you drop them, move between "Next in queue" and "Next from", and the list scrolls at the edges.
- 🖼️ **Sharp covers in Immersive** after opening the lyrics.
- 🎨 **Accent colour** now applies to the Immersive look; no more click sound in its full-screen player; lyrics follow the song after a skip.
- ⏮️ **Previous** goes back a song instead of restarting it when tapped right after a skip.
- 🔁 **Reopening the app** brings back the song from an extension or a download, its queue and position.
- ✖️ **Clear button** in search fields.

## 2.0.2-beta.6

- 🔔 **Update popup fixed**: it shows as soon as the app knows about the update, a stray tap no longer closes it, and after "Later" the update stays in Settings, About.
- 🗂️ **Settings in tabs** (classic look): Playback, Downloads, Appearance, Extensions, About. About shows your version and the update when there is one.
- 🧩 **Extensions**: Refresh really refreshes now (no stale cache, real errors, update check of installed extensions). Extensions are also found and installed from **GitLab** and **Codeberg** (topic `myspoti-extension`). Uninstall an extension right from the list (trash icon), which now also clears its sign-in and cache.
- 🚗 **Android Auto**: playback started from the car no longer stops after a few seconds, and the car's buttons keep working.
- 🔀 **Real crossfade** for songs played from extensions, downloads and device files (Settings, Playback). Spotify streams keep their fade out and in.
- 🖼️ **Sharp covers everywhere**: notification, lock screen, Android Auto, share card. Fixes a crash at playback start (#77).
- ✨ **Immersive look**: drag songs to reorder the queue, and the "playing from" source line is no longer cut off.

## 2.0.2-beta.5

- ✨ **New Immersive look** (optional, Apple-Music-like): glass tab bar and mini player that fold away when you scroll, animated player and lyrics, grouped Settings, glass sheets (Spotify Connect, queue, quality…). Offered at the first open after the update; switch any time in Settings, Display. Classic stays the default.
- 🎤 **Lyrics** (Immersive): the new line loses its blur the moment it becomes current.
- 🖼️ **Sharp covers** in the player: the large image instead of a stretched thumbnail.
- 🎧 **Background playback**: the player keeps running when you switch apps or turn the screen off.
- 🚫 **"Spotify refused to play this" (402)**: commands sent like the browser's, a Chromium network retry after a refusal, and no more ghost copy of this phone among your devices. Still refused? Please send a diagnostic report from this version: it now opens with a short summary of the cause.
- ⏭️ **No more songs skipping on their own** when Spotify limits audio licences: the app waits instead of asking again.
- 🧹 **Lighter diagnostic reports**: a third shorter, without the repeated lines.

## 2.0.2-beta.4

- 🧩 **Download sources are extensions now**: the app no longer carries any download source. Install a download-source extension (Settings, Extensions) and downloads work as before: checked, encrypted, kept in Myspoti. Without one, downloads say so.
- 🔌 **For extension authors**: the `download-source` hook, `playback` events (start, progress, pause, resume, seek, skip, end) and `spotify.raw.request` (docs/extensions/API.md).
- 📊 **Plays now count on Spotify**: once the device is registered, the play reports (melody) leave from the app itself.
- 👥 **Collaborative playlists, owner side**: remove someone as a collaborator or from the playlist, or stop the collab (with a warning). Invitation links stay valid until they expire.
- 💾 **No save button** on your own playlists.
- ➕ **Add a playlist or album to another playlist**, with the choice of what to do about duplicates.
- 🗂️ **Library filter chips**: playlists, artists, albums, songs.
- 🌍 **Chinese, Russian and Polish**.
- 📈 **Listening stats**: top artists and tracks (4 weeks, 6 months, year), minutes per week counted on the phone, a "your #N artist" badge, and **friend activity**.
- 🎬 **Music videos**: a switch to turn them off, and **picture-in-picture** for clips.
- 🎧 **Songs played by an audio-source extension**: the player shows which extension plays and the quality it announces, the song shows at once with its large cover, lyrics, Canvas and like button, and the queue works as with Spotify (Play next, Add to queue, the song's radio after the last song). A song no extension has plays on Spotify instead of being skipped, and the next one goes back to the extension.
- ⚡ **Faster starts with extensions**: the next song is prepared during the current one, and a source that is slow or down is left sooner.
- 🩺 **Extensions say whether their source answers**: one that does not is shown inactive and skipped until it answers again. The extensions screens are simpler.
- 📴 **Likes and additions made offline** are sent when the network is back.

## 2.0.2-beta.3

- 👋 **Leave a collaborative playlist** you joined, from the playlist's menu, like on the web player.
- ⬇️ **Downloaded playlists play from your phone**: picking a song in a fully downloaded list plays its file, online too, instead of streaming it again.
- 🩺 **"Spotify refused to play this"**: the diagnostic report now says much more about why Spotify refuses. If it happens to you, please send a report from this version.

## 2.0.2-beta.2

- 🎵 **Playback works again**: beta.1 could leave Spotify's player without a device ("Device not found"), so nothing played. The page now starts exactly like in 2.0.1: the play reports leave from the page again, and the check for protected audio runs only after a play fails.
- Everything else from beta.1 stays (Recents, refused plays, ads, Connect, repeat, sign-in, downloads, Android Auto, playlists, font).

## 2.0.2-beta.1

- 📊 **Your plays count on Spotify**: the report of what you really listened to now leaves from the app like the playback itself, so normal plays should reach your Recents and your Wrapped. Recents also no longer hides a play whose playlist or album row was not announced.
- 🎵 **"Spotify refused to play this"**: a play Spotify acknowledges but never starts now goes on to the fallbacks instead of staying at 0:00.
- 📺 **Ads**: a song left paused right after an ad break starts by itself.
- 💻 **Spotify Connect**: while another device plays, Myspoti no longer seeks or skips on it, so the computer stops jumping tracks.
- 🔁 **Repeat** keeps your choice after a new play or a reload.
- 🔒 **No sound at all?** If your WebView cannot play protected audio, the app now says so and tells you to update Android System WebView and Chrome.
- 🔑 **Sign-in**: a slow phone is no longer sent back to the login page in a loop.
- ⬇️ **Downloads**: slowed, remastered and featured versions are found.
- 🚗 **Android Auto** no longer freezes on big libraries (300 items per folder).
- ❤️ **The + button** of other songs is no longer checked because an own playlist plays.
- 🖼️ **Playlists without a cover** show the mosaic of their albums; big playlists show their controls sooner.
- 🔤 **New font, Manrope**: Cyrillic and Greek text now uses the app's font.

## 2.0.1

- 🎵 **"Spotify refused to play this"**: on the accounts where Spotify refused every play (free accounts on some phones and in some countries), a refused command is now sent again the way other web-player apps send it, and playback keeps going that way when it works. If it still refuses, please send a new diagnostic report: it now says which way was tried.

## 2.0.0

Myspoti 2.0 is a new app, rebuilt from the ground up: Spotify with no ads and with lossless downloads, on a free account.

- 🚫 **No ads, on a free account**: the ad breaks of Spotify's own player are switched off at the source. Pick any song, skip as much as you want.
- ⬇️ **Lossless downloads**: songs, albums, playlists and Liked Songs in FLAC (CD or 24-bit hi-res) or small AAC — the very recording, found by its ISRC on Tidal or Qobuz, encrypted on your phone. Downloaded playlists stay in step.
- 🚀 **A new engine**: faster screens, quicker skips, and playback that recovers by itself.
- 🎤 **Word-by-word lyrics** from several sources, with translation and **song meanings from Genius**.
- 🎬 **Official music videos**, in sync, in full screen, silent or with the clip's own sound.
- 👥 **Playlists**: create and edit them, **add songs** from a search, **collaborative playlists** with invites and who added what.
- 🔀 **Smart Shuffle**, a draggable queue, crossfade, sleep timer.
- 🎙️ **Podcasts and audiobooks**, with their own player and downloads.
- 📷 **Spotify Codes** to share, profiles, Recents, Browse, New releases, Your recap, Jam, Android Auto.
- 🌍 **7 languages**, in-app updates, and a diagnostic report to send in one tap.

This release installs as **Myspoti**, next to Myspoti Beta if you have it.

## Since 2.0.0-beta.9

- **Word-by-word lyrics from more sources** (Apple Music timing through LyricsPlus, lrc.red, Kugou), lined up with Spotify's own, words lit whole as they are sung; a setting picks word by word or line by line.
- **Song meanings from Genius**: the light bulb opens the story of the song; underlined lines open their explanation with a long press, best-liked first, translated into your language as they arrive.
- **Music videos with their own sound**: "Sound: clip" plays the clip's audio while the song keeps playing silently on Spotify (it still counts). Clips in 1080p on Wi-Fi, fewer freezes, the full-screen button in the clip's corner, the right cover after the clip, and label uploads found when Spotify itself has the video.
- **Add songs to a playlist** from a Spotify search right under its cover, with suggestions.
- **Collaborative playlists**: invite with a link, members' faces in the header, who added each song, and collaborators can add and remove songs.
- **Share as a Spotify Code**, with the link under it.
- **Queue**: a line shows where a dragged song will land, and a song moved into or out of the queue no longer gets stuck.
- **Smart Shuffle**: suggested songs are marked and can be kept with +, and they light up when they play.
- **Shared links open the page** (playlist, album, artist, show) instead of starting it.
- **Fixed**: a crash when typing or deleting in a playlist's search while music plays, a skip when a clip went full screen, the status bar over the login page, the + of a song played from your own playlist, and lyrics hard to read on some colours.
- **A crash or a freeze now shows in the next diagnostic report.**
- **A new welcome screen** and a smoother setup.
- **Smoother player, lighter on the battery**: the hidden player page was redrawn with every frame the app drew (and kept animating its own UI underneath), and the progress bar redrew the whole player 15 times a second. The page now stays out of the app's drawing and the bar redraws only itself, as often as it actually moves. Measured on the player screen: 0 late frames instead of 86 %, about a third less CPU — and a tap on a song or "next" is heard ~150 ms sooner.
- **The player recovers from a page that fails to load**: when Spotify answered an error to a reload (seen: 504), the app stayed on it, refusing every command until restarted. The page is now loaded again, sooner first, then every minute.
- **"Next" that does nothing**: when the player page answered "ok" to a skip but stayed on the song (the sound dipped, then nothing), the song after it is now asked for by name.
- **Spotify refusing playback (402)**: after a refusal, the app now also tries taking playback to this phone first, then the list on shuffle, before telling you; the diagnostic report now says which kind of account plays and whether a refused command targeted this phone or another device.
- **Smart shuffle from the player keeps the song playing**: on a long playlist the song was often not found and a random one started; the playing song now always stays first.
- **Fast taps on "next" in the player land where they should, and the screen follows**: each tap showed the same second song (held for seconds while the page went on to the fifth), and a first skip Spotify applied late could push the player one song past the target. The screen now shows the song the taps add up to, and where the skips landed is checked and corrected.
- **A song picked just before an ad plays after the ad** instead of the old queue going on (reported with a song picked in another playlist).
- **Profile playlists without a cover of their own show their mosaic** instead of a grey square.
- **"Restart the player" in Settings says it did.**
- **Shuffle no longer flips back**: a state sent by the page before it took the tap could turn the button off again, and the list and the player then disagreed.

## 2.0.0-beta.9

- **Fast skips during an ad or a reload**: the song your taps point to plays once the ad or the reload is over, and the screen shows that same song (the sound and the screen no longer drift apart); "previous" during an ad no longer restarts the ad.
- **Ad breaks are stopped at the source**: the player page's own ad managers (audio and in-stream) are switched off from the app, and again each time an ad shows up, instead of patching the web player's own files (the way SpotX does it, done here at runtime so an update of the page cannot break it). The device also no longer tells Spotify it cannot read mp3 (`file_urls_mp3`) — the registration some free accounts were refused for (`402 COMMAND_FAILED`).
- **No more silence until a restart**: an ad mute whose unmute was lost (the page never reports the end of a break) is lifted after 75 s, the mute is only trusted once it reached the media elements, and a podcast stalling is caught like a song.
- **The login page can no longer spin for ever**: the app does not wait on the address alone any more; no signed-in token 25 s after the player page came back, and the login page is shown again so it can be tried.
- **The page rebuilds itself after a renderer crash** (the reported "the app crashed", "no sound until the player is restarted") instead of leaving a dead page behind.
- **One client identity across launches**: the client token's device id is kept, so Spotify no longer sees a new client at every start.
- **Your phone tells Spotify its own name**: the device registration now carries the phone's name (the one Android knows), so the other devices' list shows it instead of a generic one, and the registration is again exactly what the real web player sends (no ad-format difference left to single it out).
- **Every install looks like its own browser**: the browser version and the system it claims are drawn once per install instead of being the same string on every phone. A single rule could have covered every Myspoti at once; it now covers one client, like a real browser would.
- **A login that goes nowhere leaves a trail**: each sign-in step reaches the log (`login step #n POST /login/v3/…`) with the status of anything Spotify refuses, instead of ending on a spinner with nothing to look at.
- **A report you can send us**: Settings → **Export a diagnostic report** writes the app's recent log (the player page, the relay, the ads, the refusals) and the device details to a file and opens the share sheet — the log needed `adb` until now, so a report of a refusal or a login that fails was impossible to hand over.
- **Covers and profile pictures that never loaded are fixed**: the profile endpoints hand out `spotify:image:…` identifiers rather than a picture's address, and the image loader was given that identifier as it was. Public playlists of a user and their profile picture now show their covers.
- **Upcoming lyrics are readable on a dark card**: they were drawn in black at 55 % of opacity, so invisible on the AMOLED card; they now follow the card's own background.
- **During an ad the screen says so** instead of leaving the previous song stuck on "buffering", and the player header keeps the playlist's own name when Spotify's label comes back empty; the artist page has its own options menu again and the separator reported missing from the profile header is back.
- **No more light band under the app**: the navigation bar was left to the system (a white strip with dark icons under an always-dark UI) — it now blends in.
- **Crossfade, for real this time**: the player had a stub that always answered "no crossfade" and the setting had been removed, so the slider testers were looking for did nothing. Settings → Playback now offers Off / 3 / 6 / 9 / 12 s: the current song fades out over its last seconds and the next one fades in — one player, one stream, so the two never overlap, and the label says so. Two independent fail-safes put the volume back, and no exit path can leave the sound down.
- **Scrolling the player no longer opens the lyrics**: any upward drag used to count as the swipe shortcut; it now takes a deliberate flick, judged on release.
- **A Connect switch no longer restarts the song**: when another device had taken playback and Myspoti takes it back, the position is remembered and restored if the page rolled back to the start.
- **The notification keeps up in the background**: the refresh loop waited for a device that had taken playback, which only a pull-back sets — so an app started in the background never refreshed it. It now runs whenever the service is up.
- **Local Files lists what other players list**: the query only took what Android's index calls music of 30 s or more, so a phone full of recordings or downloads showed "your audio files will appear here" while another player listed them (reported: 29 tracks). Everything over 20 s now counts, minus Ringtones/Notifications/Alarms/Android — 59 files listed on the test phone where 77 exist.
- **Remove a song from a playlist, and add it to another**: a playlist you can edit now offers « Remove from this playlist » in the song's own menu (with Undo), and « Add to another playlist » opens a picker of your playlists with a search field.
- **Stop all downloads**: one action cancels everything running or waiting **without deleting a single file** (« Remove all downloads » is still the separate, destructive one).
- **Updates on your terms**: a setting turns the automatic check off (it stays on by default) and « Check for updates now » is right there.
- **The mini player names your headphones**: Bluetooth icon and device name (or « Headphones » for wired) when the sound does not come from the phone.
- **Android Auto works from a cold start**: the car's tree is there at once and a browse or a play waits for the player instead of failing while it loads.
- **The player keeps its colour on AMOLED**: the tint from the cover's colour used to disappear in AMOLED mode; it is now there in both, as asked.
- **A song Spotify marks unavailable is still tried**, and if it really cannot play the app says « Not available in your region » instead of leaving a tap that does nothing.

- **Podcasts download without SpotiFLAC and play like in the stock app**: an episode now downloads with a free account — the file is the **publisher's own plain MP3** (`passthroughUrl`, resolved right before each attempt), fetched in chunks with resume, checked (type, header, size), encrypted and kept in the offline store under `episode:<id>`. Spotify exclusives (`passthrough = NONE`, Widevine-encrypted) are **refused with a clear message**, DRM is never attempted. A queue of its own, one worker, resumed at start-up.
- **Show and episode pages**: a header with the cover and its gradient, publisher/authors/narrators, rating, **Follow**, Episodes/Chapters and About tabs, newest/oldest sort, episode rows with "11 min left" and a "played" mark; the episode page has its actions (✓, download, share, ⋮, Play) and **"Mark as played / unplayed"**, written to the account's collection (so it follows you). An audiobook page is no longer empty: chapters plus authors and narrators.
- **A podcast player of its own**: −15 s / +15 s, speed 1 → 3×, a clock that toggles elapsed/remaining, sleep timer, no shuffle or repeat. Two causes of episodes stopping in the background are fixed: the autoplay guard paused episodes, and a resume the page ignored was never asked again.
- **The stock app's gestures**: tapping the active tab again scrolls **Home** and **Your Library** (list and grid) back to the top without reloading, tapping **Search** again focuses the field; tapping the **cover** in the player switches to an **immersive full screen** (controls folded into a strip), undone by another tap — the quick swipe to the lyrics still works.
- **Recents, as in the official app**: the entry leaves Your Library for the profile panel, under "Your recap". The page has a "Recents" header with back, **Music / Podcasts** pills, dated sections (Today, Yesterday, then the date) and rows **grouped by container** — "4 tracks played • EP • …", "9 tracks played • Playlist", "12 tracks added • Playlist" for Liked Songs (heart badge), an artist with their round picture, a Blend — 56 dp covers, a round chevron to **unfold** the container's tracks, a tap on the row to open it. The counts come from the page itself (groups, content type, "added" vs "played"), not a guess, and match the stock app's on the same account.

- **A bottom bar that looks like Spotify's**: the icons were declared bigger but a Material component clipped them to 24 dp, the tabs sat in the middle of the bar instead of spreading evenly, and the selected label could turn grey under a white icon. The bar now uses three equal tabs across the full width, 28 dp icons, 11 sp medium labels, Spotify's idle grey, and the selected tint on both icon and label — on top of the system gesture bar.
- **Signing in no longer lies**: Spotify's e-mail step sends the page back to open.spotify.com, and the app took that address for a session — so leaving it to read the mail ended on an anonymous player with nothing playable. The sign-in page now stays in front until a real token exists, the short grace only counts while the app is on screen, coming back re-reads the token, and an anonymous token sends you to the sign-in page instead of pretending.
- **The app presents itself like a real browser, consistently**: the user agent now carries the reduced version every desktop Chrome sends (no build number in it), the brand list follows the version the way Chromium builds it, and what is remembered per install is how far behind the WebView it sits — so the claim follows WebView updates instead of drifting.

- **Dragging a song to the edge of the queue scrolls the list**: the reorder used to work only among the visible rows; a drag near the top or bottom now scrolls the sheet (speed growing toward the edge) and the song can be moved across the whole queue.
- The log line `login=` (it only meant "the page is on a login URL") is now `session=signed-in|anonymous|login-page`, read from the token; a relayed request now carries the page's cookies and the browser's own transport headers.
- **Fewer needless reloads**: a page that misses one answer (a long render, a busy main thread) is no longer taken for a dead one — the reload waits for two silent ticks in a row, so a short hiccup does not cost a full page reload.
- **An episode stuck at its end goes on to the next one** instead of starting again from 0:00 (the stall recovery only knew songs).
- **Spotify's colours everywhere, and smoother motion**: switches, sliders, radio buttons, dialogs and spinners took the phone's wallpaper colours (or Material purple); they now use Spotify's green and greys. Covers fade in instead of popping, play/pause morphs instead of swapping, the mini player's colour cross-fades from one song to the next, the podcast player takes the cover's colour, unfolded Recents rows slide in, and the bottom bar's fade is dense enough that covers no longer show through the tab labels. The episode page's Play button now turns into Pause while it plays.

## 2.0.0-beta.8

- **SpotiFLAC**: when it is not verified (or the verification lapsed), Downloads and any download open the verification first, then the songs download; its page is full screen (Retry and Skip were hidden under the mini player); sessions follow SpotiFLAC Mobile's rules (renewed once, in their last hour, and a download refused because another one just renewed it is sent again), so many downloads at once no longer end the verification; you are told when it must be done again.
- **Automatic downloads wait for Wi-Fi**: downloaded lists keep in step only on Wi-Fi; a download you start on mobile data goes ahead with a warning.

## 2.0.0-beta.7

- **Music that keeps playing**: a frozen player page is caught and reloaded in seconds, and the song goes on where it was; no more silent songs after a skip, "previous" or an ad; play right after launch works.
- **Skips about three times faster** (~0.8 s from tap to sound instead of ~2.5 s), and much less work in the background: far fewer calls to the player page and to Spotify.
- **Ads**: an ad that gets through is silent, and ad breaks are shorter.
- **Myspoti plays on this phone again** when the Spotify app (or another device) took playback without being picked here; free accounts refused by Spotify in some countries get a fallback and a message.
- **Canvas**: the player opens straight on it, with a smooth fade when it is still loading; tap it to watch it alone. No more flashing buttons.
- **Faster artist pages and playlists**; your **liked songs by an artist** come first and open their own page; hearts are right on long lists.
- **Smart shuffle** keeps the song going, adds songs to every playlist and has its own icon.
- **New**: followers and following on profiles, episodes in playlists, unavailable songs greyed out, fast scrollbar, share as image (Instagram story), swipe up for the lyrics, foldable-friendly player.
- **Settings**: autoplay, accent colour, screen rotation.
- **New languages**: German, Spanish, Portuguese and Greek.
- **GrapheneOS**: the player loads with "secure app spawning" on.
- **GitHub Sponsors**: support Myspoti on GitHub Sponsors too, next to Ko-fi.

## 2.0.0-beta.6

- **Fewer ads abroad**: ads from other countries' ad servers are blocked, sponsored playlists and promotion shelves are hidden; ads are recognised even when they look like songs; an ad that still gets through is muted and skipped.
- **Steadier player**: play, pause and skips go one at a time (fast taps no longer jam it), and a song that did not start is asked again.
- **No more "pop" between songs**: a very short fade on every skip.
- **Smart shuffle from the player**: tap shuffle twice on a playlist or Liked Songs.
- **Remote control**: with another device playing, the notification and the slider keep up.
- **Sleep timer "end of track"** works with episodes, local files and repeat one; artist pages load the whole discography.
- **Artist discography** (Releases) shows albums and singles again.
- **Long names fit**: long playlist names and artist mixes no longer push the layout; big titles shrink to fit, cards in a row keep the same height.
- **A pause stays a pause**, even right at the end of a song.
- **Downloads are safe**: a damaged download list no longer deletes your downloaded songs.
- **Changes Spotify refuses are undone on screen** (likes, playlist visibility), with a message.
- **Search** keeps your query when you come back, and says when it is loading, empty or failed (with Try again). Artist and profile pages can be reloaded too.
- **Jam**: join with a copied link; sharing an episode works.
- **Logout** stops everything (local songs, video, notification); the data saver applies from launch.
- Player buttons are named for TalkBack.

## 2.0.0-beta.5

- **Encrypted downloads**: downloaded songs only play in Myspoti, on your phone.
- **Offline queue**: the queue of downloaded songs shows what really plays next, and can be edited.
- **Downloaded songs are no longer reported to Spotify**: no more album starting over a downloaded song.
- **In-app updates**: download and install without leaving the app.
- **Jam QR scan** no longer crashes; a banner tells when downloads need the SpotiFLAC verification.
- **Your playlists, edited in the app**: name, description, cover (from your photos), public, private or collaborative, delete.
- **Profiles**: search people, open a profile (followers, public playlists), follow; a playlist's owner opens theirs.
- **More from Spotify**: "Don't play this artist", save episodes, search suggestions, "More like this" under albums, library sorted by recents or recently added, pin and move items into folders.
- **Sort in both directions** (tap the chosen sort again), remembered for each playlist.
- **Fixes**: AMOLED black everywhere, lists no longer end under the mini player, full-screen music videos show, the Spotify account page stays under the status bar, "Saved in" confirms each change, fast skips no longer jam the player, no white screen on phones in light mode.
- **Security and reliability fixes** (Android Auto, library pages, logout, links, video lookups).

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
