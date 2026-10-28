# Myspoti extensions: API for authors

An extension is a small JavaScript file plus a `manifest.json`. Myspoti runs it in a sandbox. What
it may do is a list of **actions** from a closed catalogue the app defines (section 4): provide
lyrics or song details, add Home sections, search results, menu entries or player buttons (as
native blocks the app draws itself, section 3), give a song's audio or download files, show its own
web page, sign in on a website, react to the song playing, and, if the user allows it, read or change the signed-in
Spotify account. Each action has a fixed icon, a short label, a level (normal or critical) and the
API it unlocks; the user sees them as icons, each with your reason, before installing.

This page documents what the build ships (API version 1). Nothing here is a promise about
anything else.

## 1. A minimal extension

`manifest.json`:

```json
{
  "id": "io.github.you.lyrics",
  "name": "My Lyrics",
  "version": "1.0.0",
  "author": "You",
  "description": "Lyrics from example.com.",
  "actions": [
    { "name": "lyrics.provide", "why": "Synced lyrics from example.com" },
    { "name": "network", "hosts": ["lyrics.example.com"], "why": "Asks example.com for the lyrics" }
  ]
}
```

`main.js`:

```js
myspoti.provide('lyrics', function (track) {
  var r = myspoti.http.get('https://lyrics.example.com/api?' +
    myspoti.query({ artist: track.artist, title: track.title }));
  if (!r.ok) return null;                       // null: "I have nothing", the app tries its other sources
  var lines = r.json().lines;                   // [{ ms: 1200, text: "..." }, ...]
  return { provider: 'example.com', lines: lines.map(function (l) { return { time: l.ms, text: l.text }; }) };
});
```

Package it (a zip with the manifest and the script at its root, nothing else but the optional
files below):

```
myspoti-ext pack ./my-lyrics        # writes io.github.you.lyrics-1.0.0.zip, prints its sha256
```

A package may hold `manifest.json`, the `main` script, `icon.png` (or the PNG named by the
manifest's `icon`), `LICENSE`, `LICENSE.txt`, `README.md` and nothing more (any other file refuses the package). At most 30 files, 2 MB for the
zip, 1 MB for the script, 256 KB for the icon. The zip is built the same way every time (fixed
order and dates), so anyone can rebuild it and compare the sha256.

How an extension reaches people: its own GitHub repository, found by the store through the topic
`myspoti-extension` (section 9), the official repository's optional list of verified extensions or a repository the user adds at their own risk. A repository is a folder of packages
and an `index.json` (`myspoti-ext index <folder> <name>` writes it). The index can also list
extensions every Myspoti must turn off (`blocked`: an id, optionally some package sha256, a reason).

An index entry is the manifest's fields (`id`, `name`, `version`, `versionCode`, `apiVersion`,
`author`, `description`, `actions` (or the old `provides` and `permissions`), `homepage`, `replaces`,
`conflicts_with`) plus `repo` (optional: `owner/name` on GitHub, the package is then read from
there as in section 9 and kept updated from there; `sha256`, when not empty, is checked against the
release's package or the package built from the archive) and the package's `url` (absolute
or relative to the index), `sha256` and `size`, and these optional store fields (an older Myspoti
ignores them, an index without them still reads):

| Field | Type | Shown |
|---|---|---|
| `icon_url` | string | The icon before download: a PNG, absolute or relative to the index (`myspoti-ext index` writes `icons/<id>.png`). `icon`, the older name, is still read. |
| `updated_at` | string | ISO 8601 date the version was listed (`2026-10-23`); `index` keeps it while the package's sha256 is unchanged. |
| `category`, `tags`, `changelog` | | As in the manifest. |
| `min_app_version` | string | As the manifest's `minAppVersion`: the row says "Requires Myspoti X or newer" and its Install button is off. |

In the app, Extensions has two tabs. **Store**: each repository with its trust note (verified by the
owner, or added by the user), a search field and category chips, and one row per extension
(icon, name, version, one line, its actions as icons, critical ones in the warning colour, one
button: Install, Update, Installed; "Incompatible with X (installed)" when `conflictsWith` or
`replaces` meets an installed one). A row opens the store page (description, changelog, tags, each
action's icon, label and your `why`, repository, sha256) before anything is downloaded; Install
then shows the install prompt: name, author and source, every action with your `why` (critical
ones marked), what it collides with (the user picking which to keep, section 4.2), the third-party
notice ("Third-party extension, not reviewed by Myspoti, under its author's responsibility.") and
one box, "I understand this extension is made by a third party and I install it on my own
responsibility", which enables Install. There is no per-action choice: an extension gets every
action it lists, or is not installed. The official repository is suggested, never added without the user.

**Installed** is organised **by area**: Playback, Lyrics & song info, Audio & downloads, Home &
search, Menus & player, Library & Spotify, Pages & more. Each area opens with a **Built-in** row (a
dropdown of what Myspoti does there by itself, e.g. Lyrics: Spotify, LRCLIB, NetEase, KuGou,
Lyrics+, Genius; built-in sources are not extensions), then the extensions acting there with their
switch and action icons, in the user's order (arrows; installation order until moved). That order
is the one the area asks them in (section 3) and the one the playback arbiter uses. The Built-in row says in
one plain sentence what the app already does there; an area no extension acts in says "No
extension — the app already does: …". Nothing is ever installed without the install prompt, and
the samples in `docs/extensions/samples` are for authors only. Audio and download sources are
ordered in Settings (Playback, Downloads). Under each area, the last things
extensions did there ("Lyrics of X from Y", "Skipped by Z") with a one-tap Turn off.

## 2. Manifest

`manifest.json` at the root of the package. Unknown keys are ignored.

| Field | Type | Default | Rule |
|---|---|---|---|
| `id` | string | required | Lower case, reverse-domain, at least two parts: `^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$`. Never changes between versions. |
| `name` | string | required | 1 to 40 characters. |
| `version` | string | required | 1 to 20 characters, shown to people (`"1.2.0"`). |
| `versionCode` | int | `1` | 1 or more. Higher is newer; this is what is compared. |
| `apiVersion` | int | `1` | Between 1 and 1 (the version this Myspoti speaks). |
| `author` | string | required | 1 to 60 characters. |
| `description` | string | `""` | 500 characters at most. |
| `homepage` | string | none | Not checked. |
| `main` | string | `"main.js"` | A `.js` file at the root of the package (no `/`, no `\`). |
| `actions` | object[] | required | What it does, from the catalogue of section 4: `{ "name", "why", "hosts"? }`. At least one, each once. |
| `settings` | object[] | `[]` | Options the user sets on the extension's page. A key at most once. Needs the `settings.page` action. |
| `replaces` | string[] | `[]` | Extension ids this one takes the place of (10 at most): installing it offers to turn them off. |
| `conflictsWith` | string[] | `[]` | Extension ids that must not run with this one (10 at most): the user picks which to keep. Either side declaring it is enough. |
| `icon` | string | none | A `.png` at the root of the package (square, 256 px or less). Without it, `icon.png` is used if the package has one, else a tile with the name's first letter. |
| `category` | string | `other` | Where the store files it, one of a fixed list: `lyrics` (needs `lyrics.provide` or `trackinfo.provide`), `playback` (Playback & audio: a `playback.*`, `audio.source` or `player.buttons` action), `library` (`library.read`, `library.write` or `playlists.write`), `discovery` (`home.sections`, `search.provide` or `spotify.catalog`), `stats` (`playback.events` or `library.read`), `look` (Look & feel: a blocks action or `webview.page`), `social` (`network`, `library.read` or `spotify.catalog`), `tools`, `other`. A category the actions do not fit, or an unknown one, is filed under `other` (`myspoti-ext check` says so); an extension with `download.source` is always under `tools`. `tags` are shown and found by the search, not filtered on. |
| `tags` | string[] | `[]` | 8 at most, each 1 to 24 characters: lower case letters, digits, spaces, `-`. The store's search finds them. |
| `minAppVersion` | string | none | The oldest Myspoti it works on (`"2.0.2"`, `"2.0.2-beta.4"`), compared like semantic versions (a release is newer than its betas). An older Myspoti shows "Requires Myspoti 2.0.2 or newer" and does not install it. |
| `changelog` | string | `""` | What changed in this version, 2000 characters at most: shown on the store page and before an update. |
| `welcome` | object | none | `{ "message": "...", "steps": ["...", "..."] }`, shown once right after the first install (not on updates): what the extension does (600 characters at most) and what is left to do (5 steps at most, 160 characters each). Older apps ignore it. |

An action entry: `{ "name": "...", "why": "...", "hosts": [...] }`.

- `name` is an action key (section 4). Unknown names, or a name twice, refuse the package.
- `why` is **required for every action**, 1 to 80 characters, in your words: it is shown next to
  the action's icon in the install prompt and on the store page. A missing or longer `why` refuses
  the package.
- `hosts` is for `network` only, and `network` needs at least one. Each host is lower case: a
  name (`api.example.com`), `*.example.com` (the domain itself and every subdomain), or `*` (any
  host, a critical action shown as "Use any website"). Names have at least two labels: no port,
  no scheme, no path, no `localhost`.

**Old manifests** (before actions: `provides` + `permissions`) still load and install, mapped to
actions (section 4.1); `myspoti-ext check` says so. `actions` cannot be mixed with them.

A setting: `{ "key", "type", "label", "description", "default", "options" }`.

- `key`: letters, digits, `_`, 1 to 40 characters.
- `type`: `text` (default), `secret` (a password field, never logged), `toggle`, `number`, `choice`, `button`.
- `label`: required. `description`: optional. `default`: any JSON value. `options`: strings, required
  (non-empty) for `choice`.
- `button`: an action, not a value. It has no `default` (the check refuses one) and no value, so
  `myspoti.settings.get` gives `undefined`. It is shown as a button with its `label` and `description`.
  Pressing it sends the `setting` event with `pressed: true` (section 3). Typical uses: pair again
  with a server, clear a cache, sign in again.
- Read it with `myspoti.settings.get(key)`: the user's value, else `default`, else `undefined`.

Settings are shown on the extension's page. Every installed extension that declares settings is also
listed in the app's Settings screen, in the section "Extensions", and each row opens its page.

A downloader with a quality choice and a button (its manifest also lists `settings.page` in `actions`):

```json
"settings": [
  { "key": "quality", "type": "choice", "label": "Quality", "options": ["FLAC", "MP3 320", "AAC 256"], "default": "FLAC" },
  { "key": "pair", "type": "button", "label": "Pair again with the server" }
]
```

`myspoti-ext check` prints every problem in plain words.

## 3. Hooks

A hook is a fixed point where the app asks the extension. Register it with
`myspoti.provide(name, fn)`; `fn` gets one object and returns its answer. Each hook is unlocked by
one action, which must be in the manifest, otherwise `provide` throws (`provide("player") needs the
player.buttons action in the manifest`):

| Hook | Action | Input | Answer |
|---|---|---|---|
| `lyrics` | `lyrics.provide` | track | lyrics (below) |
| `audio-source` | `audio.source` | track | `{ url, … }` |
| `download-source` | `download.source` | track + quality | files |
| `trackinfo` | `trackinfo.provide` | track | blocks, under the song's credits |
| `home` | `home.sections` | `{}` | sections of blocks, at the end of Home |
| `search` | `search.provide` | `{ query }` | blocks, under the search results |
| `menu` | `menu.items` | `{ kind, uri, title, subtitle }` (`kind`: track, album, playlist, artist) | `menu` blocks, at the end of that ⋯ menu |
| `player` | `player.buttons` | track | `button` blocks, under the player's controls |
| `page` | `webview.page` | `{}` | `{ title?, html }` (section 3, "Pages") |
| `message` | `webview.page` | what its page posted | the reply posted back to the page |
| `tap` | any blocks action | `{ action, surface, context }` | nothing (the app asks the blocks again) |
| `status` | none | `{}` | `{ live, detail? }` (below) |

The lyrics, song details, Home, search, menu and player hooks are **additive**: every extension is
asked, the answers merged in the area's order (lyrics: the best, ties to the higher one). Audio and
downloads are **ordered**: asked one after the other in the user's order until one answers (section 4.2). Registering twice replaces. Returning
`null`/`undefined` means "no answer": the app goes on without this extension. A throw is a
failure (recorded as the extension's last error, shown on its page; 3 in a row turn it off). A
timeout, or an `http` error your script did not catch (DNS, connection, timeout), is a **network**
error: logged, never counted as a failure, so a slow or rate-limited server never turns an extension
off. `lyrics`, `audio-source` and `download-source` may also say why they have nothing with an
outcome (section 3, "Outcomes"). Calls are synchronous: return the answer, do not return a promise.

The input of every hook, and of the `track` event, is this track:

```
{ id, uri, title, artist, artists, album, durationMs, isrc?, cover? }
```

`id` is Spotify's track id (empty for a local file), `artist` the first of `artists`. `isrc` and
`cover` are absent when unknown (`null` inside a `playback` event).

### `lyrics`

Asked among the app's own lyrics sources. Return one of:

```js
{ provider: 'Name',                 // optional, 40 characters at most, default: the extension's name
  synced: true,                     // optional, default: true if any line has a time
  lines: [
    { time: 1500,                   // ms from the start; missing = 0
      end: 4000,                    // optional ms
      text: 'line',                 // 500 characters at most
      words: [ { time: 1500, end: 1900, text: 'word' } ]   // optional, `time` required, 200 at most
    } ] }
```

or `{ provider?, plain: 'text\nof the song' }` for unsynced lyrics (one line per row; used only
when `lines` is absent). At most 3000 lines (more is an error). Times are numbers or numeric
strings, in milliseconds. Lyrics with no non-blank text count as no answer. Word-by-word display
needs `synced` and at least one line with `words`.

Action: `lyrics.provide` (normal). Usually you also need `network`.

### `audio-source`

Where the audio of a song is. The song then plays in Myspoti's own player, like a download;
Spotify's player does not play it. Action: `audio.source` (critical, ordered: the sources are
asked in the user's order, Spotify included; the ones under Spotify are not asked).

```js
{ url: 'https://cdn.example.com/a.m4a',   // http or https, not a local address; required
  headers: { Referer: '...' },            // optional, 20 at most; host, content-length, connection, cookie are dropped
  mimeType: 'audio/mp4',                  // optional
  durationMs: 215000,                     // optional
  expiresInMs: 300000,                    // optional: how long the address stays good
  codec: 'flac', bitDepth: 24, sampleRate: 96000,  // optional: the quality, shown as you say it
  source: 'FLAC · 16-bit · 44.1 kHz' }    // optional: free text shown when the three above are absent (80 characters)
```

**Lossless DASH.** Instead of `url`, `{ manifest, mimeType?, durationMs? }`: an MPEG-DASH manifest,
base64 (1 MB at most), of FLAC in fragmented MP4 with a `SegmentTemplate` + `SegmentTimeline` and
absolute https addresses (as a `dash-flac` download). Myspoti plays its segments in order as one
file, seekable. Check `myspoti.features.indexOf('audio.manifest') >= 0` first: an older Myspoti has
no `features` and needs a `url`. The song is asked for 10 s after the one before starts, so it starts
at once.

The song comes with its `isrc` when Spotify knows it (asked from its catalogue if the list lacked it),
and `ahead`: `true` when the app asks before the song plays (the next song of the queue, 10 s after
the one before starts), `false` when it is about to play. An answer given ahead is kept and played as
it is when the song comes (it is not asked again), so it must be just as playable; only the urgency
differs: you may take more of the time, and leave out side work that only matters once the song
plays. Check `myspoti.features.indexOf('audio.ahead') >= 0`: an older Myspoti does not send it.

**Quality.** `codec` (lower-case, 16 characters: `"flac"`, `"aac"`, `"mp3"`…), `bitDepth` (8 to 32)
and `sampleRate` (Hz, 8000 to 768000) are what the song's sound-quality line shows while it plays
("FLAC · 24-bit · 96 kHz"), with either answer. Myspoti shows what you announce, without checking or
rounding it up; a value out of range is left out. Without any of the three, your `source` text is
shown instead (80 characters at most; feature `audio.source`), else nothing. Feature:
`audio.quality`.

No `url` (or a null answer) means "no audio for this song". A `file:`/`content:` URL or a
private address is an error. Note the returned `url` is checked for being public, not against your
`network` hosts.

**When it is asked.** Every play of a song goes to the active `audio-source` extension first,
wherever it starts: a list or an album, a search result, an artist, Home, Recents, shuffle, a
Spotify link opened with Myspoti, another extension's block. It is asked for the picked song (or,
for shuffle, a random one of the list); with an answer, that song plays in Myspoti's own player and
the rest of the list follows as the queue, each song asked when its turn comes. Without an answer,
the song plays on Spotify as usual. A song already downloaded plays from its file. Episodes, plays
while offline or with data saver on, and plays while another Spotify Connect device is playing,
never ask.

**In the player.** The song stays the Spotify song: like, lyrics, Canvas, clip and queue work as
usual, and "Play next" / "Add to queue" go into Myspoti's own queue (each song asked when its turn
comes). Your extension's name, then that quality line, is shown under the output device (a tap opens the sound-quality sheet), and your name beside the artist in the mini player. Picking a
Spotify Connect device hands the song, its list and its place over to that device, played by
Spotify.

**An address that expires.** An answer is reused about ten minutes (seeks, replays), or
`expiresInMs` when it is shorter (milliseconds from your answer; ten minutes at most, `0` or less:
never reused). A song that starts after that asks you again. When the server refuses the address
(HTTP 401, 403, 404 or 410), the answer is forgotten and you are asked once more for that song; a
second refusal fails the song cleanly (the player moves on). Feature: `audio.expires` (an older
Myspoti ignores `expiresInMs` and retries on 401 and 403 only).

**Starting at once.** For the next song, Myspoti also reads ahead the first 512 KB of a `url`
(then asks the rest from there with an HTTP `Range`, sending your `headers`), or the first segment
of a `manifest`, once per song, off mobile data, and drops it if the queue changes. A server that
does not answer a range with 206 and a `Content-Range` total simply gets the whole file as before.

A `download-source` extension does not stream by itself: if its files should also play without
being downloaded, provide `audio-source` as well (the two may share their code).

### `download-source`

Where a song can be downloaded from, and which file is the right recording: finding the audio,
fetching what it needs and matching it to the song are the extension's job. Myspoti only keeps
what you hand over: it downloads the file, runs the same checks on any file whatever its origin
(below), encrypts it, and lists it in Downloaded. Myspoti never compares the file with a reference
recording: if you want that check (an official preview, a fingerprint), do it in the extension
before answering, and say `isrcMatched` only for a file you found by the song's ISRC. Without an
extension providing this hook, downloads tell the user to install one. Action: `download.source`
(critical, ordered: asked in the user's order until one has the song).

The input is the track of this section plus `quality`, what the user asked for:

```
{ id, uri, title, artist, artists, album, durationMs, isrc?, cover?, quality }
```

`quality` is `"hi-res"` (the best there is), `"cd"` (16-bit lossless) or `"small"` (a small file;
a FLAC you return is then re-encoded to AAC on the phone). Return the files you have, best first:

```js
[ { url: 'https://cdn.example.com/x.flac',   // the file; http(s), public address
    format: 'flac',                         // required: flac, dash-flac, m4a, mp3
    headers: { Referer: '...' },            // optional, as for audio-source
    bitDepth: 24, sampleRate: 96000,        // optional, shown and logged
    sizeHint: 91234567,                     // optional, bytes, for the progress bar
    provider: 'Example',                    // optional, 40 characters: shown in Downloaded (default: the extension's name)
    isrcMatched: true },                    // optional: you found this file by the song's ISRC
  { format: 'dash-flac', manifest: '<base64 of a DASH MPD>' } ]
```

`{ files: [...] }` or a single file object work too. 10 files at most. `null`, `[]` or files with
no `url` mean "nothing for this song". `dash-flac` takes `manifest` instead of `url`: an MPEG-DASH
manifest (base64, 1 MB at most) of FLAC in fragmented MP4 (a `dfLa` box in the init segment),
described by one `SegmentTemplate` with `initialization`, `media` using `$Number$`, and a
`SegmentTimeline`; Myspoti fetches the segments with your `headers` and joins them into one FLAC
file. Anything else a service needs (another manifest layout, decryption, an API call per
segment, a token refresh) is yours to do: hand over a plain file `url`, or a manifest in that form.
Myspoti tries the files in your order and keeps the first that passes the checks: a FLAC must be
a real FLAC file (its header is read), an `m4a` or `mp3` must hold an audio track the phone can
read, and the length must be the song's (Spotify's duration): not shorter by more than 5 s
(previews are refused) nor longer by more than 15 s (another version). `m4a` and `mp3` are kept as
served. A format outside the list, a private address or a missing `format` is an error. The
extensions are asked in the user's order for downloads (section 4.2); the first one that answers
wins. Time limit: 30 s (the desktop runner: the same).

### `status`

Whether your source answers now, for an extension that depends on a server (an `audio-source` or
`download-source` above all). No action is needed: any extension may provide it.

```js
myspoti.provide('status', function () {
  var r = myspoti.http.get('https://api.example.com/ping', { timeout: 3000 });
  return r.status === 200 ? { live: true } : { live: false, detail: 'The server is down (' + r.status + ')' };
});
```

- **Answer**: `{ live: true | false, detail? }`. `detail` is shown as it is, trimmed to 120
  characters; say what the user can do ("Sign in again in the settings").
- **When**: in the background, when the extension starts, then every 5 minutes while the app is on
  screen or playing, when the network comes back, and right before a song's audio or a download is
  asked from it if the last answer is older than 5 minutes (the song waits at most for the budget).
- **Budget**: 5 s. Keep it to one cheap request. A call already running for that extension (a
  song's audio) is not interrupted: the probe is skipped and asked again later.
- **Effect**: `live: false` makes the extension **inactive**: it is skipped for `audio-source` and `download-source` (the next
  extension in the area's order is asked, or the song plays from Spotify), its row and page say
  "Inactive" with your `detail`. It stays on, is not counted as failing and is not turned off; the
  next answer `live: true` makes it active again at once. Its other hooks keep being asked. A
  throw, an answer that is not `{ live }` or no answer in 5 s proves nothing (a busy phone, a slow
  start): the extension stays in use, and its log says the probe had no answer.
- Without a `status` hook an extension is always live. Check
  `myspoti.features.indexOf('status') >= 0`: an older Myspoti never asks it.

### Outcomes (`results`)

`lyrics`, `audio-source` and `download-source` may answer **why** they have nothing instead of
`null`, when `myspoti.features.indexOf('results') >= 0` (an older Myspoti reads such an answer as no
answer, or as a malformed one: check the feature first):

```js
myspoti.provide('audio-source', function (track) {
  var r = myspoti.http.get('https://api.example.com/find?isrc=' + track.isrc);
  var results = myspoti.features && myspoti.features.indexOf('results') >= 0;
  if (r.status === 429 && results) return { outcome: 'rate-limited', retryAfterMs: 1000 * Number(r.headers['retry-after'] || 60) };
  if (r.status === 401 && results) return { outcome: 'sign-in', detail: 'Sign in again in the settings' };
  if (!r.ok) return null;
  return { url: r.json().url };
});
```

| `outcome` | Fields | What the app does |
|---|---|---|
| `not-found` | | Same as `null`. |
| `sign-in` | `detail?` | A notice "sign in to keep using its source" with a **Sign in** button that opens your extension's page (offer the sign-in there: a setting button, your page). Gone at your next answer with audio, or after a sign-in page reached its callback. |
| `rate-limited` | `retryAfterMs?` | Your source is set aside (not asked) for `retryAfterMs`, bounded to 30 s – 15 min (60 s without it). |
| `server-down` | `untilMs?` (wall-clock ms) or `retryAfterMs?` | Set aside until then, bounded to 30 s – 15 min (5 min without either). |
| `quality-unavailable` | `available?: ["cd", …]` | Logged and shown in "Why this source?"; the next source is asked. |
| `network` | `detail?` | A network error you caught: logged, not counted. |
| `error` | `detail?` | A failure you caught: logged, not counted (an uncaught throw is). |

Every field is optional; `detail` is trimmed to 120 characters. An unknown `outcome` is a failure.
Every outcome, the ones the app classifies itself too (`found`, `not-found`, `network` for a timeout
or an `http` error, `bug` for a throw), is written to your extension's log with its time. Three
network errors in a row also set a source aside (30 s, doubling up to 15 min). While a source is set
aside the next one is asked, or the song plays from Spotify. The player's sound quality sheet has
**Why this source?**: for the song playing, each source asked with its outcome and time, the ones
skipped and why, the one chosen, how its address opened (time, HTTP status, error), the quality it
announced and the one the player's decoder reports (its input, not proof of a bit-perfect output).
Addresses are shown without their query string.

### `track` event

`myspoti.on('track', function (track) { ... })` is called for each new song, with the song's fields
(as hooks receive them) plus `origin` and `providerId`, as in the `playback` event below. It needs
`playback.events` in the manifest (checked when you call `on`) and granted by the user (otherwise
nothing is delivered). Return values are ignored; an error in a listener is swallowed (only logged),
and later listeners of the same event do not run. Time limit 3 s per delivery. There is no way to
remove a listener.

### `playback` event

`myspoti.on('playback', function (e) { ... })`: what happens to the song playing, in Myspoti's own
player (downloads, local files, songs from an `audio-source` extension) and in the Spotify Web
Player. Same rules as `track` (`playback.events`, 3 s per delivery, errors swallowed). Events arrive in
order, one at a time.

```
{ type,          // start, progress, pause, resume, seek, skip, end
  track,         // the track of this section
  positionMs, durationMs,
  timestamp,     // ms since 1970, UTC, when it happened
  source,        // "local": Myspoti's own player; "spotify": the Web Player
  contextUri,    // the list, album or artist it plays from; "" when unknown (a local file)
  shuffle,       // true or false
  reason,        // see below; null for progress, pause, resume
  playedMs,      // time actually played of this song so far (pauses and seeks excluded)
  origin,        // where the audio comes from: "spotify", "download", "audio-source", "local-file"
  providerId }   // id of the extension that supplied the file or the stream; null for Spotify
```

`origin`: `spotify` is Spotify's own stream; `download` a song downloaded with a `download-source`
extension (`providerId` is that extension; null for a download made before Myspoti kept it);
`audio-source` a stream an `audio-source` extension gave (`providerId` is the one that answered);
`local-file` a file on the phone that is not a download (`providerId: null`). An extension can use it
to tell its own plays from the others', for example not to scrobble twice.

| `type` | When | `reason` |
|---|---|---|
| `start` | A song starts. | `play` (first song, or after a stop), `next` (the previous one ended), `skip` (the previous one was skipped) |
| `progress` | Every 10 s of play. | — |
| `pause` / `resume` | | — |
| `seek` | The position moved by more than 3 s. | `forward`, `backward` |
| `skip` | The song left before its end. | `forward` (next), `backward` (previous), `other` (a song picked, the queue changed) |
| `end` | The song left at its end (within 5 s), or playback stopped. | `finished`, `stop` (stopped, or switched to the other player) |

Every song that started leaves with exactly one `skip` or `end`. The players are read about once a
second, so positions and times are within a second; three seconds with nothing playing count as a
stop. While no extension listens to `playback`, nothing is read.

### Blocks (`trackinfo`, `home`, `search`, `menu`, `player`)

An extension never draws: it describes **native blocks** as JSON, and the app draws them with its own
fonts, colours, spacing and icons, under the extension's name ("From X"). Answer a list of blocks
(or one block). A block of an unknown type is left out (a newer extension on an older app); a block
that breaks a rule fails the whole answer (a failure, like a throw).

| Type | Fields | Where |
|---|---|---|
| `section` | `title`, `subtitle?`, `blocks` (12 at most, no section inside) | Home, search, song details. On Home, loose blocks are gathered in one untitled section. |
| `card` | `title`, `subtitle?`, `image?`, and `open` or `action` | Home, search, song details |
| `tracks`, `albums`, `playlists`, `artists` | `title?`, `items`: `[{ uri, title, subtitle?, image? }]` (50 at most; `uri` a `spotify:` uri of that kind) | Home, search, song details. A song plays, the rest opens its page. |
| `button` | `label` (40), `icon`, `action` | Home, search, song details, the player |
| `menu` | `label` (40), `icon`, `action` | ⋯ menus only |
| `text` | `text` (1000), `style`: `title`, `body` (default), `caption` | Home, search, song details |
| `image` | `url`, `ratio?` (0.5 to 3, default 1.78), `open` or `action` | Home, search, song details |

- `open`: a `spotify:` uri of a track, album, playlist, artist, show or episode; the app opens it.
  Nothing else can be opened: no web address, no app screen.
- `action`: 1 to 64 letters, digits, `_ . : -`. A tap calls your `tap` hook with
  `{ action, surface, context }` (`context`: the place, e.g. the menu's `{ kind, uri, title }`).
- `icon`: one of `add album artist bolt bookmark chart check download edit extension favorite
  headphones info language link lyrics mic music open play playlist queue radio refresh repeat
  search settings share shuffle star timer translate tune volume`; anything else is `extension`.
- Images: https on a public host. Text is cut to its length. No colour, size, position or layer.
- At most 10 top-level blocks per answer (4 menu entries, 3 player buttons). On screen, quotas per
  extension: 2 menu entries, 1 player button, 2 Home sections, 4 blocks in search and song details;
  in all: 4 menu entries, 3 player buttons, 6 Home sections. What is over goes under an
  **"Extensions ⋯"** entry (menus, player); Home, search and song details drop it. An item, card or
  entry already given by a higher extension (same uri, same label) is dropped.
- `myspoti.ui.refresh('home' | 'search' | 'menu' | 'player' | 'trackinfo')` asks the app to ask again.

```js
myspoti.provide('menu', function (t) {
  if (t.kind !== 'track') return [];
  return [{ type: 'menu', label: 'Find the sample', icon: 'search', action: 'sample' }];
});
myspoti.provide('tap', function (e) {
  if (e.action === 'sample') myspoti.notify('Looking for ' + e.context.title);
});
```

`docs/extensions/samples/blocks-demo` shows one block of each kind.

### Pages (`page`, `message`)

`webview.page` (critical) gives the extension one free page, opened from its detail page ("Open
its page"). `page` answers `{ title?, html }` (512 KB). The app shows it in a locked WebView: no
file or content access, no navigation (links do nothing), no new windows, no storage, no cookies,
no geolocation. Everything it loads is fetched by the app: GET over https from the hosts your
`network` action lists (any host only with `network.any`), never a local or Spotify address, without
cookies; a Content-Security-Policy says the same (no frames, no forms). The only bridge is
`MyspotiPage.postMessage(text)`, to your own extension: your `message` hook receives it (parsed
JSON if it is JSON) and its answer is dispatched to the page as a `message` event
(`window.addEventListener('message', e => e.data)`). `myspoti.page.post(data)` sends to the page
at any time, if it is open.

Sign-in pages are a different thing, see "Sign in on a website" below.

### Sign in on a website (`auth`)

Action: `webview.auth` (critical, "Sign in on a website"). `myspoti.auth.open({ url, callback })` opens
the page at once and returns an id. Later, `myspoti.on('auth', fn)` is called with `{ id, url }` when
the page reached the callback (the full address, query and fragment included), or with `{ id, error }`
(for example `"closed"`). Use the id to tell your sign-ins apart. `auth` needs `webview.auth` in the manifest.

```js
function signIn() {
  return myspoti.auth.open({ url: 'https://login.example.com/start', callback: 'myextension://done' });
}
myspoti.on('auth', function (e) {
  if (e.error) return myspoti.notify('Sign-in: ' + e.error);
  myspoti.storage.set('callback', e.url);   // the answer is in the query of e.url
});
```

**Rules.** `url` must be https and not a Spotify address. `callback` is a custom scheme
(`myextension://done`) or an https address on a non-Spotify host. `javascript:`, `data:`, `file:`,
`intent:`, `http:` and the like are refused.

The page opens full screen in a web view with its own profile for each extension. Its cookies and
storage are never the app's or Spotify's, and the extension never gets them. The profile persists
between sign-ins of the same extension. Cookies, POST forms, redirects and iframes work as in a browser.

The callback is caught on links, on redirects, and on a form POSTed to an https callback. The POST
body itself is not returned: use a callback that puts the answer in the query.

**Hidden.** `myspoti.auth.open({ url, callback, hidden: true })` loads the same page out of sight
first: a check that passes by itself (redirects, an invisible captcha) reaches the callback with
nothing on screen. If it has not after 8 seconds, the same page (not reloaded) is shown full screen
with its Close button, for the user to finish it. Use it for a verification that usually needs
nobody; leave it out for a real sign-in form.

**Errors.** `{ id, error }` is `"closed"` (the user closed it, or pressed Back), `"timeout"` (no
callback after 3 minutes) or `"network: …"` (the page could not be loaded). An HTTP error status of
the page itself is not an error: challenge pages often answer 403 or 503. A callback the page opens
with `window.open` is caught too; no second window is ever shown.

Only one sign-in page is open at a time. It needs Android System WebView with multi-profile support;
otherwise `auth.open` throws. It is separate from `webview.page`, whose page stays locked as before
(no cookies, GET only from listed hosts).

### Waiting for the network (`online`)

Action: `network`. `myspoti.work.whenOnline()` asks for one `online` event. `myspoti.on('online', fn)`
is called at once when the phone has a validated network, or as soon as it comes back, also after the
app restarts. Asking again before it fires changes nothing. The work runs with a 30 s time limit.
`online` needs `network` in the manifest.

Typical use: send a queue of things the extension kept in `myspoti.storage` while offline.

```js
myspoti.on('track', function (t) {
  var queue = myspoti.storage.get('queue') || [];
  queue.push(t.uri);
  myspoti.storage.set('queue', queue.slice(-200));
  myspoti.work.whenOnline();
});
myspoti.on('online', function () {
  var queue = myspoti.storage.get('queue') || [];
  queue.forEach(function (uri) { myspoti.http.post('https://plays.example.com/api', { uri: uri }); });
  myspoti.storage.remove('queue');
});
```

(manifest: `playback.events`, `storage`, and `network` for `plays.example.com`.)

### Settings changed (`setting`)

Action: `settings.page`. `myspoti.on('setting', fn)` is called when the user changes one of the
extension's settings (section 2):

- `{ key }` when a setting changed. For a text field, it comes once the user stops typing for a
  moment. Read the new value with `myspoti.settings.get(key)`. The event carries only the key.
- `{ key, pressed: true }` when the user presses a `button` setting.

It needs `settings.page` in the manifest (checked when you call `on`). Time limit 30 s per delivery.

```js
myspoti.on('setting', function (e) {
  if (e.key === 'pair' && e.pressed) pair();
  if (e.key === 'quality') myspoti.log('quality is now ' + myspoti.settings.get('quality'));
});
```

## 4. Actions

The closed catalogue. The icon is the Material Rounded icon the app draws; the user sees the icon,
the label and your `why`. **Critical** actions are shown in the warning colour and explained; the
app frames them at run time (destructive Spotify writes are confirmed one by one, section 6; audio
and downloads are asked in the user's order; pages are locked down, sign-in pages have their own profile). The sandbox enforces exactly what the manifest
declares: a call outside it throws (`"x is not in this extension's manifest (actions)"`). The user
cannot untick single actions: installing grants all of them. An extension installed by an older
Myspoti that let the user untick some stays off, with a "Waits for your OK" card on its page,
until the user accepts them all with the same tick.

| Action | Icon | Label | Level | Mode | Unlocks |
|---|---|---|---|---|---|
| `playback.control` | PlayCircle | Control playback | normal | shared | `myspoti.player.play/pause/next/previous/seek`; `spotify.player` play, pause, resume, seek, next, previous, shuffle, repeat, volume, speed, skipTo; `spotify.devices` transfer, pullHere |
| `playback.queue` | QueueMusic | Edit the queue | normal | shared | `spotify.player` addToQueue, playNext, moveInQueue, removeFromQueue, clearQueue |
| `playback.events` | GraphicEq | Know what you play | normal | shared | `myspoti.player.current()`, `myspoti.on('track' \| 'playback')`, `spotify.player` state, queue, volume, media (`myspoti.spotify.media`) |
| `lyrics.provide` | Lyrics | Provide lyrics | normal | additive, takeover | `lyrics` hook |
| `trackinfo.provide` | Info | Add song details | normal | additive, takeover | `trackinfo` hook |
| `audio.source` | Headphones | Play audio from elsewhere | **critical** | ordered, takeover | `audio-source` hook |
| `download.source` | Download | Provide downloads | **critical** | ordered, takeover | `download-source` hook |
| `home.sections` | Dashboard | Add Home sections | normal | additive, takeover | `home` hook |
| `search.provide` | ManageSearch | Add search results | normal | additive, takeover | `search` hook |
| `menu.items` | MoreHoriz | Add menu entries | normal | additive, takeover | `menu` hook |
| `player.buttons` | SmartButton | Add player buttons | normal | additive, takeover | `player` hook |
| `settings.page` | Tune | Its own settings | normal | takeover | manifest `settings`, `myspoti.settings`, `setting` event |
| `notify` | Notifications | Show short messages | normal | shared | `myspoti.notify` |
| `library.read` | LibraryMusic | Read your library | normal | shared | `spotify` library, own playlists, users, recents, devices.list, saved episodes, Blends (reads), recent searches, What's new |
| `library.write` | LibraryAdd | Change your library | **critical** | shared | `spotify` library edits, follows, private session, taste and bans, saved episodes, clear recent searches |
| `playlists.write` | PlaylistAdd | Edit your playlists | **critical** | shared | `spotify.playlists` edits, `spotify.blends` invite/join/rename/leave |
| `spotify.catalog` | Album | Browse Spotify | normal | shared | `spotify` search, catalog, public playlists, home, podcasts, extras |
| `spotify.raw` | Code | Full Spotify access | **critical** | shared | `spotify.raw` (GraphQL, page services, Spotify URLs); `raw.request` writes can be kept with Always allow (section 6) |
| `network` | Public | Use the internet | normal (`"*"`: **critical**, "Use any website") | shared | `myspoti.http` to the listed hosts; `myspoti.work.whenOnline()` and the `online` event |
| `webview.page` | Web | Its own web page | **critical** | takeover | `page` and `message` hooks, `myspoti.page.post` |
| `webview.auth` | Key | Sign in on a website | **critical** | takeover | `myspoti.auth.open`, `auth` event |
| `storage` | Save | Keep its own data | normal | shared | `myspoti.storage` (512 KB) |
| `attest` | Fingerprint | Prove this is Myspoti | normal | shared | `myspoti.attest` (section 10) |

### 4.1 Old manifests

A manifest with `provides` and `permissions` (before actions) still loads, installs and runs. Its
keys become actions: `lyrics` → `lyrics.provide`, `audio-source` → `audio.source`,
`download-source` → `download.source`, `network` → `network`, `notify` → `notify`, `player.read` →
`playback.events`, `player.control` → `playback.control`, `spotify.catalog` → `spotify.catalog`,
`spotify.library.read` → `library.read` + `playback.events`, `spotify.write` → `library.write` +
`playlists.write` + `playback.control` + `playback.queue`, `spotify.raw` → `spotify.raw`; `storage`
(and `settings.page` with settings) are added, as every old extension had them. A permission named
after a hook still gives that action's `why` (cut to 80 characters); a critical one still needs 15
characters or more. What an installed extension was granted, and the priorities per hook, are
migrated the same way when the app starts.

### 4.2 Living together

- **Additive** actions (lyrics, song details, Home, search, menus, player): every extension adds;
  answers are merged in the area's order, duplicates dropped, quotas per extension (section 3).
- **Ordered** actions (`audio.source`, `download.source`): every source is asked in the order the
  user sets in Settings (Playback, Downloads), each action its own order, until one answers. A new
  source goes last; installing a second one asks nothing. For audio, Spotify has its place in the
  order too (last until moved): the sources under it are not asked.
- `conflictsWith` / `replaces`: "Incompatible with X (installed)" / "Replaces X (installed)" in the
  store, and the install or turn-on prompt asks which to keep; the one not kept is turned off (or the
  new one installs turned off).
- **Playback arbiter.** Every playback command from an extension (`myspoti.player.*`,
  `spotify.player` play/pause/resume/seek/next/previous/shuffle/repeat/skipTo, `spotify.devices`
  transfer) goes through the app. Two extensions' commands of the same kind (play/pause,
  next/previous/skip, seek, shuffle/repeat) within 2 s: the one higher in the Playback area wins
  (a lower one after it is dropped: `myspoti.player.*` returns `false`, `spotify.player.*` returns
  `null`). The same two contradicting each other 3 times within 30 s: the lower one's playback
  commands are suspended and the user is told, with a Turn off button. At most 20 commands a minute
  per extension.
- **Isolation.** Each extension has its own thread, storage, settings, log and sign-in profile; no API names another
  extension. A block's tap, a page's messages and `myspoti.page.post` only ever reach the extension
  that drew them.

### 4.3 Protected surfaces

No extension can hide, delay, cover, restyle or observe these: the owner's donation reminder and
Support sheets, the "New version" card, the install prompt (warnings, third-party notice), the
confirmations of critical actions (destructive Spotify changes, granting a critical action), the
Report button (beta logs) and what it opens, About (version, credits, source, legal).

- No action, hook, block or `open` uri targets them (blocks open only `spotify:` content; a test
  checks the names never meet).
- Extension blocks are drawn inside the app's pages and pages in the app's WebView: these surfaces
  are dialogs and sheets above them, and the Report button is drawn after the app.
- While one is on screen, extension blocks and pages are not asked nor drawn, the page's WebView is
  hidden and paused, taps and page messages are dropped.
- No event about them exists (`myspoti.on` knows `track`, `playback`, `auth`, `online` and `setting` only).

## 5. The `myspoti` object

The script runs in Rhino 1.8.1 (ES6: `let`, `const`, arrow functions, template strings,
destructuring) on its own thread. It sees the standard JavaScript objects, `myspoti` and
`console`, and nothing else: no `require`, `fetch`, timers, DOM, or Java (`java`, `Packages` are
undefined). `myspoti` and `console` cannot be replaced. Everything is synchronous.

**Forbidden:** `eval`, `Function`, `new Function(...)`, `(function(){}).constructor(...)` and the
generator constructor all throw `"code from strings is not allowed in extensions"`. What was
reviewed is what runs.

A script that throws while loading fails to load, with `file:line` in the message.

| Call | Notes |
|---|---|
| `myspoti.apiVersion` | `1`. |
| `myspoti.extension` | `{ id, name, version }` from the manifest. |
| `myspoti.features` | What this Myspoti understands beyond the first API: `audio.manifest`, `audio.expires`, `audio.quality`, `audio.ahead` (section 3, `audio-source`), `http.all`, `status` (section 3), `results` (section 3, "Outcomes"), `attest` (section 10, only when the user granted it). Missing on an older Myspoti: test `myspoti.features && myspoti.features.indexOf(name) >= 0`. |
| `myspoti.provide(hook, fn)` | Section 3. |
| `myspoti.on('track' \| 'playback', fn)` | Section 3. Needs `playback.events`. |
| `myspoti.on('auth', fn)` | Section 3, sign in. Needs `webview.auth`. |
| `myspoti.on('online', fn)` | Section 3, waiting for the network. Needs `network`. |
| `myspoti.auth.open({ url, callback })` | Section 3, sign in. Returns an id at once. Needs `webview.auth`. |
| `myspoti.work.whenOnline()` | Section 3, waiting for the network. Needs `network`. |
| `myspoti.on('setting', fn)` | Section 3, settings changed. Needs `settings.page`. |
| `myspoti.http.get(url, options?)` | `options`: `{ headers, timeout }`. |
| `myspoti.http.post(url, body, options?)` | A non-string `body` is sent as JSON (`Content-Type: application/json` unless you set one). |
| `myspoti.http.request({ url, method, headers, body, timeout })` | Methods: GET POST PUT PATCH DELETE HEAD. |
| `myspoti.http.all([request, ...])` | Up to 4 requests sent together (below). Feature `http.all`. |
| `myspoti.query({ a: 1, b: 'x y' })` | `"a=1&b=x%20y"`. `null` and `undefined` values are left out. |
| `myspoti.storage.get(key)` / `.set(key, value)` / `.remove(key)` | `storage`. Your own store, values are JSON. `get` of a missing key is `undefined`. 512 KB in all (keys plus values), then `set` throws `"storage full (512 KB)"`. |
| `myspoti.settings.get(key)` | Section 2. |
| `myspoti.player.current()` | The track playing in Myspoti (shape of section 3) or `null`. `playback.events`. |
| `myspoti.player.play()`, `.pause()`, `.next()`, `.previous()`, `.seek(ms)` | Myspoti's own player. `playback.control`, through the arbiter (4.2). Return `true` if it was done. |
| `myspoti.ui.refresh(place)` | Section 3, blocks. The place's action. |
| `myspoti.page.post(data)` | Section 3, pages. `webview.page`. |
| `myspoti.spotify.<area>.<method>(...)` | Section 6. |
| `myspoti.attest.enroll(challenge)` / `.publicKey()` / `.sign(text)` / `.reset()` | Section 10. A signing key of its own in the phone's hardware keystore, with its key attestation, to prove requests to your own server. Needs `attest`. |
| `myspoti.notify(text)` | A short message on screen, 200 characters, with your extension's name. `notify`. |
| `myspoti.log(...)`, `console.log/info/warn/error(...)` | Same thing: arguments joined by spaces, objects as JSON, 2000 characters at most. |
| `myspoti.util.base64Encode(s)` / `base64Decode(s)` | UTF-8 text. Decode throws on bad input. |
| `myspoti.util.md5(s)`, `sha1(s)`, `sha256(s)` | Lower-case hex. |
| `myspoti.util.hmacSha256(key, text)` | Lower-case hex. |

`myspoti.player` (needs `playback.events` / `playback.control`) is Myspoti's own player. The
Spotify account's player (what the Web Player is playing) is `myspoti.spotify.player`, with the
playback actions.

### `myspoti.http`

Returns `{ status, headers, body, ok, json() }`. `headers` has lower-case names (several values
joined by `", "`), `body` is text, `ok` is true for 2xx, `json()` parses the body. A 4xx or 5xx
does not throw. A network failure, a refused host or a limit throws `Error("http: ...")`.

Rules, all enforced by the app:

- The host must be in your `network` hosts (a `*.domain` entry matches the domain and its
  subdomains), or you hold `network` with `*` and the user accepted it.
- https only. Plain `http` is allowed only to a host the manifest names exactly (never through
  `*.domain` or `*`).
- Local addresses are refused whatever the manifest says: `localhost`, `*.localhost`, `*.local`,
  IPv4 literals in 0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16, and every IPv6 literal.
- No cookies, no cache, no account. Redirects are not followed: you receive the 3xx and its
  `location` header. The headers `host`, `content-length`, `connection`, `cookie`,
  `transfer-encoding` are dropped. The default `User-Agent` is `Myspoti-Extension/1 (<id>)`.
- Timeout: `timeout` in ms, default 15000, kept between 1000 and 20000 and cut to what is left of
  the call's time. Response body: 5 MB at most.
- 120 requests per minute (sliding window): over that, `"more than 120 requests in a minute"`.

`myspoti.http.all([{ url, method, headers, body, timeout }, ...])` sends up to 4 requests at the
same time and waits for all of them: the script stays synchronous, the call returns once the last
one has answered. The answers come in the same order, each like `http.request`'s; a request that
fails (refused host, network failure, over the limit) gives `{ error: "http: ..." }` in its place,
without failing the others. More than 4 in one call throws `"http.all: at most 4 requests at once"`
(split the list yourself: nothing is queued). Every request is checked by the rules above and counts
in the 120 per minute; each timeout is cut to what is left of the call's time.

```js
var answers = myspoti.http.all(ids.map(function (id) { return { url: 'https://api.example.com/t/' + id }; }));
answers.forEach(function (r) { if (r.error) myspoti.log(r.error); else use(r.json()); });
```

Check `myspoti.features.indexOf('http.all') >= 0` first; without it, send them one by one.

Example, a listener that remembers what it saw:

```js
myspoti.on('track', function (t) {
  var seen = myspoti.storage.get('seen') || [];
  seen.push(t.uri);
  myspoti.storage.set('seen', seen.slice(-100));
});
```

(manifest: `"actions": [{ "name": "playback.events", "why": "Remembers the last 100 songs played" }, { "name": "storage", "why": "Keeps them" }]`,
and `"provides": []`.)

## 6. Spotify access

`myspoti.spotify.<area>.<method>(...args)` talks to the account signed in to Myspoti. Areas:
`player`, `search`, `catalog`, `library`, `playlists`, `home`, `podcasts`, `users`, `extras`,
`devices`, `recents`, `blends`, `raw`. The object is built from the app's own method table, so a
method that is not in the tables below does not exist (`undefined`). Jam (live shared sessions) is
not exposed.

```js
var r = myspoti.spotify.search.tracks('papaoutai');       // needs spotify.catalog
myspoti.spotify.library.add([r.items[0].uri]);             // needs library.write
```

**Arguments** are positional. Optional ones may be left out or `null`. Defaults are in the tables.

- URIs are strings (`"spotify:track:..."`). Lists of strings: 1000 entries at most.
- Numbers: integer arguments accept any number (truncated). Booleans are `true`/`false`. A wrong
  type is an error that names the argument; a number where a string is expected is wrong too.
- Enums are given by name, case-insensitive (`'private'`, `'short_term'`); results name them in
  upper case (`"PRIVATE"`).
- A queue item is `{ uri, uid? }`, or an item from `player.queue()` as is.
- The whole argument list may not exceed 256 KB.

**Results** are JSON: the app's models, field by field (section 6.2). A method that returns
nothing gives `null`. `null` fields are present as `null`. Depth is cut at 12 levels.

**Errors** are thrown as `Error` with a message starting `spotify.<area>.<method>:` :
`no such method`, `unreadable arguments`, `query (argument 1) must be a string`,
`the user refused`, or the failure Spotify or the app reported. The right check comes first
(`"library.write is not in this extension's manifest (actions)"`, `"the user did not grant
library.write"`). No Spotify account in the host (the desktop runner without `--cdp`): the call
throws.

**Limits:** 60 Spotify calls per minute (`"more than 60 Spotify calls in a minute"`), an answer over
2 MB is an error, and the call must finish inside the time limit of the hook (section 7).

**Confirmation.** A call marked "asks" in the tables does not run until the user says yes. They
see: `<extension name> wants to <what>.` followed by the first 300 characters of your arguments. If
they refuse, or do not answer within 120 seconds, the call throws `spotify.<method>: the user
refused`; catch it. The time the user takes does not count against your time limit. A host
without a screen refuses. Confirmed calls are counted in the per-minute limit only once allowed.

Which calls ask: everything that removes or clears something (the tables), plus:

- `raw.gql`: only when the operation is a mutation. An operation the app does not know counts as a
  mutation. A query does not ask.
- `raw.service`: always (a page service call can change the account in ways nothing can describe).
- `raw.request`: when the method is not `GET` or `HEAD`, whatever the address, unless the user chose Always allow (below).
- `raw.get` (a GET on a Spotify address, with the account's token; other hosts are refused) and
  `raw.operations()` never ask.
- `podcasts.setEpisodeSaved(uri, saved)` asks only when `saved` is `false`.

Everything else runs without asking: the user already accepted the action.

**Declared writes.** The `raw.request` writes an extension always needs are listed under its
`spotify.raw` action, as `"METHOD https://host/path"` (8 at most):

```json
{ "name": "spotify.raw", "why": "Sends your listens to Spotify",
  "writes": ["POST https://spclient.wg.spotify.com/gabo-receiver-service/v3/events/"] }
```

They are shown before install under the action and allowed with it, as the grants below: they
never ask while music plays. An update that declares a new one waits for the user's OK.

**Always allow.** For a `raw.request` write (POST, PUT, PATCH, DELETE) the dialog offers **Allow once**,
**Always allow** and **Refuse**. Always allow keeps a grant for this extension only. The grant is the
method, the host and a path pattern. In the path, a segment that names one thing becomes `*`, and
matches exactly one segment. A segment names one thing when it contains a digit and is 8 characters
or longer, or when it is all digits, or when it contains `:` (an id, a number, a uri). The query is
ignored. Example: `PUT https://gue1-spclient.spotify.com/playlist/v2/playlist/*/changes`. Covered
writes then run without asking. Grants are listed on the extension's page under its actions
("Always allowed"), and each one can be revoked. They are kept across updates and removed on
uninstall. Other destructive methods (`raw.gql` mutations, `raw.service`, typed removals) are still
asked every time.

### 6.1 Methods

"Right" is the action needed: `catalog` = `spotify.catalog`; `library.read` = `library.read`,
except `player.state`, `player.queue`, `player.volume`, `player.media` = `playback.events`; `write` = by area:
`player` and `devices` = `playback.control` (addToQueue, playNext, moveInQueue, removeFromQueue,
clearQueue = `playback.queue`), `playlists` and `blends` = `playlists.write`, everything else
(`library`, `users`, `extras`, `podcasts`, `search`) = `library.write`; `raw` = `spotify.raw`. "Asks the user": empty means it runs directly. `?` after a
type means optional/nullable. Returns of "—" mean `null`.

#### `player`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `state()` | PlayerState? | library.read |  |
| `queue()` | Queue? | library.read |  |
| `volume()` | number | library.read |  |
| `media(trackUri: string)` | Media? | playback.events |  |
| `play(contextUri: string, trackUri: string?, index: number?, positionMs: number?, order: string[]?)` | — | write |  |
| `pause()` | — | write |  |
| `resume()` | — | write |  |
| `seek(positionMs: number)` | — | write |  |
| `next()` | — | write |  |
| `previous()` | — | write |  |
| `setShuffle(on: bool)` | — | write |  |
| `setRepeat(mode: Repeat = 'off')` | — | write |  |
| `addToQueue(uris: string[])` | — | write |  |
| `playNext(uris: string[])` | — | write |  |
| `removeFromQueue(items: {uri,uid?}[])` | — | write | yes |
| `clearQueue()` | — | write | yes |
| `skipTo(item: {uri,uid?})` | — | write |  |
| `moveInQueue(item: {uri,uid?}, target: {uri,uid?}, after = false)` | — | write |  |
| `setVolume(level: number)` | — | write |  |
| `setLocalMute(on: boolean)` | `playback.mirror` (critical) | write | Keeps Spotify playing unheard on this phone (`myspoti.features` has `player.localMute` only when the user granted `playback.mirror`). It can keep Spotify playing silently and counts listens on the user's Spotify account, so the user ticks the accept-risk box. Host rules, whatever the script does: honoured only while Myspoti's own local / extension player is playing; one extension at a time; at most one enable per 20 s; cleared by itself on track change, local pause or stop, the local track's duration, and when the extension is disabled, uninstalled, blocked or crashes. Refused while Myspoti's own Connect mirror (Settings, "Count extension plays on Spotify", on by default) plays the song on the page itself, and an extension's mirror ends when that one starts. Both players show "Spotify mirrored by <extension>", and the Activity log and Logbook record each enable and clear. Existing installs do not have it until the user accepts the action through the update / consent flow. |
| `setSpeed(speed: number)` | — | write |  |

`media(trackUri)` (also `myspoti.spotify.media(trackUri)`) returns the media manifest Spotify sent the
player for that song: `{ uri, manifest }`, exactly as sent. It has the formats and `file_id`s, for
example `manifest.file_ids_mp4[0].file_id`. It returns `null` when the player was not sent that song.
Only songs queued in the player count, and the last 200 are kept. `trackUri` must be a `spotify:track:`
uri. It never asks and changes nothing.

#### `search`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `all(query: string, limit = 10)` | SearchResults | catalog |  |
| `tracks(query: string, offset = 0, limit = 30)` | Page<Track> | catalog |  |
| `artists(query: string, offset = 0, limit = 30)` | Page<Artist> | catalog |  |
| `albums(query: string, offset = 0, limit = 30)` | Page<Album> | catalog |  |
| `playlists(query: string, offset = 0, limit = 30)` | Page<Playlist> | catalog |  |
| `shows(query: string, offset = 0, limit = 30)` | Page<MediaCard> | catalog |  |
| `episodes(query: string, offset = 0, limit = 30)` | Page<Episode> | catalog |  |
| `audiobooks(query: string, offset = 0, limit = 30)` | Page<MediaCard> | catalog |  |
| `users(query: string, offset = 0, limit = 30)` | Page<MediaCard> | catalog |  |
| `genres(query: string, offset = 0, limit = 30)` | Page<MediaCard> | catalog |  |
| `suggestions(query: string, limit = 10)` | string[] | catalog |  |
| `recent()` | MediaCard[] | library.read |  |
| `clearRecent()` | — | write | yes |

#### `catalog`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `track(uri: string)` | Track | catalog |  |
| `album(uri: string)` | Album | catalog |  |
| `albumTracks(uri: string, offset = 0, limit = 50)` | Page<Track> | catalog |  |
| `artist(uri: string)` | Artist | catalog |  |
| `artistTopTracks(uri: string)` | Track[] | catalog |  |
| `artistPage(uri: string)` | ArtistPage | catalog |  |
| `isrc(trackUri: string)` | string? | catalog |  |
| `discography(uri: string, offset = 0, limit = 50)` | Page<Album> | catalog |  |
| `relatedArtists(uri: string)` | Artist[] | catalog |  |
| `appearsOn(uri: string)` | Album[] | catalog |  |
| `similarAlbums(trackUri: string, limit = 20)` | Album[] | catalog |  |

#### `library`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `contains(uris: string[])` | bool[] | library.read |  |
| `items(filter: LibraryFilter = 'all', sort: LibrarySort = 'recents', folderUri: string?, offset = 0, limit = 50)` | Page<LibraryItem> | library.read |  |
| `likedTracks(offset = 0, limit = 50)` | Page<SavedTrack> | library.read |  |
| `add(uris: string[])` | — | write |  |
| `remove(uris: string[])` | — | write | yes |
| `pin(uri: string)` | — | write |  |
| `unpin(uri: string)` | — | write |  |
| `createFolder(name: string, parentFolderUri: string?)` | string | write |  |
| `renameFolder(folderUri: string, name: string)` | — | write |  |
| `deleteFolder(folderUri: string)` | — | write | yes |
| `moveToFolder(uri: string, folderUri: string?)` | — | write |  |

#### `playlists`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `playlist(uri: string)` | Playlist | catalog |  |
| `tracks(uri: string, offset = 0, limit = 100)` | Page<Track> | catalog |  |
| `sortedTracks(uri: string, sort: PlaylistSort = 'custom', descending = false)` | Track[] | catalog |  |
| `recommended(uri: string, limit = 10, skip: string[] = [])` | Track[] | catalog |  |
| `mine()` | Playlist[] | library.read |  |
| `editable()` | Playlist[] | library.read |  |
| `access(uri: string)` | PlaylistAccess | library.read |  |
| `published(uri: string)` | bool | library.read |  |
| `members(uri: string)` | PlaylistMember[] | library.read |  |
| `addedBy(uri: string)` | RowAdder[] | library.read |  |
| `create(name: string, folderUri: string?)` | string | write |  |
| `rename(uri: string, name: string)` | — | write |  |
| `setDescription(uri: string, description: string)` | — | write |  |
| `setAccess(uri: string, access: PlaylistAccess = 'public')` | — | write |  |
| `setPublished(uri: string, published: bool)` | — | write |  |
| `setPublic(uri: string, public: bool)` | — | write |  |
| `setCollaborative(uri: string, collaborative: bool)` | — | write |  |
| `setCover(uri: string, jpegBase64: string?)` | — | write |  |
| `delete(uri: string)` | — | write | yes |
| `follow(uri: string)` | — | write |  |
| `unfollow(uri: string)` | — | write | yes |
| `join(uri: string, invitationToken: string)` | PlaylistAccess | write |  |
| `collaboratorInvite(uri: string)` | string | write |  |
| `addTracks(uri: string, trackUris: string[])` | — | write |  |
| `removeTracks(uri: string, uids: string[])` | — | write | yes |
| `moveTracks(uri: string, uids: string[], beforeUid: string?)` | — | write |  |

#### `home`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `feed(filter: HomeFilter = 'all', itemsPerShelf = 10)` | HomeFeed | catalog |  |
| `categories()` | Category[] | catalog |  |
| `category(uri: string)` | BrowsePage | catalog |  |
| `whatsNew(offset = 0, limit = 50)` | Page<Album> | library.read |  |

#### `podcasts`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `show(uri: string)` | Show | catalog |  |
| `episodes(uri: string, offset = 0, limit = 50)` | Page<Episode> | catalog |  |
| `episode(uri: string)` | Episode | catalog |  |
| `audioUrl(uri: string)` | EpisodeAudio | catalog |  |
| `audiobook(uri: string)` | Audiobook | catalog |  |
| `chapters(uri: string, offset = 0, limit = 50)` | Page<Episode> | catalog |  |
| `savedEpisodes(offset = 0, limit = 50)` | Page<Episode> | library.read |  |
| `setEpisodeSaved(uri: string, saved: bool)` | — | write | only when `saved` is false |

#### `users`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `me()` | Account | library.read |  |
| `profile(userUri: string)` | UserProfile | library.read |  |
| `followers(userUri: string)` | UserRef[] | library.read |  |
| `following(userUri: string)` | UserRef[] | library.read |  |
| `top(range: TimeRange = 'short_term', limit = 10)` | TopContent | library.read |  |
| `isFollowing(userUris: string[])` | bool[] | library.read |  |
| `friendActivity()` | FriendActivity[] | library.read |  |
| `history()` | HistoryEntry[] | library.read |  |
| `follow(userUris: string[])` | — | write |  |
| `unfollow(userUris: string[])` | — | write | yes |
| `setPrivateSession(on: bool)` | — | write |  |

#### `extras`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `lyrics(trackUri: string)` | Lyrics? | catalog |  |
| `canvas(trackUri: string)` | Canvas? | catalog |  |
| `credits(trackUri: string)` | Credits | catalog |  |
| `radio(seedUri: string)` | string? | catalog |  |
| `isExcludedFromTaste(trackUris: string[])` | bool[] | library.read |  |
| `isArtistBanned(artistUris: string[])` | bool[] | library.read |  |
| `setExcludedFromTaste(trackUri: string, excluded: bool)` | — | write |  |
| `setArtistBanned(artistUri: string, banned: bool)` | — | write |  |

#### `devices`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `list()` | Device[] | library.read |  |
| `transfer(deviceId: string, play = true)` | — | write |  |
| `pullHere()` | — | write |  |

#### `recents`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `recentEntries(offset = 0, limit = 50)` | Page<RecentEntry> | library.read |  |

#### `blends`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `invitation(linkOrId: string)` | BlendInvitation | library.read |  |
| `group(playlist: string)` | BlendGroup | library.read |  |
| `story(playlist: string)` | BlendStory? | library.read |  |
| `invite()` | BlendInvite | write |  |
| `join(linkOrId: string)` | string | write |  |
| `rename(playlist: string, name: string)` | — | write |  |
| `leave(playlist: string)` | — | write | yes |

#### `raw`

| Call | Returns | Right | Asks the user |
|---|---|---|---|
| `operations()` | string[] | raw |  |
| `gql(operation: string, variables: object)` | JSON | raw | only if the operation is a mutation |
| `service(service: string, method: string)` | JSON | raw | always |
| `get(url: string)` | JSON or null | raw |  |
| `request(method: string, url: string, body?: string\|object, headers?: object, options?: { encoding })` | `{ status, headers, body }` | raw | if the method is not GET or HEAD |

`raw.request` sends any method (`GET HEAD POST PUT PATCH DELETE`) to `https://*.spotify.com`
with the account's credentials, as the app's own calls (the Web Player's token, the client token,
its headers, no `X-Requested-With`), or to `https://*.spotifycdn.com` with none. Any other host or
plain http throws. A `body` that is not a string is sent as JSON; `Content-Type` defaults to JSON.
A binary body (a protobuf, say) is given as base64 with `{ encoding: 'base64' }` as the fifth
argument: its bytes are sent, and a body that is not base64 throws before anything leaves
(`myspoti.features` lists `raw.base64` where the app can do this).
Your headers cannot replace the credentials: `authorization`, `client-token`, `cookie`, `origin`,
`referer`, `app-platform`, `spotify-app-version`, `x-requested-with` and the transport's own are
dropped. A 4xx or 5xx is returned, not thrown; `body` is text, header names are lower case.

### 6.2 Result shapes

Models as plain JSON objects (`string?` = string or `null`; `T[]` = array). `Page<T>` is
`{ items: T[], offset, total, nextOffset: number? }`; `nextOffset` is `null` on the last page.
Enums come as their upper-case names.

```
Image { url: string, width: number?, height: number? }
ArtistRef { uri: string, name: string }
AlbumRef { uri: string, name: string, images: Image[] }
Track { uri: string, name: string, durationMs: number, artists: ArtistRef[], album: AlbumRef?, explicit: bool, playable: bool, uid: string?, addedAt: string?, hasVideo: bool }
Album { uri: string, name: string, artists: ArtistRef[], images: Image[], type: string, releaseDate: string?, trackCount: number, label: string? }
Artist { uri: string, name: string, images: Image[], followers: number?, monthlyListeners: number?, verified: bool }
Playlist { uri: string, name: string, description: string?, owner: string?, images: Image[], trackCount: number, editable: bool, ownerUri: string?, ownerImages: Image[], followers: number?, isBlend: bool }
SearchResults { tracks: Page<Track>, artists: Page<Artist>, albums: Page<Album>, playlists: Page<Playlist> }
NowPlaying { kind: ItemKind, uri: string, uid: string?, name: string, artists: ArtistRef[], album: AlbumRef?, imageUrl: string?, durationMs: number, explicit: bool, provider: string?, mediaType: string? }
PlayerRestrictions { canPause: bool, canResume: bool, canSeek: bool, canSkipNext: bool, canSkipPrevious: bool }
PlayerState { item: NowPlaying?, contextUri: string?, contextName: string?, previous: NowPlaying?, next: NowPlaying?, isPaused: bool, isBuffering: bool, positionMs: number, timestampMs: number, speed: number, shuffle: bool, repeat: Repeat, restrictions: PlayerRestrictions }
Queue { current: NowPlaying?, queued: NowPlaying[], nextUp: NowPlaying[] }
Media { uri: string, manifest: object }
TopTrack { track: Track, playcount: number? }
ArtistPage { artist: Artist, biography: string?, headerImages: Image[], topTracks: TopTrack[], popularReleases: Album[], appearsOn: Album[], related: Artist[] }
BlendInvite { id: string, link: string }
BlendMember { username: string, name: string, imageUrl: string? }
RowAdder { rowUid: string, trackUri: string, username: string }
PlaylistMember { username: string, name: string, imageUrl: string?, isOwner: bool }
BlendGroup { title: string?, inviteLabel: string?, link: string?, members: BlendMember[] }
BlendStory { title: string, subtitle: string?, detail: string?, background: string?, titleColor: string?, textColor: string?, image: string? }
BlendInvitation { state: BlendInvitationState, title: string?, subtitle: string?, inviter: BlendMember?, members: BlendMember[], playlist: string? }
MediaCard { uri: string, kind: MediaKind, title: string, subtitle: string?, images: Image[] }
RecentItem { uri: string, kind: MediaKind, title: string, subtitle: string?, images: Image[], durationMs: number, explicit: bool, playedAtMs: number?, typeLabel: string? }
Shelf { uri: string?, title: string?, items: MediaCard[], total: number }
HomeFeed { greeting: string, filters: HomeFilter[], shelves: Shelf[] }
Category { uri: string, title: string, images: Image[], color: string? }
BrowsePage { uri: string, title: string, shelves: Shelf[] }
LibraryItem { uri: string, kind: MediaKind, name: string, subtitle: string?, images: Image[], pinned: bool, addedAt: string?, count: number? }
SavedTrack { track: Track, addedAt: string? }
Account { uri: string, username: string, name: string, images: Image[], premium: bool, country: string? }
UserProfile { uri: string, name: string, images: Image[], followers: number?, following: number?, publicPlaylists: Playlist[] }
TopContent { artists: Artist[], tracks: Track[] }
FriendActivity { user: UserRef, track: Track?, contextUri: string?, timestampMs: number }
UserRef { uri: string, name: string, imageUrl: string? }
Show { uri: string, name: string, publisher: string, description: string?, images: Image[], saved: bool, rating: number?, topics: string[] }
Episode { uri: string, name: string, description: string?, durationMs: number, releaseDate: string?, images: Image[], show: AlbumRef?, playedPositionMs: number, played: bool, explicit: bool, playable: bool, previewUrl: string? }
EpisodeAudio { url: string?, passthrough: string, format: string }
Audiobook { uri: string, name: string, authors: string[], narrators: string[], description: string?, images: Image[], chapterCount: number }
LyricsLine { startMs: number, endMs: number?, text: string, words: LyricsWord[]? }
LyricsWord { startMs: number, text: string }
Lyrics { lines: LyricsLine[], synced: bool, language: string?, provider: string?, background: number?, text: number?, highlight: number? }
Canvas { uri: string, videoUrl: string, type: string }
Credits { roles: CreditRole[] }
CreditRole { role: string, artists: ArtistRef[] }
Device { id: string, name: string, type: string, active: bool, volume: number?, local: bool }
HistoryEntry { uri: string, kind: MediaKind, name: string, images: Image[], playedAtMs: number? }
RecentEntry { item: RecentItem, childrenGroup: string?, group: string?, groupCount: number?, saved: bool, music: bool }
```

Enums: `Repeat` OFF CONTEXT TRACK. `ItemKind` TRACK EPISODE AD. `MediaKind` TRACK ALBUM ARTIST
PLAYLIST SHOW EPISODE AUDIOBOOK CHAPTER USER GENRE COLLECTION FOLDER UNKNOWN. `PlaylistAccess`
PUBLIC PRIVATE COLLABORATIVE. `PlaylistSort` CUSTOM TITLE ARTIST ALBUM ADDED_AT DURATION.
`HomeFilter` ALL MUSIC PODCASTS AUDIOBOOKS. `LibraryFilter` ALL PLAYLISTS ARTISTS ALBUMS PODCASTS
AUDIOBOOKS. `LibrarySort` RECENTS RECENTLY_ADDED ALPHABETICAL CREATOR CUSTOM. `TimeRange` SHORT_TERM
MEDIUM_TERM LONG_TERM. `BlendInvitationState` WAITING JOINABLE FULL JOINED DELETED UNKNOWN.

Notes: `extras.lyrics` returns Spotify's own `Lyrics` shape (`startMs`), not the hook's shape
(`time`). `PlayerState.positionMs` is the position at `timestampMs` (epoch ms); add
`(now - timestampMs) * speed` for a live value while not paused. `raw.*` return Spotify's JSON as it
is. `playlists.setCover` takes a JPEG as base64.

## 7. Limits

| What | Limit |
|---|---|
| Loading the script (its top level; compiling it is not counted) | 3 s |
| A hook call | One table for the app and the desktop runner (`HookBudgets`): 8 s for `lyrics`, 15 s for `audio-source`, 30 s for `download-source`, 3 s for blocks hooks, `tap` and `message`, 5 s for `page` and `status` |
| A `track` or `playback` event | 3 s |
| A `setting` or `online` event | 30 s |
| An `auth` event | 10 s |
| Script work | Checked every 5000 instructions against the call's time; a loop cannot hold the thread. A call that runs over is abandoned, and it cannot be caught by `try/catch`. The next call waits behind it on the extension's own thread, never on the app's. |
| Recursion | 800 interpreter frames |
| Time spent waiting for the user to confirm | Not counted |
| `myspoti.http` | 120 requests/min, 5 MB per response, 20 s per request, https only; `http.all`: 4 at once |
| `myspoti.spotify` | 60 calls/min, 256 KB of arguments, 1000 items per list, 2 MB per answer |
| `myspoti.attest` | `sign` 120/min and 64 KB of text; `enroll` 5/hour and a challenge of 128 bytes |
| Storage | 512 KB (keys and values together) |
| Sign-in page | One at a time |
| `myspoti.work.whenOnline()` | 30 s |
| Lyrics | 3000 lines, 500 characters per line and per word, 200 words per line |
| Audio and download headers | 20 |
| Download files | 10 per answer, a `dash-flac` manifest 1 MB |
| `notify` / `log` | 200 / 2000 characters |
| Package | 2 MB zip, 30 files, 1 MB script, 256 KB icon |

## 8. Testing on a computer

`myspoti-ext` runs an extension the way the app would, without a phone. From `base/`:

```
./gradlew :extensions-cli:installDist
extensions-cli/build/install/myspoti-ext/bin/myspoti-ext <command> ...
```

(`./gradlew :extensions-cli:run --args="..."` works too, but may not pass your terminal's input
to the confirmation prompt below. Give absolute paths.)

| Command | Does |
|---|---|
| `check <folder\|zip>` | Package validity only: prints the manifest's problems (`✗ ...`, e.g. a missing or too long `why`), says when it is an old manifest, loads the script, prints the hooks it provides and each action as the user sees it (icon name, label, level, why). It never calls a hook. Exit 1 on a problem. |
| `test <folder\|zip> --scenario file.json [--state file.json]` | Runs a scenario (below): hook calls, events and page messages in order with the app's time limits, requests answered or broken by rules, storage kept across steps. Prints each step (`✓`/`✗`, outcome, time); exit 1 when a step failed. |
| `run <folder\|zip> lyrics --title T --artist A [options]` | Calls the hook and prints what it answered, parsed the way the app parses it (lines, `synced`, `word by word`, or the audio URL and headers). |
| `run <folder\|zip> audio-source ...` | Same for the audio hook. |
| `run <folder\|zip> download-source ... [--quality hi-res\|cd\|small]` | Same for the download hook: prints the files, best first, as the app reads them (default quality `cd`). |
| `run <folder\|zip> audio-source ... [--ahead true]` | Asks for a song's audio (with `ahead: true` when given) and prints the answer as the app reads it: address or manifest, `expiresInMs`, quality. |
| `run <folder\|zip> home\|search\|menu\|player\|trackinfo ...` | Calls a blocks hook (`--query Q` for search, `--kind track\|album\|playlist\|artist` for menu) and prints the blocks as the app reads them. |
| `run <folder\|zip> page\|message\|tap ...` | The page hook (prints title and HTML), a page message (`--data JSON`), a tap (`--action ID`). |
| `run <folder\|zip> status --title T` | Asks `status` as the app does (5 s) and prints live, or inactive and why. |
| `run <folder\|zip> track ...` | Delivers the `track` event. |
| `run <folder\|zip> playback ... [--played MS] [--leave skip\|end\|stop]` | Delivers a play of the song as `playback` events, at once, with the times they would have had: `start`, a `progress` every 10 s up to `--played` (default 40000), then `skip` (forward), `end` (finished) or `end` (stop). Also `--context URI`, `--shuffle true`, `--source local\|spotify`, `--origin spotify\|download\|audio-source\|local-file` and `--provider ID` (also for `track`). |
| `pack <folder> [out-folder]` | Checks, builds `<id>-<version>.zip`, prints its sha256 and its index entry. |
| `index <folder-of-zips> <name>` | Writes `index.json` for a repository (newest version of each extension; keeps the existing `blocked` list). |

`run` options: `--title T` (required), `--artist "A, B"` (comma-separated; the first one is
`artist`), `--album B`, `--duration MS`, `--id SPOTIFY_ID` (also sets `uri`), `--isrc ISRC`,
`--set key=value` (a setting; repeat; `true`, `false` and numbers are passed as such, anything else
as text; unset ones use the manifest `default`).

What the runner does: every right the manifest asks is granted. Each hook and event gets the app's
own time limit (section 7). An answer `{ outcome }` is printed as the app reads it. Storage is in
memory for the run (`test --state` keeps it in a file).
`myspoti.attest` works with a software key kept between runs (section 10).
`log`, `console`, `notify` and failures go to stderr as `[id] message`. `player` controls print
`player: <action>` and answer `true`. `myspoti.player.current()` returns the `--title` track.
`http` is real (same host rules as the app).

**Spotify.** Without `--cdp`, every `myspoti.spotify` call throws, so you can test lyrics and audio
code but not Spotify code. With `--cdp PORT` (or `--cdp http://host:PORT`) the calls run against
the account signed in to the Web Player of a Chrome you started yourself:

```
google-chrome --user-data-dir=$HOME/.myspoti-chrome --remote-debugging-port=9222   # sign in to open.spotify.com once
myspoti-ext run ./my-ext lyrics --title "Papaoutai" --artist Stromae --cdp 9222
```

The runner reloads that Web Player page, waits (60 s at most) for it, and stops if nobody is signed
in. These are real calls on a real account. A call that asks for confirmation (section 6) prints
the question on stderr and reads one line from stdin:

```
[io.github.you.x] My Ext wants to remove from the library.
["spotify:track:1"]
Allow it? [y/N]
```

`y` or `yes` allows; anything else, or end of input, refuses (so `echo y | myspoti-ext ...`
allows every question, a script run with no input refuses them all).

**Limits of `check`.** It never calls a hook or an event and has no account, no current track and
no network of its own: it only reads the manifest and runs the script's top level once with all
rights granted. A bug inside `provide('lyrics', ...)`, a wrong response shape, a missing host,
or a `myspoti.spotify` call fail only under `run`. A top-level call to `myspoti.spotify` fails in
`check`. `run ... track` prints `delivered` even if the listener threw: read the `failed: ...` line
on stderr.

### 8.1 Scenarios (`test`)

A scenario is a JSON file:

```json
{
  "offline": true,
  "settings": { "region": "eu" },
  "track": { "title": "Papaoutai", "artist": "Stromae", "isrc": "BEUM71300139" },
  "http": [ { "match": "api.example.com/find", "body": { "url": "https://cdn.example.com/a.flac" } } ],
  "steps": [
    { "hook": "audio-source", "expect": { "outcome": "found", "maxMs": 2000 } },
    { "http": [ { "match": "api.example.com", "status": 429, "retryAfter": 30 } ] },
    { "hook": "audio-source", "expect": { "outcome": "rate-limited", "retryAfterMs": 30000 } },
    { "http": [ { "match": "*", "latencyMs": 20000 } ] },
    { "hook": "audio-source", "cancelAtMs": 500, "expect": { "outcome": "cancelled" } },
    { "event": "online" },
    { "message": { "type": "hello" }, "expect": { "posted": 1, "contains": "hi" } },
    { "storage": { "lastIsrc": "BEUM71300139" } }
  ]
}
```

- **`http` rules** (at the top, or as a step that replaces them from then on): a request whose URL
  contains `match` (`*`: all) waits `latencyMs` (past the request's timeout: a timeout), then fails
  with `dns: true` (no such host) or `reset: true` (connection reset), or answers `status` (200 by
  default; 429 when only `retryAfter` is given, in seconds, sent as `Retry-After`), `headers` and
  `body` (text, or JSON written as text). `times: n` applies a rule to n requests only. Requests no
  rule matches go to the network, or fail as a DNS error with `"offline": true`.
- **Steps**: `{ hook, input?, track?, quality?, ahead?, data?, action?, query?, cancelAtMs? }` calls
  a hook (the input is built as the app builds it unless `input` is given); `cancelAtMs` ends the
  call at that time (outcome `cancelled`). `{ event, input? }` delivers an event. `{ message }` is
  your page posting to you; what you answer or post back is the page's. `{ wait: ms }` waits.
  `{ storage: { key: value \| null } }` checks your storage.
- **`expect`**: `outcome` (`found`, `not-found`, `sign-in`, `rate-limited`, `quality-unavailable`,
  `server-down`, `network`, `bug`, `skipped`, `cancelled`; `live`/`inactive`/`unknown` for `status`),
  `maxMs`, `minMs`, `retryAfterMs`, `contains` (a text the answer contains), `posted` (messages posted
  to the page). A hook call over the app's time limit fails too.
- **`--state file.json`**: your storage is read from it before the run and written back after, so
  a run can start where the last one ended (a token, a cache).

## 9. GitHub: install, updates, store, publishing

### 9.1 What the app does with a repository

**Install.** From the store (a discovered repository, or an index entry with `repo`) or by pasting
`https://github.com/you/name` in "Install from an address", the app reads the newest release
(GitHub's `releases/latest` redirect, not its API): its attached `extension.zip` when there is one,
else the release tag's archive; without a release, the default branch's archive. An archive is cut
down to a package: the files at the repository's root that a package holds (`manifest.json`, the
`main` script, the icon, `LICENSE`, `README.md`); nothing else of the repository is kept. Everything
comes from `github.com`, `codeload.github.com` and `raw.githubusercontent.com`, which have no rate
limit for this. The complete package is kept on the phone: the extension runs offline and keeps
running if the repository is deleted (its page then says "Source gone"). The app records the
repository, the tag or branch, the commit (GitHub writes it in the archive), the package's sha256,
and a snapshot of what the user accepted: actions with their `why`, network hosts, exclusive roles,
`replaces` and `conflictsWith`, author, repository and id.

**Updates.** Once a day per extension (and on "Check for updates" on its page), the app reads the
newest `manifest.json`; a higher `versionCode` is downloaded to a staging folder and compared with
the accepted snapshot (never with the version running, so dropping an action in one version and
adding it back in the next is still seen). It is applied by itself only when it asks nothing new.
Anything of the following holds it back, the old version running meanwhile, with an **"Update
needs your OK"** card listing only what changes, each with its icon and reason, the same single
box as the install prompt, and **Accept** (enabled once the box is ticked) / **Keep current**:

- a new action (critical or not, `webview.page` included), or a new network host (`*` is critical);
- an exclusive role it did not have;
- any change to a `why`, even a small one (the user judges the meaning, not the app);
- another author, repository or id;
- a new `replaces` or `conflictsWith` entry.

"Keep current" is remembered for that version; a newer one is weighed again. The previous version
stays on the phone: **Revert to X** on the extension's page brings it back in one tap (and the
version left is not reapplied by itself).

**Kill switch.** Once a day the app reads `blocked.json` from `kota-zo/myspoti-extensions` (a
missing file is an empty list): `{ "blocked": [{ "id": "…", "sha256": ["…"], "reason": "…" }],
"flagged": [{ "id": "…", "reason": "…" }] }`. A blocked id (every package, or the hashes listed) is
turned off with a notice and cannot be turned on, installed or reverted to; a flagged one (reported
and confirmed) is hidden from the store.

### 9.2 In the store

The store searches GitHub once per visit (cached 6 hours) for public repositories with the topic
`myspoti-extension`, up to 100, and reads each one's `manifest.json` on the default branch (a
missing or refused manifest leaves the repository out). They are listed under **On GitHub**, by
score, then the most recently updated.

**Ratings** are the 👍 and 👎 on one issue in your repository: open an issue titled `Ratings`,
labelled `myspoti-ratings`, from the repository owner's account (an issue opened by anyone else
does not count). The score is a Bayesian average (5 votes at 70 % are added to yours, shown as 1 to
5 stars), so a few votes do not outrank many. Reading them needs no account; voting from the app
signs in with GitHub (device code, no password in the app) once that is enabled. Downloads are the
`download_count` of the release's `extension.zip`, shown on the store page.

Hidden behind **Show all**: blocked or flagged extensions, a score under 0.4 with 10 votes or more,
and repositories not updated for 18 months. Every store page has **Report**: it opens a prefilled
report form (`report-extension.yml`) on `kota-zo/myspoti-extensions` in the browser (the extension's id, version, repository and
sha256; nothing about the user).

### 9.3 Publish in 5 minutes

Nobody approves an extension: you publish it in your own GitHub repository, and the store finds it
by its topic. Myspoti does not host, review or endorse third-party extensions; read the
[policy](https://github.com/kota-zo/myspoti-extensions/blob/main/POLICY.md) (no DRM circumvention,
piracy, credential theft, fake plays, malware) before you publish.

1. **Start from the template**, `github.com/kota-zo/myspoti-extension-template` (to be published):
   "Use this template" creates a public repository with this layout, everything at the root:

   ```
   manifest.json     id, name, version, versionCode, author, actions (each with its why), category, tags
   main.js           the script
   icon.png          optional, square, 256 px or less
   README.md         optional, shown on GitHub and kept in the package
   LICENSE           optional, kept in the package
   scripts/pack.py   builds extension.zip (the template's workflow runs it)
   .github/workflows/release.yml   on a vX.Y.Z tag: builds extension.zip and attaches it to a release
   ```

   Other files and folders (screenshots, sources, workflows) can live there too: the app keeps only
   the package files above.
2. Rename the `id` (it never changes afterwards), edit `manifest.json` and `main.js`, and check it:
   `myspoti-ext check .` (section 8) must print no problem.
3. Add the topic **`myspoti-extension`** to the repository (About, Topics). The store finds it
   within 6 hours.
4. Release: raise `version` and `versionCode`, tag (`git tag v1.0.0 && git push origin v1.0.0`).
   The template's workflow checks that the tag matches `version`, builds the deterministic
   `extension.zip` and attaches it to a GitHub release (by hand: `myspoti-ext pack .`'s zip renamed
   **`extension.zip`**; without it, the tag's archive is used). Every phone gets it within a day, by
   itself if it asks nothing new.
5. Optional: open the `Ratings` issue with the `myspoti-ratings` label, from your own account
   (`gh label create myspoti-ratings && gh issue create --title Ratings --label myspoti-ratings`).

Changing an action, a host or a `why` in an update is fine: users are asked once, with your new
words. Keep `id` and the repository: a changed one is shown as a different source.

**Reports and takedowns.** The Report button on a store page opens the report form of
`kota-zo/myspoti-extensions` (prefilled with the id, version, source and sha256); reports, legal
notices and counter-notices can also go to myspoti.app@gmail.com. On a valid report the owner adds
the extension to `blocked.json` (turned off on every phone, with the reason) or `flagged` (hidden
from the store); see `POLICY.md` and `REPORTING.md` in that repository. The same repository can also
list "verified" extensions in its `index.json` (optional, the owner's choice; the app shows it in the
store only when it lists at least one).


### `raw.request` "Always allow" is per operation

A write the user allows for good is a grant on method + host + path pattern. On a multiplexed endpoint (GraphQL / pathfinder, or any request whose JSON body carries `operationName` or a persisted-query `sha256Hash`) the grant also names that operation: it never covers another one. If no operation can be determined from the body on such an endpoint, "Always allow" is not offered and the user is asked each time. The consent shows the real operation, and the revocation list shows it too (`POST https://host/path [operation name]`). Grants stored before this change on such endpoints without an operation were dropped (the user is asked again). Writes declared in the manifest (`writes`) on a multiplexed path cannot cover operations; the user is asked.

## 10. Prove a request comes from a genuine install (`attest`)

Optional and generic: any extension that talks to **its author's own server** can use it, so that
the server can tell a request comes from a Myspoti install running that extension, and not from a
script that pulled a key out of the zip (there is nothing secret in a zip: anyone can read it).
Action `attest` (normal, "Prove this is Myspoti"): the user sees it at install like the others.
Without it, every `myspoti.attest` call throws `"attest is not in this extension's manifest
(actions)"`. No key exists until the extension calls `enroll`. The app makes no request of its own
for this: your script sends what it gets to your server.

| Call | Does |
|---|---|
| `myspoti.attest.enroll(challenge)` | `challenge`: a string from your server, 1 to 128 bytes (UTF-8). Creates, or replaces, an EC P-256 signing key for **this extension** in the Android hardware keystore (StrongBox when the phone has one, else the TEE) with `challenge` as the attestation challenge. Returns `{ publicKey, certChain }`: `publicKey` is the base64 SPKI (DER), `certChain` the base64 DER certificates of the Android key attestation, leaf first. At most 5 an hour. Throws if the phone cannot make such a key. |
| `myspoti.attest.publicKey()` | The current key's base64 SPKI, or `null` before `enroll`. |
| `myspoti.attest.sign(text)` | The base64 DER ECDSA-SHA256 signature of the UTF-8 `text` (64 KB at most), made with this extension's key. Throws `"attest.sign: not enrolled ..."` before `enroll`. 120 a minute. |
| `myspoti.attest.reset()` | Deletes this extension's key. Uninstalling the extension does it too. |

`myspoti.features` has `attest` when the user granted it. The key's alias holds the extension's id
(`myspoti.ext.attest.<id>`), so an extension can only ever use its own key, never another's. The key
cannot be exported: it signs only, and only for the extension that owns it, only while the app runs
that extension. No device identifier (serial number, IMEI) is requested: the chain says what app
made the key and how the phone's keystore and boot state look.

### What it proves, and what it does not

It proves that the key lives in Android hardware (the attestation's security level is
StrongBox or TrustedEnvironment, never Software), that **the app package made it** (the attestation
extension, `attestationApplicationId`, holds the package name and the SHA-256 digest of the
certificate that signed the app: `app.myspoti` or `app.myspoti.beta`, signed by the key you pin),
and that your server asked for it (the challenge). Every later signature then comes from that key.

It does **not** prove that the build is untouched: on a rooted phone, or with a patched official
build that keeps its signature, code can still ask the app to sign things. It does not prove who
the user is, that the content of a request is true (the extension reports what it saw), or that
one phone is not signing in a loop for someone: use a per-key rate limit. It is a barrier against
scripts that copy a key out of a package and against builds signed by anyone else, not against a
determined owner of a phone.

### It is optional

For the author: your server can still serve clients that did not enrol, or serve them less; Myspoti
never requires it. For the user: it is one more line in the install prompt, the extension works
without it for everything else, nothing is sent anywhere by the app, and the key is theirs to lose
by uninstalling.

### On the server

1. **Challenge.** Give a fresh random challenge (at most 128 bytes) to each enrolment, remember it
   against the session, accept it once and for a few minutes.
2. **Verify the chain.** Parse `certChain` (leaf first), check each certificate is signed by the
   next, and that the last one is one of Google's hardware attestation roots (published in the
   Android documentation, "Verify hardware-backed key pairs with Key Attestation"). Check none is
   on Google's revocation list. Check the leaf's public key is the `publicKey` that was sent.
3. **Read the attestation.** The leaf holds the extension `1.3.6.1.4.1.11129.2.1.17` (key
   description): check `attestationChallenge` equals your challenge, `attestationSecurityLevel` is
   StrongBox or TrustedEnvironment, and in the authorization lists that the key is an EC P-256 key
   with purpose `SIGN`, with no `ALL_APPLICATIONS` and no software-only part.
4. **Check the app.** In `attestationApplicationId`: the package name is `app.myspoti` (or
   `app.myspoti.beta` if you accept the beta), and one of the signing certificate digests is the
   SHA-256 of the release certificate **you choose to pin** (`apksigner verify --print-certs` on
   the official APK). Optionally also read the root of trust (verified boot, locked bootloader).
5. **Store** the key against the account or install, with the time and the level you saw.
6. **Verify each request.** Sign a canonical string that leaves nothing to interpretation:
   method, path (with the query), a timestamp in seconds, a random nonce and the SHA-256 of the
   body, one per line. The server rebuilds it from the request it received, verifies the
   ECDSA-SHA256 DER signature with the stored key, accepts a timestamp within about 60 seconds of
   its own clock, and keeps each `(key, nonce)` for twice that window to refuse a replay. A key it
   does not know, or a signature that fails, is refused like any bad credential.

The extension's side:

```js
var API = 'https://api.example.com';

function enrol() {
  var c = myspoti.http.get(API + '/attest/challenge').json();            // { challenge }
  var e = myspoti.attest.enroll(c.challenge);
  myspoti.http.post(API + '/attest/enrol', { challenge: c.challenge, publicKey: e.publicKey, certChain: e.certChain });
}

function signedPost(path, body) {
  if (!myspoti.attest.publicKey()) enrol();
  var text = JSON.stringify(body);
  var ts = String(Math.floor(Date.now() / 1000));
  var nonce = myspoti.util.sha256(ts + Math.random() + text).slice(0, 32);
  var canonical = ['POST', path, ts, nonce, myspoti.util.sha256(text)].join('\n');
  return myspoti.http.post(API + path, text, { headers: {
    'Content-Type': 'application/json',
    'X-Key': myspoti.util.sha256(myspoti.attest.publicKey()),
    'X-Timestamp': ts,
    'X-Nonce': nonce,
    'X-Signature': myspoti.attest.sign(canonical),
  } });
}
```

(manifest: `"actions": [{ "name": "attest", "why": "Lets my server know this is a real Myspoti" }, { "name": "network", "hosts": ["api.example.com"], "why": "Talks to my server" }]`.)

On a computer, `myspoti-ext` gives each extension a **software** EC P-256 key kept in
`~/.myspoti-ext/attest/` (or under `$MYSPOTI_EXT_HOME`), and a one-certificate self-signed
"chain" that says `TEST-ONLY` and carries the challenge in an extension of a private OID. It is
there to test your signatures and your enrolment flow; a server that verifies the chain as in
step 2 must refuse it, since it is not hardware-backed and not Google's.
