# What's different from the original Myspoti

Everything below is compared to the original `myspoti-main` archive (version 2.4.0):
**41 files added, 73 files changed, 0 removed.**

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

Not adopted, on purpose: the service-worker blocker and the interval throttle. They change playback
and cache behaviour, and neither can be validated here without a device.

If a TV still gets Spotify's unsupported page after this, the next lever is the identity itself —
SpotiDuck presents a **Windows** desktop Chrome where this install presents Linux.

### Files touched

- `base/host-android/src/main/kotlin/app/myspoti/host/android/DesktopSpoof.kt` (new)
- `base/host-android/src/main/kotlin/app/myspoti/host/android/WebPlayerHost.kt`
- `base/host-android/src/test/kotlin/app/myspoti/host/android/DesktopSpoofTest.kt` (new)

---

## License

Unchanged: GPL-3.0, same as the original project. See [LICENSE](LICENSE).
