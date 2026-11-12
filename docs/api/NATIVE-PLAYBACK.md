# Native playback: playing one track without the Web Player page

Clean-room notes for the native engine (`base/engine`): the minimal protocol sequence that
plays one track for a free account and gets the play counted, starting from a valid web access
token and client token. Written from our own network captures; no Spotify code is reproduced.
Raw captures hold account data and stay out of the repo. Placeholders: `{token}`, `{ct}` (client
token), `{dev}` (40-hex device id), `{conn}` (dealer connection id), `{sp}` (regional spclient
host from apresolve, e.g. `gew4-spclient.spotify.com`), `{file}` (40-hex audio file id).

Sources:

| Capture | What it holds |
|---|---|
| play-bodies, 2026-11-10 | Firefox 159 desktop, free account, Web Player 1.3.10.962 (SDK harmony 4.81.1). Page load, resume of a paused session, 50 s of play. Every request with headers; bodies of 30 playback calls. No audio bodies, no WebSocket frames. |
| free-plain, 2026-11-10 | Same browser, 75 s of play from 0, headers only. |
| real1, 2026-10-26 | Chrome 156, three tracks played to their natural end, then a pause. Request bodies and every dealer frame. |
| connect-2tabs, 2026-10-26 | Chrome, two tabs: pause, resume, seek, next, previous, transfer, volume, all with bodies and dealer frames. |
| audio-init, 2026-11-10 | Firefox 159, the first CDN range answer of an audio file with its body (1292 bytes). |
| end-of-track, 2026-11-10 | Firefox 159: a paused session resumed 30 s before the end, two natural ends, bodies of every state PUT and melody batch. |

Tags: **[M]** measured in a capture, **[I]** inferred (from field names, timing or a single
sample). SESSION.md (tokens, dealer, hosts) and CAPTURE-MAP.md are the background.

## 1. The sequence

1. `GET apresolve.spotify.com/?type=dealer-g2&type=spclient` -> first dealer and spclient host. [M]
2. Dealer WebSocket `wss://{dealer}/?access_token={token}`; the first frame carries
   `Spotify-Connection-Id` = `{conn}`. Ping `{"type":"ping"}` every 30 s. [M]
3. `POST {sp}/track-playback/v1/devices` (register, with `{conn}`) -> `initial_seq_num`,
   `file_format_filter`. [M]
4. `PUT {sp}/connect-state/v1/devices/hobs_{dev35}` with `x-spotify-connection-id: {conn}`. [M]
5. A Connect command from `{dev}` to `{dev}`: `POST {sp}/connect-state/v1/player/command/...`
   (`play`, `resume`...) or `POST .../connect/transfer/...`. [M]
6. The server pushes `hm://track-playback/v1/command` `replace_state` on the dealer: a state
   machine (queue) and the state to be in, paused or not, with a seek position. [M]
7. For the current state's track: pick the file from its manifest, then
   `GET {sp}/storage-resolve/v2/files/audio/interactive/10/{file}?...` (CDN URLs) and
   `GET seektables.spotifycdn.com/v1/seektable/{file}` (fragment layout, PSSH). [M]
8. `PUT {sp}/track-playback/v1/devices/{dev}/state` `before_track_load`; melody
   `jssdk_playback_start`. [M]
9. DRM: `GET spclient.wg.spotify.com/widevine-license/v1/application-certificate`,
   `GET {sp}/melody/v1/license_url?...`, `POST {sp}/widevine-license/v1/audio/license`
   (CDM challenge in, licence out). Audio: range requests on `audio-fa.scdn.co/audio/{file}?{token}`. [M]
10. When audio really plays: state PUT `started_playing` (about 1.1 s in), then
    `played_threshold_reached` after 30 s of audio (`ms_played_until_update`). [M]
11. At the end: melody `track_stream_verification` + `jssdk_playback_stats` (one batch), state
    PUT `track_data_finalized` with `playback_stats`, then `before_track_load` of the next state
    (the current state's `advance` transition, followed locally). [M]
12. Throughout: melody `media_playback_event_v2` timeline, sent every 120 s. [M]

Steps 10-12 are what makes a listen count; the user confirmed real1's three listens in their
Spotify history (2026-10-26). Every Web Player action, even one started on the device itself,
goes through steps 5-6: a play from the page is `player/command` from `{dev}` to `{dev}`. [M]

## 2. Headers per endpoint

Every call carries the browser's `user-agent`, `accept` (`*/*` unless noted), `accept-language`,
`origin: https://open.spotify.com`, `referer: https://open.spotify.com/`, `sec-fetch-dest: empty`,
`sec-fetch-mode: cors` and `sec-fetch-site` (`same-site` on `*.spotify.com`, `cross-site` on the
CDNs). No `Cookie` goes to spclient or the CDNs. [M, play-bodies]

| Endpoint | authorization | client-token | app-platform + spotify-app-version | content-type |
|---|---|---|---|---|
| `track-playback/v1/devices`, `/state`, `/volume`, `/media/{uri}` | yes | yes | yes | `application/json` (none on GET) |
| `connect-state/v1/devices/hobs_*` (accept `application/json`, plus `x-spotify-connection-id`) | yes | yes | yes | `application/json` |
| `connect-state/v1/player/command/...`, `connect/transfer/...` | yes | yes | yes | `application/json` |
| `storage-resolve`, `melody/v1/license_url`, `melody/v1/time` | yes | yes | no | - |
| `melody/v1/msg/batch` | yes | yes | no | `text/plain;charset=UTF-8` |
| `widevine-license/v1/audio/license` (POST of raw bytes) | yes | yes | no | none at all |
| `spclient.wg.spotify.com/widevine-license/v1/application-certificate` | no | yes | no | - |
| `apresolve`, `seektables.spotifycdn.com`, `audio-fa.scdn.co` | no | no | no | - (audio adds `range`) |

`authorization` is `Bearer {token}`; `app-platform` is `WebPlayer`; `spotify-app-version` is the
page's `clientVersion` (`1.3.10.962.g89de28870671`). The browser also sends CORS preflights
(`OPTIONS`) for each spclient call; a native client has no reason to. [M]

Answers come compressed to the browser's `accept-encoding` (`gzip, deflate, br, zstd`):
track-playback in zstd, connect-state in gzip or br (seen by the Android relay). The platform
HTTP client must decode them. [M]

## 3. Device registration

Register body (field order as sent) [M]:

```json
{"device": {"brand": "spotify",
   "capabilities": {"change_volume": true, "enable_play_token": true, "supports_file_media_type": true,
     "play_token_lost_behavior": "pause", "disable_connect": false, "audio_podcasts": true,
     "video_playback": true,
     "manifest_formats": ["file_ids_mp3", "file_urls_mp3", "manifest_urls_audio_ad", "manifest_ids_video",
       "file_urls_external", "file_ids_mp4", "file_ids_mp4_dual", "manifest_urls_audio_ad"],
     "supports_preferred_media_type": true, "supports_playback_offsets": true, "supports_playback_speed": true},
   "device_id": "{dev}", "device_type": "computer", "metadata": {}, "model": "web_player",
   "name": "Web Player (Firefox)", "platform_identifier": "web_player linux undefined;firefox 159.0;desktop",
   "is_group": false, "is_public": false, "correlation_id": "<clienttoken js_sdk_data.device_id>",
   "client_version": "harmony:4.81.1-1f3d48b7f"},
 "outro_endcontent_snooping": false, "connection_id": "{conn}",
 "client_version": "harmony:4.81.1-1f3d48b7f", "volume": 65535}
```

`manifest_urls_audio_ad` is listed twice by the Web Player. `correlation_id` equals the
clienttoken request's `device_id` (Firefox); Chrome's `name` is `Web Player (Chrome)` and its
`platform_identifier` `web_player linux undefined;chrome 155.0.0.0;desktop`. [M]

Answer: `{"initial_seq_num":1,"device_keep_alive_update_seconds":21600,"endsongs":null,
"file_format_filter":5160,"supports_observing":true,"effective_license":"tft"}`. [M]

- `seq_num`: the first state PUT carries `initial_seq_num + 1`; each state or volume PUT adds one
  (volume and state share the counter: real1 seq 2 state, 3 volume, 4 state). [M]
- `file_format_filter` is a bit mask of allowed file formats: 5160 = bits 3, 5, 10, 12, and the
  state machine of the same free account only listed formats 10 and 12 while `media/{uri}`
  also listed 11 and 13 (256 kbit/s). [I]
- `hobs` body: `{"member_type":"CONNECT_STATE","device":{"device_info":{"capabilities":
  {"can_be_player":false,"hidden":true,"needs_full_player_state":true}}}}`, answer the cluster. [M]
- A Chrome page also sent `PUT .../state` `{"seq_num":2,"state_ref":null,"sub_state":
  {"playback_speed":1,"format":"file_urls_external","is_video_on":false},"debug_source":
  "video_visibility_changed"}` and a volume PUT right after registering; Firefox did not.
  Not needed. [M]
- Leaving: `DELETE track-playback/v1/devices/{dev}` (body `seq_num`, `state_ref`, `sub_state`,
  `previous_position`, `debug_source: "deregister"`), `DELETE connect-state/v1/devices/hobs_{dev35}`
  (SESSION.md). [M 2026-10-15]

## 4. Commands and the state machine

Commands (`POST {sp}/connect-state/v1/player/command/from/{dev}/to/{dev}`), answer
`{"ack_id": "..."}`. Envelope `{"command": {<fields>, "logging_params": {"page_instance_ids": [],
"interaction_ids": [], "command_id": "<32 hex>"}, "endpoint": "<name>"}}`. [M]

| Endpoint | Fields |
|---|---|
| `play` | `context` {`uri`, `url`: `context://{uri}`, `metadata`}, `play_origin` {`feature_identifier`, `feature_version`: appServerConfig `buildVersion`, `referrer_identifier`?}, `options` {`license`: `tft`, `skip_to` {`track_index`, `track_uri`, `track_uid`, any subset}, `player_options_override`: {}} |
| `pause`, `resume`, `skip_next`, `skip_prev` | none |
| `seek_to` | `value`: position in ms, a float |
| `set_options`, `set_queue` | see CONNECT notes (shuffle, repeat, queue) |

Previous button: past about 3 s the Web Player sends `seek_to` 0, else `skip_prev`. [M]
Transfer: `POST .../connect/transfer/from/{dev}/to/{dev}` `{"transfer_options":{"restore_paused":
"pause"},"command_id":"..."}` (page load restoring a paused session); `"restore"` keeps playing. [M]

The answer arrives on the dealer as `{"type":"message","uri":"hm://track-playback/v1/command",
"headers":{"content-type":"application/json"},"payloads":[P]}` with P [M]:

```text
{type: "replace_state", selected_alias_id, prev_state_ref, registration_token: "",
 state_machine: {state_machine_id, tracks: [...], states: [...], attributes: {options, playback_session_id}},
 state_ref: {state_index, paused, active_alias}, seek_to: <ms int | null>, context_metadata}
```

- `tracks[i]`: `metadata` (`uri`, `name`, `group_name`, `authors`, `duration` ms, `images`...),
  `manifest` (lists by format name, see 5), `track_type` `AUDIO`, `ms_played_until_update`
  (30000), `ms_playing_update_interval` (0), `content_type` (`TRACK`; `AD` for an inserted ad).
- `states[i]`: `state_id` (32 hex; it is the `playback_id` of melody), `track` (index),
  `track_uid`, `disallow_seeking`, `initial_playback_position`, `transitions` {`skip_next`,
  `skip_prev`, `show_next`, `show_prev`, `advance`}, each `{state_index, paused, ends_context}`
  or null, `player_cookie`, `signals` (`switch-to-video`), `options`, `track_provider`
  (`context`, `autoplay`, `queue`).
- The current state is `states[state_ref.state_index]`; 3-5 tracks and 8-12 states per push.
- The full machine is re-sent on every push; `pause`/`resume` keep `state_index` and flip
  `paused`; `seek_to` carries the new position; `skip_next` sends a state on the next track. [M]
- Every state PUT answer is the next machine: `{state_machine, updated_state_ref: {state_index,
  paused, ...}, previous_state_ref, endsong, play_metadata, context_metadata, ...}`. The next PUT
  names that machine's `state_machine_id`; state ids stay stable across machines. [M]
- `advance` of the paused-loaded state was `paused: true` in the answer to a paused report and
  `false` in the answer to a playing one. [M]
- A natural end is followed locally: no command and no `replace_state` came between
  `track_data_finalized` and the next `before_track_load` in real1 and end-of-track. [M]
- The two PUTs of a natural end go out 10-20 ms apart with the same `state_machine_id` (the
  answer to the PUT before them): the page does not wait for the finalized PUT's answer. The
  next report (`started_playing`) names the machine of the `before_track_load` answer. [M, end-of-track]
- Playback taken by another device: `replace_state` with no tracks, no states and `state_ref: {}`;
  the device finalizes the track and PUTs `debug_source: "state_clear"` with `state_ref: null`. [M]
- `set_volume` pushes (`volume`, `command_id`) are answered by `PUT .../volume`
  `{"seq_num", "volume", "command_id"}` (204). [M]

## 5. Resolving the audio file

The state machine's track manifest is enough; no extra lookup is needed for the current track. [M]

```json
"manifest": {
  "file_ids_mp4": [{"bitrate": 128000, "file_id": "{file}", "file_url": null, "impression_urls": null,
    "track_type": "AUDIO", "format": "10", "audio_quality": "HIGH", "hifi_status": "NONE",
    "gain_db": -5.127103, "expires_at": null, "average_bitrate": 132212}],
  "file_ids_mp4_dual": [{"...": "...", "format": "12", "average_bitrate": 132487}]}
```

The Web Player played the `file_ids_mp4` format 10 file (AAC 128 kbit/s) when both lists were
there. [M] Format numbers: 10 and 11 are AAC 128 and 256 kbit/s, 12 and 13 their `_dual`
versions (bitrates in the manifest); `_dual` is not explained by the captures. [I]

`GET {sp}/storage-resolve/v2/files/audio/interactive/10/{file}?version=10000000&product=9&platform=39&alt=json`
(the same query on every call, Firefox and Chrome; the `10` matched the file's format both
times, so it may be the format [I]):

```json
{"result": "CDN", "fileid": "{file}", "ttl": 86400,
 "cdnurl": ["https://audio-fa.scdn.co/audio/{file}?1794354467_<token>",
            "https://audio-fa-quic.spotifycdn.com/...", "https://audio-fa-tls13.spotifycdn.com/...",
            "https://audio-fa-tls130.spotifycdn.com/...", "https://audio-fa-quic0.spotifycdn.com/...",
            "https://audio-ak.spotifycdn.com/audio/{file}?__token__=exp=1794354467~hmac=<64 hex>"]}
```

The number before `_` is the URL expiry (epoch seconds, now + 24 h, equal to `license_url`'s
`expires`). The page fetched from the first URL in Firefox and from `audio-fa-quic0` in Chrome. [M]

`GET seektables.spotifycdn.com/v1/seektable/{file}` (no credentials) [M]:

```json
{"seektableVersion": 2, "offset": 1292, "timescale": 44100,
 "segments": [{"size": 163393, "duration": 441344}, "... 16 entries"],
 "initRange": {"start": 0, "end": 1068},
 "pssh": {"widevine": "<base64 pssh box>", "playready": "<base64 pssh box>", "fairplay": ""}}
```

Segment durations sum to the track length (150.2266 s for a 150226 ms track). The Web Player
asked for this table before storage-resolve answered, i.e. it needs nothing from it. [M]

`GET {sp}/track-playback/v1/media/{uri}?manifestFileFormat=...` (one `manifestFileFormat` per
register `manifest_formats` entry, duplicate included) returns `{"media": {"{uri}": {"item":
<track as in the state machine>, "video_version_uri"}, "{video uri}": {...}}}`. The page called
it for the current track after loading it, and for the next track after a skip; it returned the
track and its music video version. Not needed to play; useful to resolve a URI outside a state
machine. [M call, I purpose]

`manifests/v9/json/sources/{id}/options/supports_drm` and `video-fa.scdn.co` serve the canvas
and music video (`manifest_ids_video`), not the audio. [M]

## 6. The audio file

- Range requests on the CDN URL: first `bytes=0-1291` (init range plus everything up to
  `offset`), then exactly one seektable segment per request (`bytes=1292-164684`,
  `164685-329897`, ...). Resuming at 60.3 s started at segment 6 (`bytes=991353-1156735`). [M]
- Buffering: two segments at start, then one request every 10 s (one segment of about 10 s
  ahead of the play position). Responses are `206`, `content-type: video/mp4`. [M]
- Container: fragmented MP4 (an init range, then 16 fragments of 441344/44100 = 10.0 s). [I from
  the seektable and ranges; no audio body was captured]
- Codec: AAC-LC, `audio/mp4; codecs="mp4a.40.2"`, 128000 bit/s for format 10. [M, real1
  `media_playback_event_v2` and `playback_stats`]
- Encryption: Common Encryption, scheme `cenc` (AES-CTR). The Widevine PSSH (version 0, system
  id `edef8ba9-79d6-4ace-a3c8-27dcd51d21ed`) holds: field 2 key id = the first 16 hex characters
  of `{file}` as ASCII bytes, field 3 provider `spotify`, field 4 content id = the 20 raw bytes of
  `{file}`, field 9 protection scheme `0x63656e63` (`cenc`). The PlayReady header says
  `ALGID="AESCTR"` for the same key. [M, decoded from the seektable]
- Init chunk (`bytes=0-1291`, 1292 bytes): `ftyp` (36) + `moov` (1032) + `sidx` (224, version 0,
  timescale 44100, 16 references = the seektable's 16 segments). `moov`: one `trak` whose `stsd`
  holds an `enca` entry (`sinf`: `frma` mp4a, `schm` `cenc` v1, `tenc` protected, per-sample IV
  size 8, default KID = the first 16 bytes of `{file}`, i.e. its first 32 hex characters, raw) and a
  clear `mp4a` entry; `mvex` with `mehd` = 6627044/44100 = 150.273 s, the 150274 the media element
  reported; a Widevine `pssh` (89 bytes). [M, audio-init]
- The moov `pssh` is not byte-equal to the seektable's (87 bytes): the moov one adds protobuf
  field 1 (`algorithm` = 1, AES-CTR). Same key id, provider and content id. [M]

## 7. DRM transport

- `GET spclient.wg.spotify.com/widevine-license/v1/application-certificate`: 702 bytes, a
  Widevine signed service certificate (starts `0a b8 02 08 03 12 10`). Same bytes three times in
  one page load; the engine fetches it once. Used for privacy mode (EME `setServerCertificate`,
  MediaDrm `serviceCertificate` property). [M bytes, I use]
- `GET {sp}/melody/v1/license_url?keysystem=com.widevine.alpha&mediatype=audio&sdk_name=harmony&sdk_version=4.81.1`
  -> `{"uri":"widevine-license/v1/audio/license","expires":1794354467}`: a path on `{sp}`. [M]
- `POST {sp}/widevine-license/v1/audio/license`: body = the CDM's licence request (1739 bytes,
  protobuf starting `08 01`), answer = the licence (526 bytes, protobuf starting `08 02`), both
  passed through untouched; no content-type on the request. One licence per track, issued at
  track change (real1), after the init range was requested (free-plain). [M]
- The core never parses these blobs. [design]

## 8. State reports

`PUT {sp}/track-playback/v1/devices/{dev}/state`, body (key order as sent) [M]:

```json
{"seq_num": 3,
 "state_ref": {"state_machine_id": "<latest machine>", "state_id": "<current state>", "paused": false},
 "sub_state": {"playback_speed": 1, "position": 61337, "duration": 150274, "media_type": "AUDIO",
               "bitrate": 128000, "audio_quality": "HIGH", "format": 10, "is_video_on": false},
 "previous_position": 61337,
 "debug_source": "started_playing"}
```

| debug_source | When | paused / speed | position / previous_position |
|---|---|---|---|
| `before_track_load` | right after the file is resolved | as pushed; speed 0 | start position; previous track's last position, absent for the first track |
| `started_playing` | 1066-1245 ms of audio after the start (four tracks) | false / 1 | current, same |
| `played_threshold_reached` | `ms_played_until_update` (30000) ms of audio after the start position (30187-30243 measured) | false / 1 | current, same |
| `pause` / `resume` | after the player paused or resumed | true/0, false/1 | current, same |
| `seek` | after the seek | speed as playing | new / old |
| `modify_current_state` | options or queue changed, same track | | |
| `track_data_finalized` | track end or left (skip, transfer) | false / 1 at a natural end | last, same; plus `playback_stats` |
| `state_clear` | playback moved away | `state_ref: null` | |

`duration` is the media's measured duration once loaded (150274 for a 150226 ms track), the
metadata duration before. `track_data_finalized` carries the file's own duration instead (the
seektable's, rounded: 150227 for 150.2266 s; `started_playing` of the same file said 150274), and
so do its `ms_total_est` and melody's `ms_file_duration`. Its `position` is the last reported
position (150062, 189293), while melody's `ms_end_position` is the file's end and `ms_played` runs
to it (29475 = 150227 - 120752; 189440 for a track played from 0). [M, end-of-track; real1 agrees:
153699 / 153889, 151238 = 153889 - 2651] A resume before any audio (paused load, then `resume`) is reported by
`started_playing`, not `resume`. At a natural end `track_data_finalized` and the next
`before_track_load` were sent together with the same `state_machine_id`. [M]

`urls_json.segments` in `jssdk_playback_stats` counts the segments fetched (4 for a resume 30 s
before the end), not the seektable's. [M, end-of-track]

`playback_stats` (finalized only) [M]: `ms_total_est`, `ms_metadata_duration`,
`ms_manifest_latency`, `ms_manifest_parse_latency`, `ms_key_system_info_latency`, `ms_latency`,
`ms_first_bytes_latency`, `start_offset_ms`, `ms_initial_buffering`, `ms_initial_rebuffer`,
`ms_seek_rebuffering`, `ms_stalled`, `max_ms_seek_rebuffering`, `max_ms_stalled`, `n_stalls`,
`n_rendition_upgrade`, `n_rendition_downgrade`, `bps_bandwidth_max/min/avg`, `audiocodec`
(`mp4`), `audio_start_bitrate`, `video_start_bitrate` (null), `start_bitrate`,
`time_weighted_bitrate`, `key_system` (`widevine`), `ms_key_latency`, `total_bytes`,
`local_time_ms`, video frame and resolution counters (0), `strategy` (`MSE`),
`ms_played_per_surface` ({}), `ms_played_visible`, `ms_played_per_audio_format`
(`{"mp4a.40.2;128000": <ms played>}`), `ms_played_per_video_format` ({}).

## 9. Melody play reports

`POST {sp}/melody/v1/msg/batch`, answer `202 {"status":202}`. Body
`{"messages":[{"type":..., "message":{...}}], "sdk_id":..., "platform": "<platform_identifier>",
"client_version":"0.0.0"}`. `sdk_id` is `harmony:4.81.1` for `jssdk_playback_start`,
`track_stream_verification` and `jssdk_playback_stats`, and `harmony:4.81.1-1f3d48b7f` for
`media_playback_event_v2`, `jssdk_connect_command` and `client_event`. [M]

| Type | When | Fields |
|---|---|---|
| `jssdk_playback_start` | track loaded | `play_track`, `file_id`, `playback_id` (= state id), `session_id` (page load time ms), `ms_start_position`, `initially_paused`, `client_id` "", `correlation_id` "", `feature_identifier` "" |
| `track_stream_verification` | track end or left, batched with the stats | `play_track`, `playback_id`, `ms_played`, `ms_nominal_played`, `session_id`, `sequence_id` 0, `next_playback_id`, `playback_service` `track-playback` |
| `jssdk_playback_stats` | same batch | about 80 fields: ids, `audio_format` `mp4`, `protected` true, `key_system` `com.widevine.alpha`, `key_system_impl` `native`, `urls_json` (CDN URL, segment count, average bandwidth), start/end time, latencies, `ms_played`, `ms_file_duration`, start/end position, rebuffer and stall counters, seek counts, `reason_start`, `reason_end`, `initially_paused`, `total_bytes`, `strategy`, `ms_played_per_audio_format` |
| `media_playback_event_v2` | every 120 s (125, 245, 365, 485 s); after a natural end, right after the next track's first audio (+0.45 s), then 120 s from there | `events` timeline (below), `context` {device {manufacturer `spotify`, model `web_player`, type `computer`}, os {name "", version ""}} |
| `jssdk_connect_command` | after a command's push arrived | `ms_ack_duration`, `ms_request_latency`, `command_id`, `command_type`, `target_device_*`, `result` `success`, `http_status_code` |
| `client_event` | debug log (`position_changed`...) | `source` `harmony:track_playback:client`, `json_data` |

`ms_played` = audio actually played: 151238 for a track of 153889 ms started at 2651. `reason_start`
/ `reason_end` values seen: `remote` (command or transfer), `trackdone` (previous track ended /
this one ended), `fwdbtn`, `endplay`, `unknown`. [M]

Timeline events: `{sn, ct, ms, ps, et, ...}`, `sn` from 0 per playback, `ct` wall clock ms,
`ps` a partial player state (`pp` position, `md` duration, `sp` speed, `ip` paused, `br` bitrate,
`vo` volume %, `ns` network). Codes [M codes, I meaning]:

| et | Seen | Meaning |
|---|---|---|
| 0 | at load, with `ms` {`mu`: `spotify-audio://{file}`, `pi`: playback id}, `ur` uri, `xt` `audio` | media opened |
| 7 | after 0, `ks` {`nm`: key system, `im`: `native`} | key system chosen |
| 2 / 3 | `sp` 0, then `sp` 1 | waiting for data / data flowing |
| 4 | after the first position | playing |
| 9 | `ap` {`qi`, `cc` codec string, `ab`, `xb`}, `ty` `audio`, `rn` `abr` | format chosen |
| 10 | `pp`, `rn` 0 first, 7 every 30 s, 5 at a natural end, 6 when left | position report |
| 1 | `ip`, `pp` | pause or resume |
| 5 / 6 | `ss`, `se` | seek start / end |

## 10. What the native engine leaves out

- Ads: `ads/v2/config` and ad audio are never requested. A state whose track is `content_type:
  AD` is not loaded and nothing is reported for it; the caller is told (`AdBreak`). ADS.md.
- Canvas and music video (`manifests/v9`, `video-fa.scdn.co`, the `overture` melody batches with
  `play_track: spotify:canvas:...`), `gabo-receiver-service` UI events, `client_event` debug
  logs, `remote-config-resolver`, `melody/v1/time` (server clock), CORS preflights.
- No secret or token is computed: tokens come from the `TokenSource` (the page bridge).

## 11. Engine map (`base/engine`, Kotlin Multiplatform, commonMain)

| File | Role |
|---|---|
| `Platform.kt` | seams: `Http`, `TokenSource`, `WebSockets`, `Environment`, `DrmPlayer` (+ `PlayableMedia`, `LicenceChannel`, `PlayerEvent`) |
| `EngineConfig.kt` | the browser identity and measured constants |
| `SpClient.kt` | headers per endpoint (section 2), 401 -> one token refresh, 429 -> Retry-After or 1/2/4 s back-off, then surfaced |
| `Requests.kt` | request bodies (sections 3, 4, 8) |
| `Resolver.kt` | file choice, storage-resolve, seektable, `media/{uri}`; certificate, licence URL and licence transport |
| `Reports.kt` | melody messages, `playback_stats`, the `media_playback_event_v2` timeline |
| `NativeEngine.kt` | registration, commands, dealer pushes, the session state machine, next-track preload |

Preload: once `started_playing` is sent (or `preloadBeforeEndMs` before the end), the engine
resolves the next state's file (`advance`, else `skip_next`) and hands it to
`DrmPlayer.preload`, which fetches its licence and first segment. A skip to it then needs no
storage-resolve, seektable or licence round trip. The Web Player itself resolved the next file
10 s before the end and licensed it at the switch (real1). [M Web Player, design engine]

## 12. Gaps and captures that would close them

| Gap | Capture |
|---|---|
| End of context (`advance` null, autoplay off): what is PUT, what is pushed | same with a single-track context and autoplay off in the account settings |
| `jssdk_playback_error` and `debug_source: "error"` bodies (load failure) | a long `capture --play --bodies 'track-playback\|melody'` with the ad audio host blocked (ADS.md test) |
| 401 and 429 answers of spclient (body, `Retry-After`) | not observed in any capture; the engine assumes standard HTTP semantics |
| Dealer frames in Firefox (ffcap records no WebSocket frames) | Chrome captures (real1, connect-2tabs) are used instead |
| Keep-alive: `device_keep_alive_update_seconds` 21600 suggests a re-register or PUT every 6 h | a 6 h+ capture |
| What `started_playing`'s ~1.1 s delay is tied to (time, or the media's first `timeupdate`) | the engine sends it after 1 s of reported audio |
| A skip's finalize (`reason_end` `fwdbtn`): duration, position, and whether its `before_track_load` also reuses the machine | a Firefox capture with bodies of a skip mid-track |
| Whether Spotify accepts the Android CDM's licence challenge (L1 and L3) and a `content-type` on the licence POST | the first on-device run of the native engine (section 13) |

Closed on 2026-11-10: the init segment layout (audio-init) and the Firefox natural end
(end-of-track; the engine now matches it, `SessionTest.natural_end_matches_the_firefox_end_of_track_capture`).

## 13. The Android player (`base/engine-android`)

An Android library with the platform seams, depending on `:engine` only (no WebView, no phone UI,
no `:host-android`), so a Wear OS app can use it as is.

| File | Role |
|---|---|
| `ChromiumHttp.kt` | `Http` on Chromium's stack: `android.net.http.HttpEngine` on Android 14+, Cronet from Play services before (as the relay's PlatformStack / CronetStack). Chromium writes Accept-Encoding and decodes. No X-Requested-With. Known deviation: Chromium refuses an upload without a type, so the licence POST gets `application/octet-stream` where the browser sent none. |
| `OkHttpWebSockets.kt` | The dealer over OkHttp (neither Cronet nor HttpEngine has WebSockets). Known limitation: OkHttp's TLS fingerprint is not Chrome's. |
| `SegmentDataSource.kt` | A Media3 `DataSource` over the same `Http`: `bytes=0-{offset-1}` first, then one seektable segment per range request, as the Web Player (section 6). The CDN URL with its token stays out of Media3 (private `myspoti-cdn://` URI). Falls back across the storage-resolve URLs. |
| `Media3DrmPlayer.kt` | The `DrmPlayer`: ExoPlayer with a `ProgressiveMediaSource` + `FragmentedMp4Extractor` (the init chunk's `sidx` gives exact segment seeks; the seektable decides the ranges), a `DefaultDrmSessionManager` (multi-session) whose `MediaDrmCallback` calls the engine's `LicenceChannel` (blocking on Media3's DRM thread; raw Widevine both ways). Privacy mode: the application certificate is set as `serviceCertificate` before the first session. Positions every 250 ms; `Ended` from `STATE_ENDED`. Audio focus and becoming-noisy by Media3; a pause the player makes itself is reported through `onPlayerPause` so the caller sends a pause command. |
| `AndroidEnvironment.kt` | Clock, random hex, `WidevineInfo` (security level and CDM version). |

Why progressive + sidx rather than a DASH manifest built from the seektable: no extra Media3
module, and the extractor's seek points are the seektable's segment starts anyway (16 `sidx`
references = 16 segments), so the data source can map every read onto the Web Player's ranges.

Preload: the next item's init chunk and first segment are fetched into memory and a DRM session is
acquired with the moov's `pssh` (the seektable's differs by one field, section 6, and Media3 reuses
a session only for equal init data). At the switch the player finds the licensed session and the
first 10 s in memory; it re-prepares (a new source), so the switch is gapless-ish, not
sample-exact. The DRM manager is held for the player's life so keep-alive sessions survive.

Tokens (`:host-android`, `NativeTokens.kt`): the web token the page holds (bridge `token.get`) and
the client token SpotifyHttp keeps, through `SpotifyHttp.credentials()`; identity = the install's
Chrome fingerprint (user agent, `sec-ch-ua*` client hints through `EngineConfig.clientHints`).

Debug entry (debug builds only, `app/src/debug/.../NativeDebug.kt`, via `TestControlReceiver`):
`adb shell am broadcast -a app.myspoti.TEST -p app.myspoti.dev --es cmd native-play --es arg spotify:track:<id>`
or `tools/myspoti-cli native-play spotify:track:<id>`; then `native-pause`, `native-resume`,
`native-seek <ms>`, `native-next [uri]`, `native-prev`, `native-transfer`, `native-stop`,
`native-status`, `native-log [n]`. Every step is a `native:` line in the Logbook.

