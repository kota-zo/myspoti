# Updating the metadata scripts

The metadata scripts (`deezer.js`, `tidal.js`, `ytmusic.js`) can be replaced without a new version of the app.
The app keeps its bundled scripts as the fallback and uses a downloaded set only if it is signed and newer.

What the app checks before it keeps a set: the signature of `manifest.json` (ECDSA P-256 / SHA-256, public key in
`ScriptUpdates.kt`), the manifest format and `minApi`, a `version` greater than the bundled one and the cached one,
and the size and SHA-256 of every script. The set is written whole and switched on in one rename; it is verified
again each time it is loaded. A script never decides what it may reach: the hosts, HTTPS-only rule and time limit
come from the app (`ScriptSpec`, `ScriptEngine`), so a new host needs a new version of the app.
`prelude.js` is part of the app and is not updatable.

## When the app looks

About 45 s after it starts, then whenever it is due: a day after the last answer, sooner after a failure to reach
the set (an hour, then twice as long each time, a day at most). Never with the app's data saver on, nor on a metered
network where Android restricts background data. A set kept is used from the next search on. `catalog.update`
(`tools/myspoti-cli`, debug and beta builds) looks at once, `catalog.status` says what is in use.

A downloaded script that fails by itself (an error it throws, an answer that is not one) is set aside for the
bundled script of that provider until the app restarts, and the call is asked again of the bundled one; one that
only ran too long gives that one call to the bundled script. When the bundled script fails too, the provider is
down for that call and the next one in the user's order answers.

## Publish

1. Edit the scripts in a working folder (or start from `src/main/resources/app/myspoti/catalog/metadata/`).
2. Pick a `version` higher than the last published one.
3. Sign (the private key is kept outside the repository and must never be committed):

       python3 tools/sign_scripts.py <scripts folder> <output folder> <version> <private key.pem>

4. Commit the content of the output folder (`manifest.json`, `manifest.json.sig`, `scripts/*.js`) to the root of the
   publishing repository (`SCRIPTS_BASE_URL` in `ScriptUpdates.kt`).

Keep `README.md`, `LICENSE-LGPL-3.0.txt` and `COPYING-GPL-3.0.txt` next to them (the scripts come from Mornye
Extension, LGPL-3.0-only).

## Try a set before publishing it

A debug build fetches the scripts from another address when told to, and only a debug build accepts plain HTTP, to
the phone's own loopback address only (`http://localhost:<port>/` or `http://127.0.0.1:<port>/`); the signature is
checked the same. With the debug build on the phone, from `base/catalog`:

    python3 tools/sign_scripts.py src/main/resources/app/myspoti/catalog/metadata <publish folder> 2 <private key.pem>
    python3 -m http.server 8000 --bind 127.0.0.1 --directory <publish folder>     # in another terminal
    adb -s <serial> reverse tcp:8000 tcp:8000
    ../../tools/myspoti-cli catalog.source http://localhost:8000/
    ../../tools/myspoti-cli catalog.update      # result: Updated(version=2)
    ../../tools/myspoti-cli catalog.status      # version 2, source cached, each script "downloaded"
    ../../tools/myspoti-cli catalog.source default
    adb -s <serial> reverse --remove tcp:8000

The same path on the computer, against the app's own key: `CATALOG_PUBLISHED_DIR=<publish folder> ../gradlew -p ..
:catalog:test --tests '*LocalServeTest*'` serves the folder on the loopback address and installs it in a scratch
cache (`LocalServeTest` also checks that plain HTTP is refused without the debug override).

To change the key: generate a new P-256 key, replace `SCRIPTS_PUBLIC_KEY`, ship a new version of the app.
