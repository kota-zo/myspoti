# Driving the app over adb (`tools/myspoti-cli`)

Debug and beta builds answer a few commands sent over adb, so a test (or an agent) checks a feature
through the app's own code paths without screenshots or taps. Stable releases leave the receiver
off in their manifest, and it answers nothing outside debug and beta builds even if turned on.

```
tools/myspoti-cli [-p app.myspoti.dev] [-s serial] [-t 40] [--raw] <command> [args…]
```

Each command is one broadcast (`<package>.CLI`, held to the adb shell by the `DUMP` permission).
The app writes its JSON reply to `/sdcard/Android/data/<package>/files/cli/<request>.json` and to
logcat under `MyspotiCli`; the script reads it back, prints the `result`, and deletes the file.

Exit codes: `0` ok, `1` the command failed (the app answered an error, on stderr, or no reply in
time), `2` device unreachable (`adb get-state` is not `device`, or several devices and none named),
`3` adb failed, `4` the app is not installed (`pm path`), `5` disabled in this build (a stable build
has no CLI receiver: it leaves the broadcast's result at 0, where the receiver sets 1; `ext.why` is
debug only). The script always targets one device: `-s` or `ANDROID_SERIAL`, else the only one adb
lists; with several and none named it refuses. `ADB`, `ANDROID_SERIAL` and `MYSPOTI_PACKAGE` override
the defaults (the beta is `-p app.myspoti`).

## Commands

| Command | Does |
| --- | --- |
| `status` | Version, debug/beta, signed in or not, what plays (uri, position, duration), installed extensions, whether the screen is open. `player` says who plays: `local` (the app's own player; `source` is `audio-source`, `download` or `file`, and `extension` the id of the extension that gave the stream) or `engine` (Spotify); `routing` is true while the extensions are asked for a song; while the local player has a queue, `playWhenReady` says whether it is to play and `playbackState` whether it is `idle` (stopped by an error), `buffering`, `ready` or `ended`. `mirror` is the Connect mirror (Settings, "Count extension plays on Spotify"): `on` (the setting), `running`, the song it keeps the page on (`uri`, `paused`, `landed` once the page played it, `webPositionMs`), `handedAway` (playback went to another device), `failedUri` (a song the page would not play), and the page under it: `pageUri`, `pagePaused`, `pagePositionMs`, `hushed` (unheard), `quality` (the level given to the page: `low` while it mirrors), `bitrate` and `codec` of what it streams, `playsElsewhere` (another device has Connect), `activeDevice`. |
| `ext.list` | Installed extensions: version, enabled, running, last error, actions, settings (secret ones masked), and their last `status` probe: `status` (`live`, `inactive`, `unknown` for no answer, `not probed`), `inactive` (skipped for songs' audio and downloads) and `statusDetail`. |
| `ext.install <zip>` | Installs a package with all it asks, without the prompt. A zip on the computer is pushed first to `…/files/cli/in/`. |
| `ext.uninstall <id>` | As its page's Uninstall button. |
| `ext.enable <id>` / `ext.disable <id>` | Turns it on (clashes settled for it) or off. |
| `ext.setting <id> <key> <value>` | Sets a setting. `true`, `false`, numbers and JSON stay JSON, anything else is a string, `null` goes back to the default. The extension gets its `setting` event after the usual pause. |
| `ext.press <id> <key>` | Presses a button setting (`setting` event with `pressed: true`). |
| `ext.call <id> <hook> [json]` | Calls one hook of that extension alone (`lyrics`, `audio-source`, `download-source`, `trackinfo`, `home`, `search`, `menu`, `player`, `page`, `message`, `tap`, `status`) and prints its answer, masked; `null` when its script provides no such hook. Asked while the app starts, it waits for the extensions to load. |
| `ext.log <id> [lines]` | Its log's last lines (50 by default), masked. |
| `ext.storage <id>` | Its storage's keys and size, never the values. |
| `ext.why [n]` | The last `n` (1 by default, 20 at most) plays through the extensions: sources asked, outcome and time, skipped and why, chosen, how its address opened, announced and detected quality, fallback, error. Addresses without query, masked. Debug builds only. |
| `play <spotify:…> [context]`, `pause`, `resume`, `next`, `previous` | The player's own commands. |
| `nav <route>` | The open screen navigates to a route (`settings`, `extensions`, `extension/<id>`, `queue`…). |

## What never leaves the phone

Storage values; secret settings and settings the manifest does not list; in logs, errors and hook
answers, any stored or secret value, `Bearer`/`Basic` credentials, `Cookie`/`Authorization`
headers, JWTs, the values of fields and URL parameters named like a token, key, signature,
session, cookie or password. All replace with `***`. Only extension and player state is reachable;
no command reads the app's own account tokens.

## End to end

A sample lyrics extension, from packing to its answer, from the repository root with the debug build on the phone:

```bash
cd base && ./gradlew -q :extensions-cli:installDist && cd ..
base/extensions-cli/build/install/myspoti-ext/bin/myspoti-ext pack docs/extensions/samples/lrclib-lyrics /tmp/myspoti-cli
tools/myspoti-cli ext.install /tmp/myspoti-cli/app.myspoti.samples.lrclib-*.zip
tools/myspoti-cli ext.list
tools/myspoti-cli ext.call app.myspoti.samples.lrclib lyrics '{"title":"Get Lucky","artist":"Daft Punk","durationMs":369000}'
tools/myspoti-cli ext.log app.myspoti.samples.lrclib 20
tools/myspoti-cli play spotify:track:69kOkLUCkxIZYexIgSG8rq
tools/myspoti-cli status
tools/myspoti-cli ext.disable app.myspoti.samples.lrclib
```

In a script, `--raw` prints the whole reply (`{"ok":…,"cmd":…,"result":…}`) and the exit code says
whether it worked, e.g. `tools/myspoti-cli --raw ext.list | jq '.result[].id'`.
