# Extensions: a guide for users

Myspoti plays Spotify on its own. Extensions add what it does not do itself. Each one is a small
script that runs in a sandbox and only does what it asked for when you installed it.

For extension authors, see [API.md](API.md).

## What kinds there are

| Kind | What it does | Where you see it |
|---|---|---|
| **Audio source** | Gives the audio of the songs you play, from a source of its own (its quality, often lossless). A song it has no audio for plays from Spotify, as before. | Settings, Playback: the source order and the extension's quality |
| **Download source** | Lets you download songs, albums, playlists and Liked Songs to play with no connection. Myspoti keeps the files encrypted on your phone. | The download button on songs and lists; Settings, Downloads |
| **Lyrics** | Lyrics from other catalogues when Spotify has none, or word by word. | The lyrics screen |
| **Other** | Home or search shelves, player buttons, tools that use your Spotify account. | Where the extension says, after you install it |

Myspoti ships no audio or download source of its own: what an extension plays or downloads comes
from where its author says, under that author's responsibility.

## Installing one

1. Open **Settings, Extensions**.
2. Either:
   - **From a repository**: on the **Store** tab, add the repository address the extension's
     author gives you (it ends in `index.json`), then pick the extension in the list. A repository
     keeps its extensions up to date.
   - **From a package**: install the `.zip` file you were given, or paste its address.
3. Read what the extension will do. Every action it asks for is listed with its author's reason,
   and the critical ones are marked. You install it on your own responsibility, and only install
   from people you trust.
4. Some extensions need a sign-in or a setting of their own: their page opens after the install.

An update that asks for more than before waits for your OK and shows only what changes.

## The order of the sources

With several audio sources, Settings, Playback lists them in order: a song is asked to the first,
then the next, and plays from Spotify when none has it. A newly installed extension goes first.
Drag to change the order, or switch one off without removing it.

## When something does not work

| You see | What it usually means | What to try |
|---|---|---|
| The extension says the song is not available or "not reachable from the source", or the song plays from Spotify | The extension's source has no copy of that song (or matched none) | Nothing is wrong in Myspoti: the song plays from Spotify. Another source may have it. |
| "Not answering for now", a source named on a failed download | The extension's server is down, slow or limits you | Try again later; the app sets a source aside for a while after repeated timeouts. |
| An extension that cannot sign in or connect | Often a VPN, a DNS blocker or an expired session | Turn the VPN off for the sign-in, then sign in again from the extension's page. |
| "No matching recording found" on downloads | The download source found no file of the right length for that song | Try another quality, or another download source. |
| Downloads stop when the app is closed | The phone's battery saver stops the app | Settings, Battery: let Myspoti run in the background. |

Report a problem to the extension's author first: Myspoti cannot change what a source answers.
For Myspoti itself, **Export a diagnostic report** in Settings writes one with personal details
masked, to attach to your report.

## Android Auto

Android Auto lists only apps installed from the Play Store, unless you allow others: in the
Android Auto settings on the phone, tap **Version** about ten times to unlock the developer
settings, then turn on **Unknown sources**. Myspoti then shows in the car's app list.

## Android TV

On a TV, Myspoti opens a short version for the remote: your Library, Liked Songs, Search and the
player. Install the `armeabi-v7a` APK on most TV boxes (the `arm64-v8a` one on recent ones), and
sign in once with the remote. Extensions, downloads and settings stay on the phone version.
