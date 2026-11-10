# What's different from the original Myspoti

Everything below is compared to the original `myspoti-main` archive (version 2.4.0):
**45 files added, 82 files changed, 0 removed.**

Short version:

- Runs on **Android 6.0** (API 23) instead of Android 10 (API 29)
- **QR code login**, **phone handoff login**, and **`sp_dc` cookie paste login** for TV and old devices
- New secure-connection layer, modern-JavaScript shims and WebView SSL checks so Spotify's own login page loads on old Android
- Login now works while a VPN is on, and failures say *why* instead of hanging
- Launcher icons for Android 6 and 7
- Dozens of Android 8–14 features replaced with versions that work on Android 6
- Tests for all of the above

---

## 1. Android 6 build, dependencies, manifest and resources

### Gradle and version catalog

- `base/gradle/libs.versions.toml` — `material3` moved to `1.5.0-alpha25` (newest `1.5.0` line that still supports Android 6) and `onnxruntime-android` moved to `1.20.0` (the newer one needs C library symbols that do not exist on Android 6). Added the `desugar_jdk_libs_nio:2.1.5` entry.
- `base/app/build.gradle.kts`, `base/host-android/build.gradle.kts`, `base/automix/build.gradle.kts` — `minSdk` set to `23`, core library desugaring turned on (so newer Java APIs work on Android 6), Play Services versions pinned so the release shrinker does not break, and `collectApks` configured.
- `base/app/proguard-rules.pro` — keep rule for `TlsCompat$CompositeTrustManager.checkServerTrusted(..., String, String)`.

### Manifest, widget and launcher icons

- `base/app/src/main/AndroidManifest.xml` — permissions and flags needed on Android 6, the `myspoti://pair` deep link, and TV (Leanback) support.
- `base/app/src/main/res/xml/widget_now_playing_info.xml` — widget metadata that Android 6 accepts.
- **30 launcher icons added** in `base/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round,_blue,_orange,_pink,_purple}.png` — Android 6 and 7 cannot read the newer adaptive icon format.

### Docs

- `README.md`, `THIRD_PARTY_NOTICES.md` — Android 6 support, QR/phone/cookie login, the older audio-model runtime version, and the Mozilla certificate bundle licence (`MPL-2.0`).

---

## 2. Old-Android connections, browser compatibility, VPN and cookie check

### Added

- `base/host-android/src/main/res/raw/ca_roots.pem` — bundled Mozilla root certificates (ISRG Root X1/X2, GTS Root R1–R4, Certainly Root R1/E1, Starfield G2, DigiCert Global Root G2, GlobalSign Root CA - R3).
- `base/host-android/src/main/kotlin/app/myspoti/host/android/TlsCompat.kt` — modern secure connections on Android 6–9 for both the app's HTTP client and the built-in browser, plus a check for browser certificate errors (full connection test on a background thread, host and certificate match, 30-minute cache).
- `base/host-android/src/main/kotlin/app/myspoti/host/android/SpotifyCookieVerifier.kt` — checks a pasted `sp_dc`/`sp_key`/`sp_t` cookie directly against `https://open.spotify.com/` (redirects off, cookie-clearing check, `_authfailed`/`isAnonymous` handling), through the new connection layer and the active VPN.

### Changed

- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt` — certificate-error handling wired to `TlsCompat`, a JavaScript compatibility layer (`globalThis`, `Object.hasOwn`, `Object.fromEntries`, `Array`/`String.prototype.at`, `replaceAll`, `flat`, `flatMap`, `Promise.allSettled`, `structuredClone`, `crypto.randomUUID`), sequential cookie injection with `stopLoading()`, `https://spotify.com/` fallback, a cookie read-back check for Android 6 devices, a 50 second page timeout, page-commit and page-error tracking (including HTTP 400+), browser version detection, session-cookie export, and the Android 6 VPN network request (`removeCapability(NET_CAPABILITY_NOT_VPN)` + `TRANSPORT_VPN`).
- `base/api-spotify/src/main/resources/bridge/player-bridge.js` — fallback for `Symbol.prototype.description` on browsers older than Chrome 70.
- `base/host-android/src/main/kotlin/app/myspoti/host/android/` — `MyspotiRuntime.kt` (connection reset when the VPN changes, direct cookie check), `ControlRelay.kt`, `MediaProxy.kt`, `RelayNetwork.kt`, `Fingerprint.kt`, `PlaybackService.kt`, `Logbook.kt` (Android 6 guards and the new connection layer).
- `base/app/src/main/java/app/myspoti/MyspotiApplication.kt` — installs the connection layer at startup.

---

## 3. QR login, phone handoff, pasted cookie login, TV and VPN fixes

### Added

- `base/app/src/main/java/com/music/spotui/connect/QrLoginProtocol.kt` — the pairing address (`http://` and `myspoti://pair`), local-network address checks, cookie parsing (bare `sp_dc`, `Cookie:` headers, JSON, Netscape `cookies.txt`), a small HTTP client and server, the offline pairing page, and picking the right local address while a VPN is up.
- `base/app/src/main/java/com/music/spotui/connect/QrLoginServer.kt` — the local pairing server on the Wi-Fi/Ethernet address (never the VPN tunnel), a 65 second wait, and the phone-side sender.
- `base/app/src/main/java/com/music/spotui/ui/screens/QrLoginUi.kt` — the QR screen, TV buttons that respond to a remote's OK button, the paste-cookie dialog (fills a valid clipboard token by itself, moves focus to **Sign In**), the pair-device dialog, the manual pair dialog, and the code scanner with a manual fallback.

### Changed

- `base/app/src/main/java/app/myspoti/MainActivity.kt` — QR screen and pair dialog wiring, VPN connection reset before login, imported-session handling (direct cookie check plus a page-load poll with specific error messages), and Android 6 window/PiP guards.
- `base/app/src/main/java/com/music/spotui/ui/screens/WelcomeScreen.kt` — QR / Phone / Cookie login button.
- `base/app/src/main/java/com/music/spotui/ui/components/LoginHelp.kt`, `AccountsList.kt` — offer the QR/cookie route when the built-in login page is stuck.
- `base/app/src/main/res/values/strings.xml` — texts for QR/phone/cookie login, the paste dialog, the VPN hint and the specific login errors.

---

## 4. Android 6 compatibility across the app

### Network checks on Android 6

`removeCapability(NET_CAPABILITY_NOT_VPN)` and VPN transport checks in:

- `base/app/src/main/java/com/music/spotui/data/DataSaver.kt`
- `base/app/src/main/java/com/music/spotui/data/LibraryOutbox.kt`
- `base/app/src/main/java/com/music/spotui/extensions/Extensions.kt`

### Platform features replaced with Android 6 equivalents

Notifications, vibration and haptics, storage paths, audio decoding and conversion, URL/text encoding, shaders and UI effects, file sharing, text romanisation, and more:

- `base/app/src/main/java/com/music/spotui/automix/`: `AutoMix.kt`, `DeckEq.kt`, `TrackDecoder.kt`
- `base/app/src/main/java/com/music/spotui/cast/CastServer.kt`
- `base/app/src/main/java/com/music/spotui/connect/`: `ConnectCode.kt`, `DesktopConsent.kt`
- `base/app/src/main/java/com/music/spotui/data/`: `NewReleaseNotifier.kt`, `api/AppUpdater.kt`, `api/GeniusMeaning.kt`, `api/InviteLinks.kt`, `api/SpotifySync.kt`, `api/UpdateChecker.kt`, `local/LocalMusic.kt`, `offline/AacTranscoder.kt`, `offline/PodcastDownloader.kt`, `preferences/SettingsPref.kt`
- `base/app/src/main/java/com/music/spotui/di/`: `Haptics.kt`, `SpeakerKeepAlive.kt`
- `base/app/src/main/java/com/music/spotui/extensions/`: `ExtensionAttestation.kt`, `ExtensionAudio.kt`, `ExtensionAuth.kt`
- `base/app/src/main/java/com/music/spotui/radio/RadioBrowserApi.kt`
- `base/app/src/main/java/com/music/spotui/ui/components/`: `AudioRoute.kt`, `PlayerSheets.kt`, `ShareCard.kt`, `SongPip.kt`, `SpotifyCodeSheet.kt`, `StatsForNerdsSheet.kt`
- `base/app/src/main/java/com/music/spotui/ui/immersive/`: `pages/ImmersiveSettingsScreen.kt`, `screens/ImmersiveArtistScreen.kt`, `shell/backdrop/RuntimeShader.kt`, `shell/backdrop/RuntimeShaderCache.kt`
- `base/app/src/main/java/com/music/spotui/ui/screens/`: `AccountBrowser.kt`, `ArtistScreen.kt`, `ContextScreen.kt`, `ExtensionPage.kt`, `HomeScreen.kt`, `SettingsScreen.kt`
- `base/app/src/main/java/com/music/spotui/ui/theme/Theme.kt`
- `base/app/src/main/java/com/music/spotui/video/`: `AudioAlign.kt`, `SingleFlightCache.kt`, `YouTubeSource.kt`
- `base/app/src/main/java/com/music/spotui/widget/NowPlayingWidget.kt`
- `base/extensions/src/main/kotlin/app/myspoti/extensions/`: `Romanize.kt`, `SafeNet.kt`

---

## 5. Tests

### Added

- `base/host-android/src/test/kotlin/app/myspoti/host/android/SpotifyCookieVerifierTest.kt`
- `base/host-android/src/test/kotlin/app/myspoti/host/android/TlsCompatTest.kt`
- `base/app/src/test/java/com/music/spotui/connect/QrLoginProtocolTest.kt`
- `base/app/src/test/java/com/music/spotui/data/local/LocalMusicTest.kt`
- `base/extensions/src/test/kotlin/app/myspoti/extensions/RomanizeApi23Test.kt`

### Updated

- `base/host-android/src/test/kotlin/app/myspoti/host/android/FingerprintTest.kt`

---

## 6. Fixes after the first build

### "Checking your session" ended in "Spotify did not accept that cookie" even with a good cookie

Two things caused it, and both are fixed:

- **The check looked like an old browser.** The app sends its own browser name, which on an
  Android 6 TV follows the device's WebView (Chrome 55–106). Spotify answers old browsers with a
  reduced page that carries no session information, and "no information" was read as "cookie
  rejected". `SpotifyCookieVerifier` now always presents a current desktop Chrome for this one
  request (a newer device keeps its own), so the answer is about the cookie again.
- **Silence was treated as "no".** Since March 2025 Spotify's `get_access_token` endpoint answers
  "anonymous" even for a valid `sp_dc` (the web player also sends a one-time code now), and the
  page markup changes. The check now asks two places, counts only a clear "yes"/"no", and anything
  else is reported as *unverified* — never as a wrong cookie. Only a cleared cookie, an
  `_authfailed` redirect or a 401 stops the import.

The player page is the authority now: on a slow Android 6 TV it gets 90 seconds instead of 60, it
is reloaded once if it comes up signed out, and nothing is called "anonymous" until the page has
had 30 seconds to boot. The new message `qr_login_unverified` says what actually happened
("could not confirm the session — the cookie is not wrong, the page did not finish loading")
instead of blaming the cookie.

### Cookies written to the WebView

- `WebPlayerHost.applySessionCookies` also writes to `https://open.spotify.com` (no trailing
  slash), a second spelling that some Android 6 ROMs normalise differently, and logs which cookie
  names the jar actually kept — that separates "Spotify said no" from "the page never saw the
  cookie".

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/SpotifyCookieVerifier.kt`
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/app/src/main/java/app/myspoti/MainActivity.kt`
- `base/app/src/main/res/values/strings.xml` (`qr_login_unverified`)
- `base/host-android/src/test/kotlin/app/myspoti/host/android/SpotifyCookieVerifierTest.kt` (4 new tests)

---

## 7. Fix: the cookie import never let the player boot

Reported after the first fix: *"when typing the token it says it didn't load after a while, but
while it is checking I click 'Log in on this device' and it signs in at once."* That was the bug,
and it had nothing to do with the cookie.

The app keeps the Web Player page **1x1 and invisible** behind its own screens — enough to play
music all day. The cookie import loaded the player into that same 1x1 invisible page, and on a slow
phone (and on an Android 6 TV) the player never finishes booting there: no ready signal, no
session, so the import gave up and reported a failure. The "Log in on this device" button calls
`attach(root, visible = true)` first — a full-size, visible page — and that is why signing in
worked the moment the user pressed it.

What the import does now:

- **The page is attached full size, in front of the WebView window, behind the app's own screen.**
  `showPlayerBehindApp(true)` attaches it and moves the Compose UI back to the front, so the user
  keeps looking at the same QR screen and can still use it, while the player gets the window it
  needs to boot. It is put back to the normal 1x1 hidden state when the import ends (success,
  failure or cancellation) — unless the user opened Spotify's sign-in page meanwhile.
- **A second chance at 25 s:** if the player still has not booted, the import opens the sign-in
  page the way the login button does (`openLogin()`). With a good cookie Spotify bounces straight
  back to the player; that is the path the user saw working by hand.
- **A WebView older than Chrome 85 fails at once** with the "Android System WebView is too old"
  message, instead of waiting 90 s for a page that cannot run.
- The rest of the window is unchanged: 90 s, honest messages, and a rejection only when Spotify
  really asks for a sign-in (`_authfailed` or its login page staying for 15 s after the cookies).

### Files touched

- `base/app/src/main/java/app/myspoti/MainActivity.kt` (`showPlayerBehindApp`, `importSession`)

---

## 8. Fix: "this browser is not supported" from Spotify itself

After §7 the TV got Spotify's own page saying the browser is not supported. That page is served
from the user agent: the app announced **the WebView's own version** — Chrome 87–106 on Android 6
and 7, which Spotify no longer accepts — while the player page itself is already polyfilled for
those engines. So the TV was refused by name, not by what its engine can do.

- `Fingerprint.MIN_CLAIMED_MAJOR` (141): the claimed Chrome is now brought up to a current version
  whenever the WebView is older than that. User agent, `sec-ch-ua` client hints and
  `navigator.userAgentData` all come from the same value, so they still agree with each other. A
  current WebView keeps its own numbers, and so does the drawn lag on them.
- **The unsupported page is recognised** (`WebPlayerHost.unsupportedBrowserPage`): its text is read
  once per player page, and the QR / cookie import now stops immediately with
  `qr_login_unsupported_browser` ("Spotify's own page says this browser is not supported (WebView
  X)") instead of waiting 90 seconds and blaming the cookie.
- **More of the player page's API is polyfilled** for old engines: `Array.prototype.findLast` /
  `findLastIndex`, `String.prototype.matchAll`, `trimStart` / `trimEnd`, `Promise.any`,
  `queueMicrotask`, `Element.prototype.replaceChildren`.

If the engine itself is too old to parse Spotify's bundle, the app still reports that as the
WebView being too old (the `SyntaxError` path) — the difference is that it is now the engine
saying so, not Spotify's name check.

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/Fingerprint.kt`
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/app/src/main/java/app/myspoti/MainActivity.kt`
- `base/app/src/main/res/values/strings.xml` (`qr_login_unsupported_browser`)
- `base/host-android/src/test/kotlin/app/myspoti/host/android/FingerprintTest.kt`

---

## 9. What the working TV wrapper does, and what we took from it

Asked to compare with **SpotiDuck** (23fpsz/SpotiDuck-Releases, a web wrapper that works on Android
TV). Its published APK carries the whole approach in its assets:

- a **fixed desktop Chrome user agent** (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/134.0.0.0 …`)
  set on `WebSettings`, never the device's WebView version;
- `assets/google_spoof.js`, installed with `WebViewCompat.addDocumentStartJavaScript`, which redefines
  `navigator.userAgent`, `appVersion`, `vendor`, `platform`, `webdriver`, `maxTouchPoints`,
  `navigator.userAgentData` (including `getHighEntropyValues`), adds a `window.chrome` stub and pins the
  screen size — i.e. the page is told a desktop browser **before any of Spotify's scripts run**;
- `assets/desktop_spoof.js`: stability patches (unregister and block Spotify's service worker, throttle
  the player's 250 ms polling to 500 ms, park background videos).

§8 already did the first point (a current Chrome instead of the WebView's own version). This adds the
second, which §8 was missing:

- **`DesktopSpoof`** (`base/host-android/…/DesktopSpoof.kt`) builds a document-start script from this
  install's [Fingerprint]: `navigator.userAgent`, `appVersion`, `vendor`, `platform`
  (`Linux x86_64`, where an Android WebView says `Linux armv8l`), `maxTouchPoints` 0, `webdriver`
  false, `navigator.userAgentData` with the same brands and full version as the native client hints,
  `getHighEntropyValues`, and a `window.chrome` stub.
- It is registered with `WebViewCompat.addDocumentStartJavaScript` (before `COMPAT_POLYFILL_JS`, both
  before the page's own code) and is also injected on page start/finish for WebViews without the
  document-start feature.
- The values come from the same [Fingerprint] as the user agent and `Sec-CH-UA` metadata, so the page,
  its hints and its script-visible `navigator` describe **one** browser.

Not adopted at the time: the service-worker blocker and the interval throttle. They change playback
and cache behaviour, and could not be judged without a device — a TV then reported exactly what they
are for (lag, songs starting late and being skipped); they are §11.

If a TV still gets Spotify's unsupported page after this, the next lever is the identity itself —
SpotiDuck presents a **Windows** desktop Chrome where this install presents Linux.

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/DesktopSpoof.kt` (new)
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/host-android/src/test/kotlin/app/myspoti/host/android/DesktopSpoofTest.kt` (new)

---

## 10. The browser spoof was never running: a syntax error in its own script

Reported from the TV, on the QR sign-in screen:

> Spotify accepted your cookie, but Android System WebView (Chrome 107) on this device is too old
> for Spotify Web Player (needs Chrome 85+). Update Android System WebView to continue.

Two things are wrong with that sentence, and one of them is the bug.

**The spoof never ran.** §9 built its brand lists with `joinToString(",")` and dropped them into
`var B=__BRANDS__,FB=__FULL_BRANDS__;`. Three brands make

    var B={"brand":"Not?A_Brand","version":"8"},{"brand":"Chromium","version":"141"},…,FB={…};

which is a `SyntaxError: Invalid destructuring assignment target` — checked with `node --check` on
the exact string the Kotlin built. A script that does not parse applies nothing, so the page kept the
WebView's own `navigator` (an Android user agent, `Linux armv8l`, real touch points): precisely the
shape Spotify refuses. The spoof was installed, ran first, and did nothing — silently, because a script that cannot
parse never reaches the `try` inside it.

**A Chrome 107 WebView is not too old.** Chrome 85 is the player's floor and 107 is above it. That
message came from the console hook: a `SyntaxError` logged while the player page is open (ours, in
this case) was recorded as a failed page script, and that branch printed a "too old (needs Chrome
85+)" string — self-contradictory on its face, and it hid the cause behind a plausible one.

### What changed

- **`DesktopSpoof`**: the brand lists are JS arrays (`var B=[…],FB=[…]`). The generated script is
  checked with `node --check` for engine majors 87, 141 and 155, and run in node against a fake
  Android `navigator`: user agent, `platform`, `maxTouchPoints`, `vendor`, brands and
  `window.chrome` all come out as a desktop browser says them.
- **`DesktopSpoof.PROBE`**: reads the page's `navigator` back after each load ("applied; ua desktop;
  platform Linux x86_64; touch 0; uaData yes", or "NOT APPLIED") and logs it. A spoof that silently
  does nothing is now visible in the log as itself, instead of being guessed at from a symptom.
- **`WebPlayerHost.PLAYER_MIN_MAJOR`** (85): the player's floor in one place, used by the QR sign-in
  instead of two bare `85` comparisons.
- **The message**: "too old" is said only when the WebView really is below 85. A page script that
  fails on a new-enough WebView now gets its own message
  (`qr_login_page_script_failed`), naming the version and the error and saying the cookie is not the
  problem.
- **`DesktopSpoofTest`**: the brand lists must be arrays with one entry per brand, and the probe must
  look for the marker the script sets.

If a TV still fails now that the spoof actually runs, the next lever is the identity itself: SpotiDuck
presents a **Windows** desktop Chrome where this install presents Linux.

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/DesktopSpoof.kt`
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/host-android/src/test/kotlin/app/myspoti/host/android/DesktopSpoofTest.kt`
- `base/app/src/main/java/app/myspoti/MainActivity.kt`
- `base/app/src/main/res/values/strings.xml` (`qr_login_webview_old`, new `qr_login_page_script_failed`)

---

## 11. What the page may cost the device: the TV is not slow, the page is heavy

Signed in on an Android TV at last, and the report is speed rather than sign-in: the app is laggy, a
song sometimes starts and sometimes does not, and most of the time it is skipped. The VPN is fast, so
it is not the network. It is the page: Spotify's web player is built for a laptop, with the tab in
front, on a CPU a TV does not have.

SpotiDuck — the wrapper that works on these TVs — has a `desktop_spoof.js` that is, besides the
`navigator` spoof taken in §9, a **performance patch bundle**, and its comments say what each part is
for. The three patches are taken one to one, and a fourth is this app's own:

- **Service worker off.** Spotify's worker intercepts every request to keep an offline cache map. On
  an old WebView that bookkeeping is what freezes the page and stalls the player. It is refused
  (`register` answers a rejected promise) and one already installed is unregistered; the page then
  loads over the WebView's ordinary HTTP cache.
- **Quarter-second polling halved.** The page re-reads itself every 250 ms to keep its own UI in step.
  Nobody sees that UI while the app draws its own screen; 500 ms is the same behaviour at half the
  wake-ups.
- **Analytics made a no-op.** `window.ga` answers its callbacks without loading anything.
- **Frames nobody sees** (this app's own): the player page is attached as a 1×1 invisible view behind
  the app's screen (so playback runs without drawing), and its `requestAnimationFrame` loop kept
  running at 60 frames a second for a page nobody can see. It is throttled to ten frames a second
  while the app's screen covers the page, and put back at full rate the moment the page is shown —
  the sign-in page, the log-in-on-this-device step, the debug window that shows the page itself.

`Stability.SCRIPT` runs at document start, before the page's own code, and again on page start and
finish for WebViews without that feature; every patch is guarded on its own, so one that cannot be
applied leaves the rest — and the page — working.

Verified against the script itself with `node`: the interval clamp turns 250 ms into 500 ms and leaves
500 and 1000 alone; `register` rejects and an installed worker is unregistered; frames run at full
rate when shown, five queued frames arrive in one batch while quiet, `cancelAnimationFrame` keeps a
queued callback from running, and a second run of the script does nothing.

What this does not do: it does not make a slow TV fast, and it does not touch the engine's own stall
repairs (`SpotifyPlayer`), which only move a song on when it is past its end. If songs are still
skipped after this, the app's log says why (`player stalled (... attempt N; media element: ...`), and
that line is the next thing to look at.

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/Stability.kt` (new)
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/host-android/src/test/kotlin/app/myspoti/host/android/StabilityTest.kt` (new)
- `CHANGES.md` §9 (the note that these patches were left out)

---

## 12. "It keeps skipping, and nothing tells me why" — the app was telling, invisibly

Reported from the TV after signing in: songs keep skipping, and no cover loads on Home. The second
is a one-line bug; the first turned out to be partly a bug of the same kind — the app knew, and could
not be heard.

### The app's answers were `Toast`s, and a TV draws no toast

Every explanation the engine has for this exact symptom was sent as `Toast.makeText(...)`:
"songs skipped by Spotify", "licences refused", "this device has no Widevine", "the play was
refused", "Spotify is limiting playback". On a television those are drawn nowhere — so the app
stopped playback, or named the reason, and the user saw songs skip with no reason at all.

- `TvNotice` + `TvNoticeHost`: those messages are drawn over the TV's own screens (a panel at the
  bottom, not focusable, gone after 14 s). `SongPlayer` now sends them there on a TV and keeps the
  toast for phones.
- The message set is unchanged and translated already (`songs_skipped_by_spotify`,
  `licences_refused_*`, `drm_missing`, `play_refused*`, `spotify_limited`, `song_unavailable*`).

### The guard against a skipping page could not see a slow one

`TrackChanges.Chain` stops playback when the page moves on by itself three times in a row after "a
few seconds" — three songs inside 30 s, each left within 10 s. A slow device fails *slowly*: on the TV
each song was given ten to twenty seconds to start before the page went on, so the bar was never met
and the whole queue went by unstopped. The window is now 90 s and a song may have played 20 s, with a
test for the case (three songs, each 15-18 s).

### The app no longer guesses a song's end

"Past the end" is extrapolated from the last state the page sent (`position + elapsed × speed`). A
page busy for seconds hands over a state that is already old, and the extrapolation runs past the
song's duration — the repair then moves to the next song, i.e. the app skips it. Before a song is
left on that judgement, the page is asked where it is (`player.state`): a page that still has the
song in its middle stops the repair and the state is taken fresh. Twice at most, so a page that
cannot answer is never left playing a song that is over.

### No cover on Home

`TvCover` handed Glide whatever string it was given. The profile and library endpoints answer
`spotify:image:<id>` (and `spotify:mosaic:<ids>`), which Glide cannot fetch — the phone screens pass
those through `spotifyImageUrl()` first, the TV's did not, so every such card kept its grey box. It
goes through the same helper now.

`TlsCompat.install()` also runs on every Android version now, not only 6-9: a television ships a
system trust store and stops updating it, and a native client that trusts only that store (the cover
loader) fails where the page, with its own roots, works. The system store is still consulted first.

### The log can be reached on a TV

Every report from a television so far had to be guessed at, because the app's own log was reachable
only by holding a 30 dp bug icon — impossible with a remote — and then copying to a clipboard. On a
TV the icon is a focusable button and the log opens full screen, so a page of it can be read across
a room and photographed.

Scrolling it from a remote needs a second thought. The first attempt wrapped the whole log in one
focusable text that scrolled itself on up and down. It did not compile — `scrollBy` is an extension
that has to be imported, as the app's list screens do — and, once it did, it still would not have
moved: a remote's keys go to whatever has the focus, and the dialog gives the focus to its buttons;
a request made too early is dropped without a word, so the text never received a single key. Each
line is a focusable row now, newest first and bottom-up: the dialog opens with the focus on the newest
line at the bottom, up walks back through the log, and the list follows the focus exactly as the home
shelves follow it. No key is taken from anywhere, so the buttons stay reachable.

### Files touched

- `base/app/src/main/java/com/music/spotui/ui/tv/TvNotice.kt` (new)
- `base/app/src/main/java/com/music/spotui/ui/tv/TvApp.kt`, `TvParts.kt`
- `base/app/src/main/java/com/music/spotui/di/SongPlayer.kt`
- `base/app/src/main/java/com/music/spotui/ui/components/BetaLogButton.kt`
- `base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt`
- `base/api-spotify/src/main/kotlin/app/myspoti/spotify/TrackChanges.kt` + `TrackChangesTest.kt`
- `base/host-android/src/main/kotlin/app/myspoti/host/android/TlsCompat.kt`

---

## 13. The playhead a TV could not keep up with: skipped songs, a stop after play, "previous" at 0:00

Reported on the TV, after 12: songs skip by themselves; pressing play after a stop plays for a moment
and stops again; pressing "previous" sends the bar to 0:00 and then to about a minute. Three symptoms,
one cause, and a fourth find while looking.

### The cause: a position guessed from a snapshot seconds old

Everything the app knows about the song's place comes from the page's state:

```
positionAsOfTimestamp   the position
timestamp               when it was true
```

and every reader runs it forward by its age: `position + (now − timestamp) × speed`. That is safe
only while the snapshot is a moment old. On a TV the page's main thread is busy enough — the same
freezes measured earlier for the log (a 38 ms step taking 7.8 s) — that the snapshot handed over is
often seconds old. Three things then go wrong at once:

- the bar (and the lyric clock) jumps ahead by the whole age, then jumps back;
- a song is judged "past the end" long before its end, and the end repair plays the next song — the
  skipping the user reports;
- a song that is restarted, or seeked back, is placed at the position the *old* snapshot predicted,
  and a page that misses that spot goes silent — "it plays a little and stops".

### The playhead is read from the page's own element now

`player-bridge.js` reads the page's own `HTMLMediaElement` (`currentTime`) when it maps a state, and
compares it with the snapshot it is about to send:

- the snapshot is fresh (under 1.5 s) **and** agrees with the element (within 2.5 s): sent as it is;
- it is stale, disagrees, or the page was just seeked/restarted: re-stamped with the element's own
  position and "now", so every host extrapolates from the truth;
- paused: the element's clock is the position, and is used when the snapshot is not fresh;
- no element reachable (it is not always in the document): the snapshot, as before.

The element is found in the DOM when it is there, else once by walking the page's player objects
(`PlayerSDK.harmony`, its streamer, a bounded `WeakSet` walk), remembered for 30 s and re-found every
4 s when it is not. Each state mapping is a dozen DOM reads, no round trip.

State pushes are also collapsed: two within 200 ms become the last one, sent a moment later. A burst
of updates (a new song, its licence, its first frame) no longer costs the host a message and a redraw
each while its main thread is already the bottleneck.

### The end of a song is no longer judged on an old guess

- `PAST_END_SLACK_MS` (15 s): a clock has to run this far past a song's duration before the song is
  taken for over, instead of 1.5 s. The end check that asks the page is still there, and still the
  first word; this is the window left for a page that cannot answer.
- The end check's own bar now includes the age of both states (`reasonAge`): a fresh answer "30 s
  left" from a page whose previous state was 30 s old is no longer rejected for being inside the old
  4 s window (that rejection was itself a skip).
- `resumeAfterReload` restores at `positionAt(now)`, not at `positionMs` — the page's snapshot time,
  seconds back.

### A silent song is resumed, not seeked to a guess

The stall repair for a song heard but standing still used to seek it to `positionAt(now)` — the
snapshot's extrapolation, a spot the song may never have reached. It now pauses and resumes on the
song's own position (`resumeWhereItIs`), and only falls back to a seek when the resume is not taken
(the state has not moved past the one the repair already had, and the element still stands) within
6 s. That is the "play after a stop" case: the page comes back, the resume is a pause and a play, the
sound goes on where it was.

### "Previous" and the bar: a seek's old position is not the song's

`SongPlayer` holds the clock while a seek is on its way. Two holes made the bar jump back:

- the hold ended before the page reported the seek, and the state it then handed over was still on
  the old position — taken as the song moving back. A new guard (`SeekRules.stale`, `SEEK_SLIDE_MS`)
  refuses a reported position on the spot the seek was sent from for twice the hold, while any
  report at the seek's target (or the song heard on) ends it;
- a `reconcile` read the state straight from the page and cleared the holds unconditionally — a page
  that had not taken the seek yet answers with the old position, so the read ended the guard and then
  put that same old position on screen. A read now settles a seek only when it is not on the old
  spot.

Seeks are also given longer on a TV (`SEEK_SEND_MS` 5 → 12 s, `SEEK_CHECK_MS` 3 → 8 s): a page busy
enough to need the wait was also too busy to report the seek in 3 s, so every seek was sent twice and
the clock was handed back before the first one landed.

On the TV's own bar, the newest place asked for stays latched until the playhead is within 3 s of it
(or playback pauses), so a seek that takes a second no longer falls back to where the song was and
climbs forward again.

### The TV's music no longer stops for a link change

`NoisyAudio` pauses on `ACTION_AUDIO_BECOMING_NOISY` — the headphone-unplugged broadcast. A TV's
HDMI output answers the same broadcast on a frame rate or HDR switch, a receiver re-negotiating, or a
soundbar going away, and the music then stopped by itself. `AudioRouteRules` says when the broadcast
means what it says: headphones leaving always pauses; on a TV an HDMI or line output going away is a
link change and playback goes on (with the change written to the log); a TV whose sound is still on a
link output is left alone too. A phone is unchanged in every case.

The TV also holds the screen on now (`FLAG_KEEP_SCREEN_ON`): a TV left on the player falls asleep
after a while, and a page Chromium has frozen hands over old states and then stops making sound.

### Tests

- `api-spotify`: `PlayheadAgeTest` (the widened window, the age a state explains, the pause-at-the-end
  rule), `EndCheckTest` (the end check's slack, the case that skipped songs), both plain JVM.
- `app`: `SeekRulesTest` (the stale-state guard, the seek's own tolerance, the place arithmetic),
  `AudioRouteRulesTest` (phone, TV, headset on a TV, sound that fell back).
- `bridge-tests/run_bridge_tests.py`: `test_playhead`, live against a signed-in page (the state a play
  pushes is fresh, and its position run on by its own age meets the element's clock).

### Files touched

- `base/api-spotify/src/main/resources/bridge/player-bridge.js`
- `base/api-spotify/src/main/kotlin/app/myspoti/spotify/SpotifyPlayer.kt` + `PlayheadAgeTest.kt`, `EndCheckTest.kt`
- `base/api-spotify/bridge-tests/run_bridge_tests.py`
- `base/app/src/main/java/com/music/spotui/di/SongPlayer.kt` + `SeekRules.kt` (new) + `SeekRulesTest.kt`
- `base/app/src/main/java/com/music/spotui/di/NoisyAudio.kt`, `AudioRouteRules.kt` (new) + `AudioRouteRulesTest.kt`
- `base/app/src/main/java/com/music/spotui/ui/tv/TvNowPlaying.kt`
- `base/app/src/main/java/app/myspoti/MainActivity.kt`

---

## License

Unchanged: GPL-3.0, same as the original project. See [LICENSE](LICENSE).
