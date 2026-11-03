# Jam (social-connect)

Captured on 2026-11-03 from the Spotify desktop app 1.3.4 (its web layer over CDP), the host on a
Premium account; the same calls are what the Web Player's `SocialConnectAPI` service sends, and
what Myspoti goes through (`SpotifyJam`, `api-spotify/.../SpotifyMore.kt`). Host:
`{region}-spclient.spotify.com`, JSON, the page's token.

| Action | Call | Notes |
|---|---|---|
| Start (or get the running one) | `GET social-connect/v2/sessions/current_or_new?activate=true` | Answers the session: `session_id`, `join_session_token`, `join_session_uri` (`spotify:socialsession:<token>`), `session_owner_id`, `session_members[]`, `is_session_owner`, `initialSessionType` (`IN_PERSON` from the desktop), `hostActiveDeviceId`, `maxMemberCount` (32), `queue_only_mode`, `host_device_info`. The service's `createSession()` returns a short form (`sessionId`, `joinSessionUri`, short links): read the session again for members and the owner flag. |
| Current session | `GET social-connect/v2/sessions/current` | 404 without one. |
| Session info (before joining) | `GET social-connect/v2/sessions/info/<join token>` | Owner and members, for the join dialog. |
| Join | `POST social-connect/v3/sessions/join` | Body: `join_token`, `join_type` (`deeplinking` for a link or QR, `discovered_device` for a nearby Jam), `playback_control` (`LISTEN_AND_CONTROL` when listening on one's own device, mode `REMOTE`; `CONTROL` when in the room, mode `IN_PERSON`), optional `local_device_id`, `join_attempt_id` (a new UUID per try), `permission_mode` (`FULL`). Service: `joinSession(token, joinType, mode, attemptId, localDeviceId?, permissionMode = FULL)`. A free account joining `REMOTE` gets the Premium upsell in the official apps. |
| Leave (member) | `POST social-connect/v3/sessions/<session id>/leave` | 404 counts as left. |
| End (host) | `DELETE social-connect/v3/sessions/<session id>` | 404 counts as ended. Seen three times, each 200. |
| Invite link | `POST url-dispenser/v1/generate-url` | `spotify_uri: spotify:socialsession:<token>`, `custom_data` (`ssp=1`, `app_destination=socialsession`), `link_preview` (title, `https://shareables.scdn.co/publish/socialsession/<token>`), `utm_*` (`utm_medium` `share-link` or `qr`). Answers `shareable_url` (`spotify.link/…`), `share_id`, `full_url` (`open.spotify.com/socialsession/<token>?si=…`). Service: `getShortInviteLink(token, joinSessionUri, qr?)`. |
| Queued by | GraphQL `decorateQueuedByUsers` (`userUris`) | The host names who queued a song (seen when a member added one). |

Checked from Myspoti's own code on a free account (`host-cdp` JamLiveTest, the desktop app as the
host): `current_or_new` answers 403 `FREE_USER_NOT_ALLOWED` (a free account cannot start a Jam), the
join answers 200 with `CONTROL`. With `local_device_id` set to the page's own Connect device, the
member's commands (next, a list, pause, resume) go to `connect-state/v1/player/command/from/<device>/to/social-connect-<session>`
(202) and the host follows, also after the member's page reloads; without it the page sometimes never
learned of the Jam, sent its first command to itself (a transfer to itself) and so left the Jam.
A list played by a member plays on the host as `spotify:list:jam-list:<session>`.

Who is in the session and what they do reaches the host through the dealer (pushed to the native
core on the desktop: not seen over CDP).
