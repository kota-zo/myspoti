# Web Player capture map

Clean-room notes for the new API, taken from network captures of open.spotify.com
(free account, Chrome 155 with Widevine, 2026-10-15). Raw captures hold tokens and
stay out of the repo. `{user}` is the account id, `{id}` a base62 id, `{hex}` a file
or device id. GraphQL calls all go to `POST api-partner.spotify.com/pathfinder/v2/query`
and are listed by `operationName`.

## Session

| Step | Calls |
|---|---|
| Login (Spotify's own page) | `accounts.spotify.com/login`, `login/code/send`, `login/code/verify` (e-mail code flow) |
| Web token | `GET open.spotify.com/api/token` |
| Client token | `POST clienttoken.spotify.com/v1/clienttoken` |
| Access points | `GET apresolve.spotify.com/` (spclient + dealer hosts) |
| Push channel | WebSocket `{region}-dealer.g2.spotify.com` |
| Config | `POST {spclient}/remote-config-resolver/v3/configuration`, gql `accountAttributes`, `profileAttributes` |

## Playback (Web Player device)

| Action | Calls |
|---|---|
| Register device | `POST {spclient}/track-playback/v1/devices`, `PUT connect-state/v1/devices/hobs_{hex}` |
| Play a context | `POST connect-state/v1/player/command/from/{hex}/to/{hex}` |
| Resolve audio | `GET manifests/v9/json/sources/{hex}/options/supports_drm`, `GET storage-resolve/v2/files/audio/interactive/10/{hex}`, `GET seektables.spotifycdn.com/v1/seektable/{hex}` |
| DRM | `GET widevine-license/v1/application-certificate`, `POST widevine-license/v1/audio/license` (EME, handled by the browser CDM) |
| Audio | `GET audio-fa.scdn.co/audio/{hex}` (encrypted segments) |
| Report state | `PUT track-playback/v1/devices/{hex}/state` (on play, pause, seek, skip) |
| Volume | `PUT track-playback/v1/devices/{hex}/volume` |
| Next track media | `GET track-playback/v1/media/{uri}` |
| Cluster state | `GET connect-state/v1/cluster`, then dealer pushes |
| Events | `POST gabo-receiver-service/v3/events` (playback and UI events) |

Free account: no shuffle button on a playlist context in the web UI.

## Catalog and search

| Action | GraphQL operations |
|---|---|
| Home | `home`, `feedBaselineLookup` |
| Search | `recentSearches`, `searchSuggestions` (as you type), `searchTopResultsList` (submit), `searchTracks`, `searchArtists`, `searchAlbums`, `searchPlaylists` |
| Browse | `browseAll` |
| Track | `getTrack`, `internalLinkRecommenderTrack`, `similarAlbumsBasedOnThisTrack`, `canvas`, `npvV2TrackSupplementalSections` |
| Album | `getAlbum` |
| Artist | `queryArtistOverview`, plus `GET playlist/v2/list/popular-release-segments-main-roles/artist_{id}` |
| Lyrics | `GET color-lyrics/v2/track/{id}/image/{cover}`, `GET timesynced-text-distribution/v0/get-timesynced-text/{uri}` |
| Extra metadata | `POST extended-metadata/v0/extended-metadata`, `GET metadata/4/track/{hex}` |

## Library and playlists

| Action | Calls |
|---|---|
| Library list | gql `libraryV3`, `GET playlist/v2/user/{user}/rootlist` |
| Liked Songs page | gql `fetchPlaylist` on `collection/tracks` |
| Playlist page | gql `fetchPlaylist`, `fetchPlaylistContents`, `fetchPlaylistMetadata`, `playlistPermissions`, `isCurated` |
| Follow / unfollow playlist | `POST playlist/v2/user/{user}/rootlist/changes` |
| Create playlist | `POST playlist/v2/playlist`, then `rootlist/changes` |
| Rename | `POST playlist/v2/playlist/{id}/changes` |
| Add track to playlist | gql `addToPlaylist` |
| Suggested tracks | `POST playlistextender/extendp/` |
| In library? | gql `areEntitiesInLibrary`, `POST collection/v2/contains` |

## Likes and follows

| Action | GraphQL operations |
|---|---|
| Like a track | `addToLibrary` |
| Unlike a track | `applyCurations` (with `editablePlaylists`) |
| Save / unsave album | `addToLibrary` / `removeFromLibrary` |
| Follow / unfollow artist | `addToLibrary` / `removeFromLibrary` |

Folders, pins, row moves, playlist access and cover, Connect transfer, podcasts, Blend and
Jam were captured later and are implemented in `base/api-spotify` (`SpotifyLibrary`,
`SpotifyBlends`, `SpotifyMore`); `ads/v2/config` is fetched at startup.
