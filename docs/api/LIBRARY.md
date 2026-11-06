# Library and playlists (Web Player)

Clean-room notes from network captures of open.spotify.com (free account, 2026-10-15,
sessions `04-playlists`, `05-playlist-edit`, `06-likes`, plus the same calls seen in other
sessions). Nothing here is copied from Spotify code; it only describes what goes over the
wire. Placeholders: `{user}` account username, `{id}` base62 id, `{likedId}` the id of the
account's Liked Songs playlist, `{uuid}` a random client UUID, `{rev}` a playlist revision.
See [CAPTURE-MAP.md](CAPTURE-MAP.md) for the session setup (tokens, access points, dealer).

## Common request shape

### GraphQL (pathfinder)

`POST https://api-partner.spotify.com/pathfinder/v2/query`

Request headers (names only): `accept`, `accept-language`, `app-platform` (`WebPlayer`),
`authorization` (`Bearer` web token), `client-token`, `content-type` (JSON),
`spotify-app-version`, plus the browser's `origin`, `referer`, `user-agent`, `sec-*`.

```json
{"variables": {...}, "operationName": "<name>",
 "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "<hash>"}}}
```

Response is `{"data": {...}}`. Errors were not captured; every call in the sessions
returned HTTP 200. The hash identifies a persisted query and changes when Spotify ships
a new web build, so keep hashes in one table that can be updated without code changes.

| operationName | sha256Hash |
|---|---|
| `profileAttributes` | `08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0` |
| `accountAttributes` | `d48062d0df0a256ff78ed9e232b83d238dcd710b371efd304b0e178b62595582` |
| `libraryV3` | `390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455` |
| `areEntitiesInLibrary` | `134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed` |
| `isCurated` | `e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6` |
| `editablePlaylists` | `d5c4b8096437dcc2ac9528c91dfcd299e35b747cda2f8f75d28f41f49c5092ba` |
| `addToLibrary` | `896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148` |
| `removeFromLibrary` | `896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148` (same document as `addToLibrary`) |
| `applyCurations` | `05b739a3a73091c213385233b9d3ed8a857c2ca29d2eebadb3d04ed12e288697` |
| `addToPlaylist` | `b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a` |
| `fetchPlaylist`, `fetchPlaylistContents`, `fetchPlaylistMetadata` | `21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf` (one document, three operations) |
| `playlistPermissions` | `e43d1d35f231cf289c23c9d9c489f4a4f502e4eda09839c530608f107b6556b8` |
| `lookupChildEntities` | `91ce02e32b19123de231dc8de91fe4b9ab84eca087d4c015549308d77fbb6d10` |

### spclient REST

Host `spclient.wg.spotify.com` (the Web Player also uses the `{region}-spclient` host from
`apresolve`). Request headers (names only): `accept` (`application/json`),
`accept-language`, `app-platform`, `authorization`, `client-token`, `spotify-app-version`,
and on POST `content-type` (`application/json;charset=UTF-8` for playlist calls,
`application/json` for collection calls). All bodies below are JSON; nothing in this area
used protobuf over HTTP. Only the dealer pushes are protobuf.

## Account

### profileAttributes

`variables: {}`. Fields used: `data.me.profile.username` (this is `{user}` for the
spclient URLs), `uri` (`spotify:user:{user}`), `name` (display name), `avatar`.

```json
{"data":{"me":{"profile":{"accountId":"…","avatar":null,"avatarBackgroundColor":1697420,
 "name":"…","socialHandle":null,"uri":"spotify:user:{user}","username":"{user}"}}}}
```

### accountAttributes

`variables: {}`. Fields used: `data.me.account.product` (`FREE`),
`attributes.catalogue` (`free`), `attributes.ads` (true), `attributes.onDemand`,
`country` (two letters), `features.personalizedRecommendations`,
`attributes.hasPodcastsAvailable`, `attributes.filterExplicitContent`.

```json
{"data":{"me":{"account":{"attributes":{"ads":true,"catalogue":"free","onDemand":true,
 "hasPodcastsAvailable":true,"filterExplicitContent":null,…},
 "country":"CH","features":{"personalizedRecommendations":"PERMITTED"},"product":"FREE"}}}}
```

## Reading the library

### libraryV3 (Your Library list)

Called on every page load and again after each library change (like, save, follow,
create, rename). Only the default request was captured:

```json
{"order": null, "textFilter": "", "features": ["LIKED_SONGS","YOUR_EPISODES_V2",
 "PRERELEASES","PRERELEASES_V2","CLIPS","EVENTS"], "limit": 50, "offset": 0,
 "flatten": false, "expandedFolders": [], "folderUri": null,
 "includeFoldersWhenFlattening": true}
```

Paging: `offset`/`limit`, stop when `offset >= totalCount`. Text search: `textFilter`.
Folders: `folderUri` opens one folder, `expandedFolders` lists folder URIs shown inline,
`flatten` lists everything without folders. The account had no folders, so these were
always the defaults (see Gaps).

Response `data.me.libraryV3` (`__typename: LibraryPage`):

| Field | Meaning |
|---|---|
| `totalCount`, `pagingInfo {limit, offset}` | paging |
| `availableFilters[] {id, name}` | ids seen: `Playlists`, `Artists`, `Albums` (only types present in the library are offered; `name` is localized) |
| `availableSortOrders[] {id, name}` | ids seen: `Recents`, `Recently Added`, `Alphabetical`, `Creator` |
| `selectedFilters[]`, `selectedSortOrder {id, name}` | current state (default `Recents`) |
| `breadcrumbs[]` | folder path when inside a folder |
| `items[]` | `{addedAt {isoString}, depth, item, pinnable, pinned, playedAt {isoString}\|null}` |

`item` is a wrapper with `__typename`, `_uri` and `data`:

| `item.__typename` | `data` fields used |
|---|---|
| `LibraryPseudoPlaylistResponseWrapper` (`spotify:collection:tracks`) | `name`, `count` (number of liked tracks), `image.sources[]` |
| `PlaylistResponseWrapper` | `name`, `description`, `uri`, `format`, `images.items[].sources[]`, `ownerV2.data {name, username, uri}`, `currentUserCapabilities {canEditItems, canView}`, `revisionId`, `attributes[] {key, value}` |
| `AlbumResponseWrapper` | `name`, `uri`, `type` (`ALBUM`, `SINGLE`…), `artists.items[] {profile.name, uri}`, `coverArt.sources[]`, `date {isoString, precision}`, `playability {playable, reason}` |
| `ArtistResponseWrapper` | `profile.name`, `uri`, `visuals.avatarImage.sources[]` |

Image `sources[]` are `{url, width, height}` (width/height may be null). Most entries also
carry `extractedColors.colorDark.hex`.

```json
{"addedAt":{"isoString":"2026-10-15T21:14:42.278Z"},"depth":0,"pinnable":true,"pinned":false,
 "playedAt":null,"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:{id}",
 "data":{"__typename":"Playlist","name":"Myspoti capture test","description":"","format":"",
 "currentUserCapabilities":{"canEditItems":true,"canView":true},
 "images":{"items":[{"sources":[{"url":"https://i.scdn.co/image/…","width":null,"height":null}]}]},
 "ownerV2":{"data":{"__typename":"User","name":"…","uri":"spotify:user:{user}","username":"{user}"}},
 "revisionId":"AAAAA8Zc…","uri":"spotify:playlist:{id}"}}}
```

The Liked Songs entry is always first and `pinned: true`.

### playlist/v2/user/{user}/rootlist (followed and own playlists)

`GET https://spclient.wg.spotify.com/playlist/v2/user/{user}/rootlist?decorate=revision,length,attributes,timestamp,owner,capabilities&bustCache={ms}`

With `accept: application/json` the answer is JSON, not protobuf. After a change the
Web Player re-reads it with `&revision={n},{hash}` instead of `bustCache` (see Revisions).

```json
{"revision":"AAAAA6N1…","length":1,"attributes":{},"timestamp":"1792098820089",
 "contents":{"pos":0,"truncated":false,
  "items":[{"uri":"spotify:playlist:{id}","attributes":{"timestamp":"1792098882278","public":true}}],
  "metaItems":[{"revision":"AAAAAcQB…","length":0,"timestamp":"1792098882241",
   "ownerUsername":"{user}","attributes":{"name":"Ma playlist n° 2","description":"…",
   "format":"…","formatAttributes":[{"key":"…","value":"…"}],"pictureSize":[…]},
   "capabilities":{"canView":true,"canEditItems":true,"canEditMetadata":true,
    "canAdministratePermissions":true,"canDelete":…,"listAttributeCapabilities":{…},…}}]}}
```

`items[i]` and `metaItems[i]` match by index. An empty rootlist has `length: 0` and no
`items`. Only playlists live here; albums and artists come from the collection
(`libraryV3`). Folders were not present (see Gaps).

### areEntitiesInLibrary

`variables: {"uris": ["spotify:track:{id}", "spotify:album:{id}", "spotify:artist:{id}", "spotify:show:{id}", "spotify:episode:{id}", …]}`
(mixed types, up to about 30 per call).

Response `data.lookup[]`, same order as `uris`:

```json
{"data":{"lookup":[{"__typename":"TrackResponseWrapper","data":{"__typename":"Track","saved":false}},
 {"__typename":"AlbumResponseWrapper","data":{"__typename":"Album","saved":false}},
 {"__typename":"ArtistResponseWrapper","data":{"__typename":"Artist","saved":false}},
 {"__typename":"PodcastOrAudiobookResponseWrapper","data":{"__typename":"Audiobook","saved":false}}]}}
```

Use `saved` for the album Save button and the artist Follow button. For tracks the heart
follows `isCurated` (next section); `saved` never came back true in the captures.

### isCurated (track heart)

`variables: {"uris": ["spotify:track:{id}", …]}` (also sent with episode URIs).
Response `data.lookup[].data.isCurated` (bool), same order. True means the track is in
Liked Songs (seen true for the liked track, false after unliking).

### collection/v2/contains

`POST https://spclient.wg.spotify.com/collection/v2/contains?market=from_token`

```json
{"username":"{user}","set":"artistban","items":[{"uri":"spotify:artist:{id}"},{"uri":"spotify:artist:{id}"}]}
```
```json
{"found":[false,false]}
```

Sets seen: `artistban` (artist hidden by "don't play this artist", checked for every
artist on screen) and `ignoreinrecs` (track excluded from taste profile, checked for the
track whose menu is open). This endpoint was not used for likes or saves.

### editablePlaylists (the "Add to playlist" / heart sheet)

`variables: {"offset":0,"limit":50,"textFilter":"","uris":["spotify:track:{id}"]}`

Response `data.me.editablePlaylists` (`EditablePlaylistPage`): `totalCount`,
`pagingInfo`, `items[] {curates, pinned, item}`. `item` uses the same wrappers as
`libraryV3` (`LibraryPseudoPlaylistResponseWrapper` for Liked Songs, then
`PlaylistResponseWrapper` with `name`, `uri`, `images`). `curates` is true when the
given track is already in that list. Only playlists the user can edit are listed.

```json
{"data":{"me":{"editablePlaylists":{"__typename":"EditablePlaylistPage","totalCount":2,
 "pagingInfo":{"limit":50,"offset":0},"items":[
 {"curates":true,"pinned":true,"item":{"__typename":"LibraryPseudoPlaylistResponseWrapper",
  "_uri":"spotify:collection:tracks","data":{"name":"…","uri":"spotify:collection:tracks","image":{…}}}},
 {"curates":false,"pinned":false,"item":{"__typename":"PlaylistResponseWrapper",
  "_uri":"spotify:playlist:{id}","data":{"name":"Myspoti capture test","uri":"spotify:playlist:{id}","images":{…}}}}]}}}}
```

### Liked Songs

The Liked Songs page (`/collection/tracks`) is now a real playlist owned by the user:
`spotify:playlist:{likedId}` with `format: "liked-songs"`. The id is per account
(base62, in the `37i9dQZF1F…` range of Spotify-generated ids). Its tracks load with the
normal playlist operations:

- `fetchPlaylist` `{"uri":"spotify:playlist:{likedId}","offset":0,"limit":25,"enableWatchFeedEntrypoint":true,"includeEpisodeContentRatingsV2":true}`
- `fetchPlaylistContents` `{"uri":…,"offset":25,"limit":50,"includeEpisodeContentRatingsV2":true}` for the next pages
- `playlistPermissions` `{"uri":…}` (Liked Songs: `basePermission: CONTRIBUTOR`, `canEditItems: true`, `canEditMetadata: false`)

`fetchPlaylist` response `data.playlistV2`: `name`, `format`, `ownerV2`, `revisionId`,
`images`, `currentUserCapabilities`, `content {totalCount, pagingInfo, items[]}`.
Each item: `uid` (hex string, identifies the row), `addedAt.isoString`, `addedBy`,
`itemV2.data` (Track: `uri`, `name`, `artists.items[]`, `albumOfTrack {uri, name, coverArt}`,
`trackDuration.totalMilliseconds`, `playability`, `contentRating.label`), `itemV3`
(new trait-based form of the same entity).

`{likedId}` also appears in the answer of `POST spclient/extended-metadata/v0/extended-metadata`
with `extensionKind: 246` (`spotify.contentagnostic.v2.CurationExperienceTrait`), the first
extension answer after the id is already known (see "Finding the Liked Songs id"). Decoded
protobuf of that extension (from a track request in another session):

```
1: "spotify:track:{id}"
2 { 2 { 1: "spotify:playlist:{likedId}" } }      default curation target = Liked Songs
3 { 4 { 1: "spotify:playlist:…"  1: "spotify:playlist:…" } }   other targets
```

The same `{likedId}` is used as `contextUri` by `applyCurations` and in dealer pushes.
`libraryV3` and `editablePlaylists` still name it `spotify:collection:tracks`. Cache
`{likedId}` once found.

### Finding the Liked Songs id

Checked on the cold start capture `01-login` (login, then first load of the Web Player).

- First occurrence on the wire is the request of `lookupChildEntities`
  (`variables.uris: ["spotify:playlist:{likedId}"]`). The client already had the id when it
  sent that request, so the source is not in the capture. The response repeats it at
  `data.lookupEntities[0].uri`. No earlier request or response carries it, in base62, hex,
  raw 16-byte gid or base64 form. Dealer frames and navigation records do not either.
- Not present in `profileAttributes`, `accountAttributes`, `libraryV3` (it names Liked Songs
  `spotify:collection:tracks`), `home`, `feedBaselineLookup`, the rootlist, or the HTML pages.
- The first answer with it is `extended-metadata` (`extensionKind 246`), about 50 ms later.
  There it is in the other-targets list (CurationExperienceTrait field 3, `4 { 1 }`). That
  request was for episodes, and their default target is a different playlist, so do not
  assume the default is Liked Songs.
- Not captured: the body of `POST accounts.spotify.com/login/code/verify` (empty in the file)
  and about 90 static or redirect responses with no stored body. The true source is one of
  those, or browser storage, which the wire capture cannot show.

Fallback sources, in order:

1. `{likedId}` saved from an earlier session of the same account.
2. `extended-metadata` `extensionKind 246` for any track or episode (default target or
   other targets, see the decode above).
3. A dealer push on `hm://playlist/v2/playlist/{likedId}` (payload field 1), after the first
   like change.

## Editing the library

All mutations answered HTTP 200 with an empty typed payload. The UI then re-ran
`libraryV3` and relied on dealer pushes for the rest.

### addToLibrary (like a track, save an album, follow an artist)

```json
{"variables":{"libraryItemUris":["spotify:track:{id}"],"interactionId":"{uuid}"},
 "operationName":"addToLibrary","extensions":{…}}
```
`interactionId` is a fresh UUID (sent as `null` from some buttons). The same call was
used for `spotify:track:`, `spotify:album:` and `spotify:artist:`.

Response: `{"data":{"addLibraryItems":{"__typename":"AddLibraryItemsResponse"}}}`

### removeFromLibrary (unsave album, unfollow artist, unlike from the player bar)

```json
{"variables":{"libraryItemUris":["spotify:album:{id}"]},"operationName":"removeFromLibrary",…}
```
Response: `{"data":{"removeLibraryItems":{"__typename":"RemoveLibraryItemsResponse"}}}`.
Also seen for `spotify:track:` (unlike from the now-playing heart, session 08).

### applyCurations (unlike from a track row)

Clicking the filled heart on a row opens the sheet (`editablePlaylists`), unticking
Liked Songs sends:

```json
{"variables":{"input":{"itemUris":["spotify:track:{id}"],
 "curations":[{"contextUri":"spotify:playlist:{likedId}","curationType":"UNCURATE"}]}},
 "operationName":"applyCurations",…}
```
```json
{"data":{"applyCurations":[{"__typename":"TrackResponseWrapper","data":{"__typename":"Track","isCurated":false}}]}}
```
Only `UNCURATE` was captured. Several `curations` entries (one per playlist ticked or
unticked) are allowed by the shape. For a simple unlike, `removeFromLibrary` with the
track URI does the same job.

## Editing playlists

### Revisions

A revision is base64 of 24 bytes: 4-byte big-endian counter then a 20-byte hash.
Example `AAAAAU2+Oz2u…` = counter 1, hash `4dbe3b3d…`. The `revision=` query parameter
uses the decoded form `{counter},{hash hex}`.

Change responses share one shape:

```json
{"revision":"{rev}","syncResult":{"fromRevision":"{rev}","toRevision":"{rev}"},
 "resultingRevisions":["{rev}"],"multipleHeads":false,"changesRequireResync":false}
```
`playlist/{id}/changes` also returns `capabilities` (same object as in rootlist
`metaItems`).

### Create a playlist

1. `POST https://spclient.wg.spotify.com/playlist/v2/playlist`
   ```json
   {"ops":[{"kind":"UPDATE_LIST_ATTRIBUTES",
     "updateListAttributes":{"newAttributes":{"values":{"name":"Ma playlist n° 2"}}}}]}
   ```
   Response: `{"uri":"spotify:playlist:{id}","revision":"{rev}"}`
2. `POST https://spclient.wg.spotify.com/playlist/v2/user/{user}/rootlist/changes` (ADD below,
   `changesRequireResync: true` in this case)
3. `GET rootlist?…&revision={n},{hash}`, then `libraryV3`, `playlistPermissions`,
   `fetchPlaylist` on the new URI.

The default name is chosen by the client (localized "My Playlist #N").

### Rename (and other list attributes)

`POST https://spclient.wg.spotify.com/playlist/v2/playlist/{id}/changes`

```json
{"deltas":[{"ops":[{"kind":"UPDATE_LIST_ATTRIBUTES",
  "updateListAttributes":{"newAttributes":{"values":{"name":"Myspoti capture test"}}}}],
  "info":{"source":{"client":"WEBPLAYER"}}}]}
```
No base revision was sent. The UI then re-ran `fetchPlaylist` and `libraryV3`.
Description is presumably `values.description` (not captured).

### Follow / unfollow a playlist (rootlist changes)

`POST https://spclient.wg.spotify.com/playlist/v2/user/{user}/rootlist/changes`

Follow (also used after create):
```json
{"deltas":[{"ops":[{"kind":"ADD","add":{"items":[{"uri":"spotify:playlist:{id}",
  "attributes":{"timestamp":"1792098815150"}}],"addFirst":true}}],
  "info":{"source":{"client":"WEBPLAYER"}}}]}
```
`timestamp` is the client time in ms, as a string.

Unfollow:
```json
{"deltas":[{"ops":[{"kind":"REM","rem":{"items":[{"uri":"spotify:playlist:{id}"}],
  "itemsAsKey":true}}],"info":{"source":{"client":"WEBPLAYER"}}}]}
```
`itemsAsKey: true` removes by URI, no index needed. After each call: `GET rootlist`
with the new revision, then `libraryV3` twice.

### addToPlaylist

```json
{"variables":{"playlistUri":"spotify:playlist:{id}","playlistItemUris":["spotify:track:{id}"],
 "newPosition":{"moveType":"BOTTOM_OF_PLAYLIST","fromUid":null},"interactionId":"{uuid}"},
 "operationName":"addToPlaylist",…}
```
Response: `{"data":{"addItemsToPlaylist":{"__typename":"AddItemsToPlaylistPayload"}}}`.
Only `BOTTOM_OF_PLAYLIST` was seen; `fromUid` suggests other move types positioned
relative to a row `uid` (not captured). The UI then re-ran `fetchPlaylistMetadata`.

### playlistextender/extendp (Recommended songs under an own playlist)

`POST https://spclient.wg.spotify.com/playlistextender/extendp/`

```json
{"playlistURI":"spotify:playlist:{id}","trackSkipIDs":[],"numResults":20}
```
Response:
```json
{"recommendedTracks":[{"id":"6U9VpmP3dEinuezxCJ2Zg9","originalId":"spotify:track:6U9VpmP3dEinuezxCJ2Zg9",
  "name":"Ain't In LA","artists":[{"id":"2qanRMyA5bNuTvz1dK45OP","name":"ADÉLA"}],
  "album":{"id":"2yDFVH9CeOHt0sc9eI0aBs","name":"PRIMA","imageUrl":"https://i.scdn.co/image/…","largeImageUrl":"https://i.scdn.co/image/…"},
  "duration":184927,"explicit":true,"popularity":91,"score":98.0,"contentRating":[]}, …],
 "request":{"playlistURI":"spotify:user:{user}:playlist:{id}","trackIDs":[],"artistIDs":[],…},
 "details":{"playlistLoad":8.0,"totalTime":43.0}}
```
`trackSkipIDs` presumably holds ids already shown or dismissed (for "Refresh").
Adding one of them uses `addToPlaylist`.

## Dealer pushes

On the dealer WebSocket (see CAPTURE-MAP) each message is
`{"type":"message","uri":"hm://…","headers":{…},"payloads":["<base64 or text>"]}`.
They arrived before the HTTP response of the mutation that caused them, and also come
from changes made on other devices, so use them to refresh caches.

### Collection (likes, saved albums, followed artists)

| uri | Content-Type | payload |
|---|---|---|
| `hm://collection/collection/{user}` | `application/octet-stream` | protobuf, tracks and albums |
| `hm://collection/collection/{user}/json` | `text/plain` | JSON, same change |
| `hm://collection/artist/{user}` | `application/octet-stream` | protobuf, artists |
| `hm://collection/artist/{user}/json` | `text/plain` | JSON, same change |

Headers: `MC-ETag` (new collection version), `Collection-Source-Revision` (previous one),
sometimes `Collection-Update-Id`.

JSON form (easiest to use):
```json
{"items":[{"type":"track","unheard":false,"addedAt":1792099111,"removed":false,
  "identifier":"4EoJ151oQ5jY48z4RhSE96","itemData":{"saveSource":{"interactionId":"{uuid}"}}}]}
```
`type` seen: `track`, `album`, `artist`. Removal: `"removed":true,"addedAt":0`.

Protobuf form, field numbers:
```
1 (item, repeated) {
  1: type      varint: 0 = track, 1 = album, 4 = artist
  2: gid       16 bytes (base62 id decoded)
  5: addedAt   varint, unix seconds (0 on removal)
  6: removed   varint 0/1
  8: itemData { 1 { 2: interactionId string } }
}
```

### Playlists

| uri | When |
|---|---|
| `hm://playlist/v2/user/{user}/rootlist` and `hm://playlist/user/{user}/rootlist` | rootlist changed (follow, unfollow, create) |
| `hm://playlist/v2/playlist/{id}` | a playlist changed (items added, also seen when merely opening an editorial playlist) |
| `hm://playlist/v2/playlist/{likedId}` | Liked Songs changed (like/unlike) |
| `hm://playlist/v2/list/liked-songs-artist/{artistId}` | the per-artist liked-songs list of that track's artist changed |

Payload is base64 protobuf:
```
1: uri               "spotify:user:{user}:rootlist" / "spotify:playlist:{id}" / "spotify:list:liked-songs-artist:{artistId}"
2: new revision      24 bytes (counter + hash, as above)
3: previous revision 24 bytes (when present)
4: ops (repeated)    e.g. kind 2 = add:
     { 1: 2  2 { 1: fromIndex  2 { 1: "spotify:track:{id}"
                                   2 { 1: addedBy "{user}"  2: timestamp ms  17 { 2: interactionId } } } } }
5: { 1: seconds  2: nanos }   change time
6: string                     server name
9: varint                     0 or 1
```
Rootlist and liked-songs-artist pushes carried only fields 1, 2 and 9. Simplest client
rule: on any playlist push, re-fetch that playlist (or the rootlist / `libraryV3`) unless
the revision equals the one already held.

## Leaving a collaborative playlist

The playlist menu of the Web Player shows "Leave playlist" only when the playlist's
`currentUserCapabilities.canCancelMembership` is true (a member who is not the owner; same flag
in `fetchPlaylist` and in the rootlist item `permissions`). The entry opens a confirmation
dialog (`leave-playlist.dialog.*`: title, a description that depends on private / public
contributor / public listener, Cancel, Leave). Read from the bundle (`xpui-root-dialogs`, module
`PlaylistPermissionsAPI`; not sent on a live account yet):

| Step | Call |
|---|---|
| private playlist only (base permission `BLOCKED`) and in the library | `RootlistAPI.remove([{uri}])` (the playlist leaves the library) |
| the leave itself | page service `PlaylistPermissionsAPI.removeMember(playlistUri, ownUsername)` |
| then | the dialog closes and the page navigates to `/` |

`removeMember` sends spclient `DELETE /playlist/{id}/permission/member/user/{username}` (JSON content
type, no body, no market; endpoint id `/playlist/{id}/permission/member/user/{username}`). The
username is the account's own (`UserAPI.getUser().username`); the same call, with someone else's
username, is the owner's "Remove from playlist" in the members list. A public playlist stays in the
library after leaving (followed, read-only); to join again a new invitation link is needed
(`claimPermissions`). In the app: `Playlists.leave(uri)`, offered in the playlist's menu when
`Playlist.canLeave`. Guests also get "Leave playlist" on their own row of the members sheet.

## Managing members and stopping a collaboration (owner)

The Web Player's "Shared with" list (`xpui-root-dialogs`, `permissions.*` strings) has a menu per
member, gated by `capabilities.canAdministratePermissions`:

| Menu entry | Call |
|---|---|
| "Make collaborator" (listener row) / "Make listener" (collaborator row) | page service `PlaylistPermissionsAPI.setMemberPermission(playlistUri, username, "CONTRIBUTOR" \| "VIEWER")` |
| "Remove from playlist" | `PlaylistPermissionsAPI.removeMember(playlistUri, username)` |

`setMemberPermission` first reads `GET /playlist/{id}/permission/members` (`{memberPermissions:{username:{revision}}}`)
and fails when the user is not a member, then sends spclient `POST /playlist/{id}/permission/member/user/{username}`
with `{revision, permissionLevel}`. In the app: `Playlists.setMemberCollaborator`, `Playlists.removeMember`
(the members sheet; the menu shows on every non-owner row for the owner).

The Web Player has **no "stop collaborating"** (its "Invite as collaborator" switch of the share dialog only
adds the `pt` token to the copied link), and **no call to revoke an invitation link**: the only
`permission-grant` calls in the bundles are `POST …/permission-grant` (create, `ttlMs` 7 days) and
`POST …/permission-grant/token/{pt}/claim`. So `Playlists.stopCollaboration(uri)` is built from the member
calls: each collaborator becomes a listener when the playlist is private (not listed on the profile) or is
removed when it is public, then the base permission goes back to `BLOCKED` (private) / `VIEWER` (public).
Links already shared are not revoked; the app's confirmation says so. Not tested on a real account.

## Blend ("Mix à plusieurs")

The Web Player has Blend routes (`/blend/invitation`, `/blend/taste-match/{id}`), all on one
spclient service, `https://spclient.wg.spotify.com/blend-invitation` (no GraphQL operation,
no page service). Checked live on the free test account (token and client-token as for any
spclient call):

| Call | Answer |
|---|---|
| `POST /v1/generate` (empty body) | `201 {"invite":"https://open.spotify.com/blend/taste-match/{id}?si=…&fallback=getapp&blendDecoration=…"}` |
| `GET /v3/view-invitation/{id}` | `200 {page_type, members, recipient{name,username,image_url,hash}, playlist_uri, title, subtitle, members_title, button_text, footnote}` |
| `PUT /v2/join/{id}` (empty body) | `200 {"playlist_uri":"spotify:playlist:37i9dQZF1E…"}` |

`page_type`: `PENDING_INVITATION` (own invitation, nobody joined), `READY_TO_JOIN_EMPTY_BLEND` /
`READY_TO_JOIN_ALREADY_CREATED_BLEND` (someone else's, open), `MAX_MEMBERS`, `ALREADY_JOINED`
(then `playlist_uri` is set), `DELETED`. The Blend playlist only exists once a second account
has joined: joining one's own invitation returned a `playlist_uri` that `fetchPlaylist` answers
"not found" and that sits in the rootlist as a `placeholder` item (removed again with
`RootlistAPI.remove`). An invitation cannot be revoked. A real Blend is an ordinary playlist
(`format: "blend"` in `fetchPlaylist`), opened, played and listed like any other. A link
`open.spotify.com/blend/taste-match/{id}` opened in the app shows the invitation (view, then join).

The Blend page itself (members, taste match, rename, leave) uses calls the Web Player has no
screen for; they are the ones Spotify's mobile apps make for that page. The two reads were
checked live on a real two-member Blend (21/10), and rename; leaving is sent by the app, its
answer not captured:

| Call | Answer |
|---|---|
| `GET /v3/get-invitation/{playlistId}` | `200 {title, button_text, invitation_link, members[{name, image_url, hash, username}]}` (`image_url` may be `""`) |
| `GET /v3/data-stories/{playlistId}` | `200 {intro_story{title1{text,text_color}, subtitle1, background_color, audio_uri}, stories[{story_type, title, subtitle, body, image, background_color, button{navigation_uri}}]}` |
| `PUT blend-writer/v1/rename/{playlistId}` `{"name":…}` | `200`, empty body (checked with the Blend's own name) |
| `PUT /v1/leave` `{"playlist_uri":"spotify:playlist:…"}` | answer not captured (sent when leaving a Blend; nothing in it is read) |

`blend-writer` is a sibling of `blend-invitation` on the same host. The texts (titles, button
words, story lines) come translated in the account's language.

## Gaps

`removeFromPlaylist`, `MOV` (both `moveTracks` and drag-and-drop), playlist delete, folders
(`libraryV3`'s `folderUri`/`expandedFolders`/`flatten`, the rootlist folder markers and
`RootlistAPI` create/rename/remove), `libraryV3` filters and sorts, pin/unpin, and a
playlist's description, cover, access and collaborators are all implemented in
`base/api-spotify` now (see `SpotifyLibrary`). Rows are addressed by `uid`, never by track
uri: the same song twice has two. What is still missing:

- `applyCurations` with `CURATE` (adding through the heart sheet), multi-playlist curations
  (Myspoti saves with `addToLibrary` instead).
- Error responses (rate limit, revision conflict, permission denied).
