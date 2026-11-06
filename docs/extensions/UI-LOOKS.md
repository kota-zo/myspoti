# Look-only extensions (`ui.style`)

A look-only extension changes how Myspoti looks, nothing else: colours, parts of the app it hides,
the order of the Classic tabs, the text size and a few fixed blocks on Home. Everything is
**declared** in `manifest.json` under `ui`; the extension never draws and needs no code. The app
applies the look on its own pages, with its own fonts, icons and spacing.

It is an ordinary extension package (see [API.md](API.md), sections 1 and 2) with one action,
`ui.style`, and the `ui` object. Everything else in API.md still holds: the install prompt, the
store, `myspoti-ext pack` and `myspoti-ext check`, the switch and the order on the Installed tab.

## 1. A minimal look

`manifest.json`:

```json
{
  "id": "io.github.you.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "You",
  "category": "look",
  "actions": [
    { "name": "ui.style", "why": "Warm orange colours and Library first" }
  ],
  "ui": {
    "colors": { "accent": "#FF9E3D", "background": "#140C08" },
    "order": { "nav": ["library", "home", "search"] }
  }
}
```

`main.js` is still needed by the package format; for a look it can be a single comment:

```js
// Look only: everything this extension changes is in manifest.json ("ui").
```

A script can live next to the look (an extension may list `ui.style` with any other action, for
example `home.sections`): the look stays declared, whatever the script does.

`docs/extensions/samples/sunset-look` uses every field.

## 2. The `ui` object

Every field is optional, but `ui` must change something. A value the app cannot use refuses the
package (`myspoti-ext check` says why); a name this Myspoti does not know (a colour, a part, a
selector...) is left aside and `check` warns, so a look written for a newer app still installs.

| Field | Type | What it does |
|---|---|---|
| `colors` | object | Colours of the app, as `#RRGGBB` or `#AARRGGBB` (see below). |
| `hide` | string[] | Parts of the app to hide, 20 at most (see below). |
| `order` | object | `nav`: the Classic look's bottom tabs, each of `home`, `search`, `library` once. The Immersive tab bar keeps its own layout. |
| `styles` | object | CSS-like rules: a selector and its properties (see below). |
| `blocks` | object | `home`: native blocks shown on Home under the extension's name. |

### `colors`

| Key | Classic look | Immersive look |
|---|---|---|
| `accent` | The accent (buttons, switches, the selected tab, links) | The accent |
| `background` | The page colour (instead of #121212) | The page colour (instead of black) |
| `surface` | Raised greys (grid tiles, cards) | Raised surfaces (#1C1C1E) |

- The text stays white: a `background` or `surface` that is see-through, or too light for white
  text (relative luminance above 0.2), is not applied. The accent can be any colour.
- The **AMOLED black** setting wins over `background`: the user chose it to save power.
- A look's accent replaces the one chosen in Settings while the extension is on.

### `hide`

Each part is one the user can also turn off in Settings → Display; the switch there keeps the
user's own value, the look only hides on top of it.

| Part | What it hides |
|---|---|
| `home.dj` | Spotify's DJ on Home |
| `player.lyrics-card` | The lyrics card under the Classic player (a swipe up on the cover still opens the lyrics) |
| `player.source` | The line under the player's title that says what plays and how (Sound quality and Stats for nerds move to the player's menu) |
| `player.capsule` | The capsule at the bottom of the Immersive player (Listen on moves to the player's menu) |

### `styles`

A rule is a selector and its properties, as in CSS but from a closed list:

```json
"styles": { "text": { "scale": 1.1 } }
```

| Selector | Property | Values | Effect |
|---|---|---|---|
| `text` | `scale` | 0.85 to 1.3 | All the app's text, on top of the phone's own font size |

### `blocks`

`home`: a list of native blocks, the same as a `home` hook answer (API.md section 3, "Blocks"),
fixed in the manifest: a `card` or `image` that opens a `spotify:` uri, a `text`, a list of
`tracks`, `albums`, `playlists` or `artists`. They are shown with the other extensions' Home
blocks, under "From <your extension>", with the same quotas. A block with an `action` calls the
extension's `tap` hook, so it needs a script that answers it.

```json
"blocks": {
  "home": [
    { "type": "card", "title": "Sunset mix", "subtitle": "Your evening playlist", "open": "spotify:playlist:37i9dQZF1DX6R7QUWePReA" }
  ]
}
```

## 3. Several looks

Looks are applied in the order of the **Pages & more** area on the Installed tab (arrows): for
each colour, the tab order and each style, the highest look that sets it wins; hidden parts add
up; every look's Home blocks are shown. Turning a look off (or uninstalling it) gives the app its
own look back at once.

## 4. What a look cannot do

- Restyle a protected surface (API.md section 4.3): while the donation reminder, the Support
  sheets, the "New version" card, the install prompt, a critical confirmation, the Report button
  or About is on screen, the whole app is back to its own look. No part of `hide` names one.
- Draw anything itself, load a font, an image as a background, or run code from the look.
- Hide the player's controls, the tab bar, Settings or the Extensions screen.

## 5. Compatibility

- `ui.style` and `ui` are new: an older Myspoti refuses a package that lists `ui.style` (an unknown
  action), so set `minAppVersion` to the first release that has it if you publish one.
- A manifest with a `ui` object but without the `ui.style` action installs as before and its `ui`
  is ignored (`check` warns): `ui` was an unknown key until now, and unknown keys are ignored.
- Nothing about the other actions, hooks or the `myspoti` object changed.
