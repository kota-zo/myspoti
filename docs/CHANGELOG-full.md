# Myspoti — detailed changelog

Every change, release by release. The short version is in [CHANGELOG.md](../CHANGELOG.md), which the app and the GitHub releases show.

## 2.2.0

- **Chromecast without Google Play Services** for extension, downloaded and device songs: Cast devices in the device picker, a small CastV2 sender on the Default Media Receiver, songs served from the phone on the LAN behind a per-session token with Range support; Cast closes the connections it serves when it stops. Cast audio devices get the speaker icon in Connect.
- **Large screens**: window size classes, a navigation rail in Classic and a side-by-side floating bar in Immersive, more grid columns and centred pages, two-pane players, free orientation, resizable DeX windows, keyboard playback shortcuts.
- **Android Auto**: Home, Library and Downloads tabs with grids and artwork; search with playlists, albums and artists; Start radio on lists and artists; downloaded songs played from their files and browsable offline.
- **Immersive player**: Canvas behind the player (same lookup, cache and settings as Classic), the cover opening into it and fading back when the lyrics or the queue open; shuffle and repeat always in reach (#93); a landscape layout for songs and podcasts; the source line's room kept from the start so it fades in under the title; glass sheets opened from the player refract the player; the glass redrawn while a page settles.
- **Podcasts**: unplayed first / hide played in shows and Your Episodes, New marks since the last visit, a dot on followed shows on Home, played ticks and progress bars in both looks; Discover's new releases of followed podcasts open their show or episode.
- **Queue**: playlists, albums and Liked Songs queued whole (Play next, Add to queue, Library long press), each song through the player that can play it; while an extension song plays, songs queued here are queued on Spotify in the same order, with removals and moves.
- **Lyrics**: a tap on a word-synced line plays from the word tapped; word timings from another catalogue only moved onto Spotify's timing when the gaps agree (lyrics cache started afresh); lyrics hold while an extension song's audio loads; Genius notes in Immersive.
- **Repeat**: Repeat once; repeat one restarts extension songs at 0:00; the repeat tapped is kept on extension songs.
- **Sound**: a skip sends the command only once its fade is low (no click), the level guard leaves pause, skip and rise fades alone; Spotify crossfade fades that really fade; crossfade for extension, downloaded and device songs with blended covers and colours; recovery after ads and skips on slow networks (port of #74); no fragment of the previous song on a skip.
- **Liked state**: a restored song is checked with Spotify, a failed check is retried once and never written as "not liked", and the current song is checked again on return to the app and when the player opens (at most once a minute); one `liked check` line per check in the diagnostic.
- **Downloads per account**: put aside on sign-out (not deleted) and brought back on sign-in, older downloads given to the first account seen; download parts checked against Content-Range and file metadata.
- **Extensions**: media and downloads through a safe client (local and private addresses refused after DNS, redirects followed by hand and re-checked, headers dropped on another host, no https to http); optional structured outcomes with app-bounded cooldowns, only script bugs turn an extension off; HTTP bodies read with a charset name so Connect works below Android 13 (#98); update cards install same-version-code changes, with a filled Accept button.
- **Other fixes**: song count next to the playlist duration (#94); artist releases ordered by full release date; Spotify local files in playlists, played from a matching device file (#80); clickable links and decoded HTML entities in descriptions; one tap per artist in the player; cosmetics (#49, #75, #85, #86); Discord fixes (Connect volume, Shuffle pill, covers in Add to another playlist, search in Immersive Downloads).
- **Build**: `-Pabis=arm64-v8a` for single-ABI test APKs; dependency bumps (coroutines 1.11.0, material3 1.5.0-beta02, media 1.8.0, Gradle 9.8.2, ktor 3.6.0).
- **Translations** of the new strings in French, German, Spanish, Italian, Portuguese, Polish, Russian, Chinese and Greek.

## 2.1.0

Stable release of the 2.0.2 betas (see [CHANGELOG.md](../CHANGELOG.md) for their notes), plus:

- **Connect mirror for extension sources**: while an audio-source extension plays a Spotify song, the Web Player plays the same song muted at low quality as this phone's Connect device. Controls from other devices (official apps included) reach the local player through the track-playback state; transfers away and back, ads and page reloads are held; drift above 1.5 s is corrected. Setting in Playback (on by default, shown only with an audio-source extension).
- **Per-look settings**: one accent colour per look (seeded from the old one); each look's settings list only what concerns it.
- **Immersive pages**: album look-up, folders, local playlists, extensions store / detail / extension page, podcast episode player.
- **Immersive shuffle**: the list's Shuffle pill starts the list shuffled, then cycles shuffle → smart shuffle → off while it plays; the player's shuffle does the same.
- **Search**: a song played from search is played alone, without the list that last registered it (Liked Songs), so Spotify's autoplay follows.
- **Radios** (Classic): the playing-from header opens only real Liked Songs links; the song title falls back to the queue's album, then the track's album, then a name search.
- **Taps**: `wideClickable` (touch bounds expansion) on back buttons; profile buttons take the space around them; the Immersive top bars stop taps from reaching the page without cancelling their own buttons; Library grid feeds the bars' blur.
- **Ads**: DoubleClick's media CDN `s0.2mdn.net` blocked (from coldMEW/Hush, GPL-3.0); ads found by content type and ad ids; a playing muted ad is skipped before any reload; a reloaded page stays muted until a song plays.
- **Data and performance**: Liked Songs read in full once a day, album pages kept on storage, playlist songs reused for 5 min, search debounce and cache, lyrics memory and disk caches with background prefetch on unmetered networks, Glide disk cache at 150 MB with one shared 128 px decode, scrubber read in the draw phase.
- **Extensions API**: optional `attest` action (per-extension Android Keystore key with attestation), see docs/extensions/API.md section 10.
- **Release hygiene**: freeze watch only in beta and debug builds, no query strings in WebView log lines, a stable build fails without a signing key.

## 2.0.1

- **402 `COMMAND_FAILED` on every play** (free accounts: Xiaomi Redmi 13 / Indonesia, Samsung A32 / Belarus, issue #55): the same accounts play in Spotui, which sends the Connect commands from the page itself, while Myspoti relays them through OkHttp. A relayed play or transfer answered 402 is now sent once more with the page's own `fetch`; if that one is accepted, the page's commands keep going that way, and only while something plays (checked 10 s later: a command sent from the page can be acknowledged and never delivered). Both refused: the refusal steps run as before. The diagnostic logs the retry's status and whether the page's way was kept.

## Unreleased

- **Skip intent**: next / previous / "skip to" made while the page cannot take them (an ad is current: "The operation is not allowed"; a reload: "local player is disabled", "not ready") move an intent instead of reaching the page; the screen shows that song, waiting. Whatever recovers playback (the ad recovery, any reload, the end of the ad) plays the intent, not the song after the last state seen; skips made meanwhile move it and the newest wins. A plain next is remembered too: if the page goes into an ad break instead, that song is asked for again after it. "Previous" refused during an ad is held, never turned into a seek (which restarted the ad).
- **Ad breaks**: the watcher wakes as soon as an ad starts (its recovery starts after 1 s, no longer up to a 2 s tick later).
- **Tests**: `tools/playback_test.py` fakes an ad in the page (`ad_taps`, `ad_previous`) and reloads it under taps (`reload_taps`), and checks that the app's media session shows the song that plays.

## 2.0.0-beta.8

- **SpotiFLAC**: when it is not verified (or the verification lapsed), Downloads and any download open the verification first, then the songs download; its page is full screen (Retry and Skip were hidden under the mini player); sessions follow SpotiFLAC Mobile's rules (renewed once, in their last hour, and a download refused because another one just renewed it is sent again), so many downloads at once no longer end the verification; you are told when it must be done again.
- **Automatic downloads wait for Wi-Fi**: downloaded lists keep in step only on Wi-Fi; a download you start on mobile data goes ahead with a warning.

## 2.0.0-beta.7

- **Notification icon**: the media notification (status bar, Samsung Now Bar) showed the engine's generic two-note icon; the app now ships its wave mark under the same resource name, so it replaces it.
- **Silent media is paused by the WebView**: Chromium pauses a hidden page's media that starts (new song, restart, seek) while muted or at volume 0. Skip fades went down to 0 and ads were muted with `muted`, so "previous" mid-song and the song after an ad stayed paused. Fades now stop at a tiny level and ads are silenced with an unheard element volume (0.001), never `muted`.
- **Fades are local**: they move the media element's volume, no longer the Spotify Connect volume (a network command, refused on some accounts, which could leave the sound at 0). The volume guard checks the element too.
- **Playback supervisor**: every 2 s the page's media element is read (`media.state`). A song the page says it plays here while the element stands still for 6 s is nudged (seek where it was), then the page is reloaded. A page that answers neither that read nor a ping is reloaded at once (a few seconds instead of a 20 s call timeout).
- **Reloads resume**: any reload (ours, the WebView's, a renderer restart) remembers the song that played and its position; once the new page is up it is resumed or played again there, checked and retried up to 3 times (a new page says ready before Spotify Connect knows its device: "No such device").
- **Commands while the page loads**: calls during a reload fail at once instead of timing out; play / pause / skip wait up to 30 s for the page; playback commands refused with "No such device" are sent again.
- **Pull back from another device**: the active device is read fresh before each transport command (after a cold start the app did not know another device had playback); the pull is bounded to 6 s, and a pull that hangs (a device that went away) reloads the page, which then claims playback itself. A pause never pulls playback back.
- **Previous refused** by the page: the song restarts, or the one before plays.
- **Ad breaks are shorter**: an ad the page "plays" without a file refuses every skip, so the page is reloaded after 1 s instead of after two refused skips (~4 s less silence per ad break).
- **Skip latency** (tap to sound, measured on the phone with `tools/skip_latency.py`): median 2.5 s → 0.8 s.
  - The next song is preloaded 2 s into each song (`player.preloadNext`, the page's own `_preloadUpcomingTrack`, which it otherwise runs only 10 s before the end): its file, first segment and manifest are cached.
  - The first "next" tap is sent at once; only taps that follow within 350 ms are gathered into one jump (the first one used to wait 350 ms).
  - The skip fade is 3 short steps without a read before them (~0.2 s → ~0.07 s).
  - The hidden page is parked on the settings page (`/preferences`, ~260 nodes): on the home page each song change redrew ~7000 nodes and ~190 images and held the main thread ~1 s. A real Web Player page, so that any reload still boots the player; the host's own reloads load the player's URL.
- **Debug builds**: `tools/playback_test.py` drives the phone through 14 playback scenarios (debug-only `TestControlReceiver`), and track-playback responses carrying an ad are kept in `cache/ad-samples`.
- **GitHub Sponsors**: support Myspoti on GitHub Sponsors too, next to Ko-fi (About, Support Myspoti); the About sheet opens in full.
- **Long playlists open and scroll faster**: the list is filtered and sorted again only when the search, the sort or a like changes (not at every recomposition), and rows keep their identity across sorts and filters.
- **Notification and lock screen no longer churn**: only a real change (song, play/pause, buffering, heart, shuffle) reaches the media session and the notification; the position is extrapolated by the system in between.
- **Skips a little faster and less work while music plays**: a fade is one call to the page instead of three (the ramp runs in the page while the command is on its way), the volume check comes with the media read instead of polling every 6 s, and the watcher looks every 4 s instead of 2 s when nothing plays.
- **Hearts (liked states) are asked once**: a page and its prefetch share the same request, and what Spotify confirmed less than a minute ago is not asked again (opening a playlist asked the whole list twice, then again for each page of tracks).
- **Far fewer calls to the player page**: "ready" is announced once per page load (twice meant every listener of it ran twice: state, token, quality, volume), the web token is fetched once and shared, and the Connect device list is read at most every 6 s in the page instead of every 2 s.
- **Myspoti's icon in the notification** and in Samsung's Now Bar, instead of a generic music note.
- **Ads that get through are silent**: they are muted on the page itself, no longer through a Spotify Connect volume command that some accounts refuse (the ad was heard). The song after the ad no longer stays paused.
- **Previous no longer pauses**: "previous" in the middle of a song restarts it and keeps playing (the skip fade left the sound at zero, and Android's WebView pauses silent media in the background).
- **Music comes back after a page reload**: when the player page reloads (frozen page, low memory, WebView restart) the song goes on where it was instead of staying paused; a frozen page is caught in a few seconds.
- **A song that stops being heard is restarted**: the app checks that the music really plays, not only what the page says.
- **Play right after launch works**: taps made while the player loads are sent once it is ready.
- **Shorter silence on ad breaks** (free accounts): about 4 s less.
- **Skips are about three times faster**: the next song is heard ~0.8 s after the tap instead of ~2.5 s (the next song is loaded ahead, the first tap is sent at once, a shorter fade, and the hidden Spotify page no longer redraws its home screen at every song).
- **Cover back to its full size** in the player.
- **Myspoti's buttons play on this phone again** when another device (the Spotify app on the same account, for instance) took playback without being picked in Myspoti: the music comes back here first, instead of driving that device (where skips, previous and volume were refused).
- **Faster artist pages and playlists**: an artist seen in the last minutes opens at once (and its releases page too), the discography and a playlist's first songs are asked for together with the page, and your liked songs load once per session instead of on every artist page.
- **Your liked songs by an artist** come first on its page, above the popular tracks, and open their own page (as in Spotify), where play and shuffle play only them.
- **Hearts right on long lists**: liked songs of a long playlist (or of an artist's liked songs) no longer show without their heart; the check goes by batches of 50, and an incomplete answer is never kept as "not liked".
- **New languages**: German, Spanish, Portuguese and Greek, on every screen.
- **Music no longer stops for a minute**: a Web Player page that stops answering is reloaded at once, and playback goes on.
- **No more silent songs**: the sound comes back by itself if a skip or a muted ad left it at zero; after a skip it comes back as soon as the next song is there.
- **Smart shuffle** goes on from where the song is (no restart), adds songs to every playlist, and has its own icon in the player.
- **Free accounts refused by Spotify** (some countries): the list plays from its start instead, and a message explains why.
- **GrapheneOS**: the player loads with "secure app spawning" on.
- **Followers and following** lists on profiles; **your liked songs** on artist pages; **episodes** in playlists; unavailable songs greyed out.
- **Fast scrollbar** on long playlists and the library.
- **Share as image** (Instagram story and more); swipe up on the cover for the lyrics; player fits foldables and wide screens.
- **Settings**: autoplay on or off, accent colour, screen rotation (portrait, automatic, landscape).
- **Canvas**: loaded while the mini player shows, so the player opens straight on it; when it is still loading, it fades in smoothly over the cover. Tap it to watch it alone (the player hides), tap again to bring the player back; it is a little darker under the player so the text reads. Its previous / next buttons no longer appear.

## 2.0.0-beta.6

- **Ads in other countries**: Google's ad servers (audio and video ads), the ad event tracker and Spotify's ad endpoints (`/ads/`, `/ad-logic/` on spclient) are refused. An ad that plays anyway is muted at once and skipped after 1.5 s; if the page holds on to it after one reload, it stays muted until it ends. The volume comes back after the ad, and after a restart if the app was closed during one.
- **Ad blocking stays discreet**: only ad media and third-party ad servers are refused; Spotify's own ad endpoints (ad config, ad events, sponsored recommendations) are left alone, so the client looks like any other. Sponsored content is dropped from the screens instead.
- **Transport commands** (play, pause, next, previous, skip to) run one at a time and only the newest counts; a play is checked against the page's state and sent once more when it did not take (the old song kept playing).
- **Fades**: every skip or play lowers the sound over ~0.1 s and brings it back once the next song plays (always within 4 s): the "pop" heard in the phone's speaker is gone. Ads and user volume changes are taken into account.
- **Smart shuffle in the player**: the shuffle button goes off → shuffle → smart shuffle on playlists and Liked Songs; the smart mix is shared with the playlist page.
- **Remote control (#40)**: while another device plays, the state is read every 3 s.
- **Sleep timer "end of track"**: based on the media uri and the clock, so episodes, local files and repeat one stop too.
- **Artist pages** load every page of the discography (up to 300 releases).
- **Ads, continued**: an ad is recognised by its metadata too (`is_advertisement`, `ad_id`, advertiser), not only by a `spotify:ad:` uri. Home promotion shelves and sponsored cards are dropped. Sponsored recommendations and the page's ad/audience trackers (Google Tag Manager, Google Analytics, Scorecard Research, Fastly Insights) are blocked.
- **Artist discography**: the Releases page filtered on lowercase kinds while Spotify sends "ALBUM", "SINGLE"…, so it stayed empty; it also opens by artist id now, and kinds are shown translated.
- **Long names**: every line-limited text ends with "…"; home cards keep two subtitle lines so a row never jumps; shortcut tiles, category and discover grids get a fixed text height; playlist, album, artist and profile titles shrink to fit two lines (`FitText`) before being cut.
- **Pause**: a pause you ask for no longer expires; the stall watchdog never restarts playback after it, even when paused in the last seconds of a song.
- **Download index**: an index that cannot be read fully is never used to delete files. The last good copy (`index.json.bak`) is used, the damaged one is kept aside (`index.corrupt-*.json`), and no file is deleted.
- **Spotify writes**: a write (add to playlist, follow…) is never sent twice when its answer is lost; a GraphQL error on a write is a failure, not a success. Likes, library saves and playlist visibility go one at a time and come back to the real state when Spotify refuses, with a message.
- **Search**: query and chip kept when coming back from a result; loading, no results and failure are shown apart, with Try again. Artist and profile pages show an error with Try again instead of an empty page.
- **Player**: share sends the episode link for episodes; play, pause, next, previous, shuffle, close and devices have names for TalkBack.
- **Jam**: "Join with a copied link"; a phone without the QR scanner gets a message instead of nothing.
- **Logout** stops local playback, the music video and the notification, pauses Spotify and drops the cached pages.
- **Data saver**: on mobile data with the saver on, the Spotify page is not loaded at launch.
- **Music video** pauses with its lookups when the app leaves the screen.
- The account token is only ever sent to Spotify hosts.
- CI also runs the API tests.

## 2.0.0-beta.5

- **Downloads are encrypted**: a downloaded song only plays in Myspoti, on the phone that downloaded it. Songs downloaded before are encrypted in the background.
- **The queue of downloaded songs is the real one**: it shows what plays next; moving, removing or skipping songs works offline.
- **Downloaded songs stay on the phone**: they are no longer reported to Spotify, so playing one never makes the Web Player start the album over it. They count in Myspoti's own Recap.
- **Downloads say when the SpotiFLAC verification is missing**, with a banner that opens it.
- **Updates install in the app**, with a progress bar; the update picks this release's own APK and keeps Android's install confirmation.
- **Jam QR scan no longer crashes** (the scanner's library lost its constructors in release builds).
- **Shorter debug logs**: the copied logs keep Myspoti's own lines and crashes only; release (non-beta) builds drop debug logs entirely.
- **Security**: the artwork provider only serves Spotify CDN images over https (another app could read Myspoti's private files through it); only Android Auto, Assistant, System UI, car and watch apps may browse the media library; Spotify links must be real Spotify hosts; logout also clears the listen history, the download sessions and the Web Player storage.
- **Reliability**: Android Auto's Liked Songs no longer come back empty; library and playlist pages no longer repeat rows; a bridge timeout is reported as an error, not a silent cancel; the newest player state is kept when the app is busy; the SpotiFLAC verification survives a rotation; the Web Player bundles are not downloaded again on every start; disk work left the main thread.
- **Shuffle-play without a list** queues the shuffled songs (up to 50).
- **Music videos** are only looked up when the video is on or the player is showing.
- **Links** open once (not again on rotation) and www./play.spotify.com links work.
- **Playlist menu** (⋯ on a playlist page): edit details (name, description, cover picked from the phone, square JPEG under 256 KB, or removed), who can see it (public, private, collaborative: the Web Player's base permission), delete with confirmation; on someone else's playlist, their profile.
- **Profiles**: a Profiles chip in search, a profile page (picture, followers, following, public playlists, Follow / Following), the owner line of a playlist opens it, "View profile" in the account menus.
- **Song menu**: "Don't play this artist" (Spotify's artist ban) and, for episodes, add to or remove from Your Episodes.
- **Search**: Spotify's suggestions while typing; clearing recent searches clears Spotify's list too.
- **Albums**: "More like this" under the tracks.
- **Your Library**: a ⋯ on each row to pin or unpin, move a playlist or folder into a folder (or back to the top), delete a folder; sorts by Recents and Recently added; items pinned on Spotify stay on top.
- **Playlist sort**: ascending or descending (pick the chosen sort again), remembered per playlist across restarts (#16).
- **AMOLED**: song rows, the library and several pages used fixed greys; they follow the theme.
- **Bottom of lists**: the last rows scroll above the mini player, the tabs and the system navigation bar.
- **Full-screen music video**: it was never drawn; it now fills the screen in landscape with its controls.
- **Spotify account page**: kept out from under the status and navigation bars.
- **"Saved in"**: a message after each add or removal, and a Done button.
- **Skipping fast**: taps on next in a row become one jump in the queue instead of a burst of commands.
- **Light-mode phones**: the app stays dark, so an empty or failed page (like Podcasts, #36) no longer shows white.
- **API**: playlist access (base permission) and cover upload, checked live; "Hide song" is not available in the Web Player (it reports it cannot ban), so it is not offered.

## 2.0.0

A new engine, written from scratch, and a cleaner app on top of it.

- **Lossless downloads**: the offline downloads are now FLAC. Myspoti finds the same recording on a lossless service (Tidal, Qobuz or Amazon) by the track's ISRC and takes it as it is served, never re-encoded — 16-bit CD quality, **24-bit hi-res when it exists**, or AAC re-encoded on the phone to keep them small (in Settings). Tidal (and Qobuz) come through the same signed servers as SpotiFLAC Mobile's extensions, three songs at a time and three segments per song; the smallest quality takes Tidal's own AAC as it is served. A one-off verification in Settings turns them on. A track with no lossless recording anywhere is marked unavailable.
- **Sound quality, chosen at the start**: a first step after sign-in picks the streaming quality (Automatic, Low 24 kbit/s, Normal 96, High 160) and the download quality (off, smallest AAC 96 kbit/s, FLAC CD, FLAC hi-res). Both stay in the new **Settings** page.
- **Connect shows your headphones**: with Bluetooth or wired headphones, their name replaces "This phone", a "This phone" row sends the sound back to the speaker, and a Bluetooth button opens the pairing settings. The quality badge opens a small page with what plays now (codec and bitrate) and the setting.
- **Downloads ask their quality** if you want ("Ask each time"): once for a whole playlist, kept for its new songs.
- **Liked Songs update at once** after a like or an unlike, without reloading the whole list.
- **No more "bop" between two songs** on the phone's speaker: the sound output stays awake across the change.
- **Downloads are encrypted**: a downloaded song only plays in Myspoti, on the phone that downloaded it. Songs downloaded before are encrypted in the background.
- **The queue of downloaded songs is the real one**: it shows what plays next, and moving, removing or skipping songs works offline.
- **Downloaded songs stay on the phone**: they are no longer reported to Spotify, so playing one never makes the Web Player start the album over it. They count in Myspoti's own Recap.
- **Downloads say when the SpotiFLAC verification is missing**, with a banner that opens it.
- **Shorter debug logs**: the copied logs keep Myspoti's own lines and crashes only.
- **Updates install in the app**: "Update" downloads the new version with a progress bar and hands it to Android's installer; no trip to GitHub. The first time, Android asks to allow installs from Myspoti.
- **Haptics work again**: buttons, likes and the debug icon vibrate.
- **Solid bottom bar** as an option in Settings, Display.
- **Settings page**: sound quality, downloads, data and battery, display and language, in one place (profile menu or Library's account menu).
- **Italian**: Myspoti's own texts are translated too.
- **Headphones unplugged or Bluetooth gone**: the music pauses instead of moving to the phone's speaker.
- **Stuck ads**: an ad that blocks the player is skipped twice, then the player reloads, so the music does not stop for good.

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
