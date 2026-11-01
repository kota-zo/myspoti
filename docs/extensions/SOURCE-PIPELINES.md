# Source transforms and racing (experimental)

Feature-detect exact strings in `myspoti.features`; never infer these from an app version.

## `download.transform` and `audio.transform`

A download file or audio answer may contain:

```json
{"transform":{"algorithm":"blowfish-cbc-pattern","keyHex":"000102030405060708090a0b0c0d0e0f","ivHex":"0001020304050607","segmentBytes":2048,"every":3,"offset":0}}
```

The host knows no provider or key-derivation scheme. The extension supplies the key and IV.
CBC has no padding and restarts its IV for each selected complete segment. Segment indices
start at zero; only indices congruent to `offset` modulo `every` are transformed. The final
incomplete segment is copied unchanged. Key length is 4–56 bytes, IV exactly 8 bytes,
segment size 8–65536 bytes and divisible by 8; `every` is 1–1024 and `offset` is below it.
Malformed or unknown transforms are rejected, never silently ignored. DASH cannot carry one.

Downloads transform into a temporary file before the existing audio identity/length checks
and local encryption. Cancellation deletes the temporary output. Streaming decrypts on demand
with bounded buffers; byte-range seeks align upstream to a segment boundary, preserve absolute
segment indices, and discard the prefix before the requested byte. A bounded read still fetches
complete cipher segments. Encrypted read-ahead heads are not reused as decoded data.

Do not return an encrypted file without the appropriate feature. A normal clear file omits
`transform` entirely. Never log session secrets or derived keys.

## `sources.race`

```js
myspoti.provide('download-source', function (query) {
  if (!query.source) {
    return myspoti.sources.race('download-source', query, ['one', 'two']);
  }
  return resolveOnly(query.source, query);
});
```

Also supports `audio-source`. One to four named lanes run independent copies of the same
extension script, receiving `query.source`. They share the extension's own storage/settings,
but never a Rhino scope. Top-level extension code must therefore be idempotent. Do not do
pairing or other UI work in a lane. No nested races; source workers cannot call Spotify APIs
or open sign-in windows. Network requests retain host checks and a shared parent HTTP quota;
notifications share the parent's quota. Root hook time limits include all lane work.

The first non-empty parseable answer wins; a CD/hi-res download cannot win with a lossy file.
A failed/empty lane does not win. Remaining workers are interrupted and closed. Transport
cancellation remains best-effort and bounded by request timeouts. This selects a descriptor;
normal file validation still decides whether the audio is the requested recording. A hi-res
preference with CD fallback remains a preference; implement strict quality in each resolver
if required. Old hosts must keep a sequential fallback instead of calling this API.
