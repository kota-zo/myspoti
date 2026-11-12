#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Cuts a Mornye extension down to its metadata: the functions its metadata entry points can reach, nothing else.

usage: strip_metadata.py <folder with <folder>/<source>/index.js> <output folder> <upstream commit>
"""
import re
import sys

SPECS = {
    "deezer": dict(
        source="deezer",
        entries=["customSearch", "handleURL", "getTrack", "getAlbum", "getArtist", "getPlaylist", "enrichTrack", "getRelatedTracks", "searchTracks", "initialize", "cleanup"],
        register="""registerExtension({
  initialize: initialize,
  cleanup: cleanup,
  customSearch: customSearch,
  handleUrl: handleURL,
  getTrack: getTrack,
  getAlbum: getAlbum,
  getArtist: getArtist,
  getPlaylist: getPlaylist,
  enrichTrack: enrichTrack,
  searchTracks: searchTracks,
  getRelatedTracks: getRelatedTracks
});""",
        drop_config=["resolverBaseURL", "resolverDownloadPath", "gatewayURL", "blowfishSecret", "blowfishIVHex", "gatewayTokenTtlMs"],
        # The public API only: what its private gateway adds is optional everywhere.
        stubs={"safeGatewayCall": "function safeGatewayCall(method, body) {\n  return null;\n}\n",
               "fetchGatewayTrackData": "function fetchGatewayTrackData(trackID) {\n  return null;\n}\n",
               "fetchGatewayAlbumData": "function fetchGatewayAlbumData(albumID) {\n  return null;\n}\n",
               "fetchGatewayAlbumTracks": "function fetchGatewayAlbumTracks(albumID, trackItems) {\n  return {};\n}\n",
               "initialize": "function initialize(settings) {\n  return true;\n}\n",
               "cleanup": "function cleanup() {\n  metadataCache = new Map();\n  return true;\n}\n",
               "userAgentForURL": "function userAgentForURL(url) {\n  return utils.randomUserAgent();\n}\n"},
        drop_globals=["gatewayToken", "gatewayTokenCreatedAt"],
    ),
    "tidal": dict(
        source="tidal-web",
        entries=["customSearch", "handleUrl", "getTrack", "getAlbum", "getArtist", "getPlaylist", "enrichTrack", "getRelatedTracks", "searchTracks", "initialize", "cleanup"],
        register="""registerExtension({
  initialize: initialize,
  cleanup: cleanup,
  customSearch: customSearch,
  handleUrl: handleUrl,
  getTrack: getTrack,
  getAlbum: getAlbum,
  getArtist: getArtist,
  getPlaylist: getPlaylist,
  enrichTrack: enrichTrack,
  searchTracks: searchTracks,
  getRelatedTracks: getRelatedTracks
});""",
        drop_config=["downloadAPIURL", "downloadPath", "resolverBaseURL"],
        stubs={"initialize": """function initialize(settings) {
  settings = settings || {};
  var countryCode = String(settings.countryCode || "").trim().toUpperCase();
  if (countryCode) CONFIG.countryCode = countryCode;
  return true;
}
"""},
        drop_globals=[],
    ),
    "ytmusic": dict(
        source="ytmusic-spotiflac",
        entries=None,
        register=None,
        drop_config=["cobaltAudioURL", "poTokenCacheMaxEntries", "poTokenMode", "poTokenProviderURL", "manualGvsPoToken", "poTokenFallbackTtlMs", "yt1dResultsURL", "yt1dAjaxURL"],
        members=["customSearch", "getTrack", "getAlbum", "getPlaylist", "getArtist", "getHomeFeed", "getRelatedTracks", "enrichTrack"],
        # Only the catalogue endpoints: a track is known from a search, an album or a playlist.
        stubs={"fetchVideoMetadataSync": "function fetchVideoMetadataSync(videoId) {\n  return null;\n}\n", "L": "function L() {}\n"},
        drop_globals=[],
        drop_blocks=["INNERTUBE_CLIENTS", "YOUTUBE_AUDIO_FORMAT_PREFERENCE", "_poTokenCache"],
    ),
}


def split(source):
    """Top-level functions by name, and everything else, in order."""
    parts = []
    last = 0
    for m in re.finditer(r"^(?:async )?function\s+([A-Za-z0-9_$]+)\s*\(", source, flags=re.M):
        if m.start() > last:
            parts.append((None, source[last:m.start()]))
        # A top-level function ends at the first closing brace in column 0.
        end = re.compile(r"^\}[ \t]*;?[ \t]*$", re.M).search(source, m.start())
        stop = end.end() if end else len(source)
        parts.append((m.group(1), source[m.start():stop] + "\n"))
        last = stop
    parts.append((None, source[last:]))
    return parts


def calls(text):
    """The names a piece of code refers to: not in comments or strings, not properties."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"\"(?:[^\"\\\n]|\\.)*\"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`", '""', text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    names = set(re.findall(r"(?<![.\w$])([A-Za-z_$][A-Za-z0-9_$]*)\b(?!\s*:(?!:))", text))
    # A member such as `getTrack: getTrack` counts by its value, already matched after the colon.
    return names


def strip(spec, source):
    if spec["register"] is None:
        m = re.search(r"^registerExtension\(\{.*?^\}\);", source, flags=re.M | re.S)
        object_text = m.group(0)
        spec["register"] = keep_members(object_text, spec["members"])
    source = re.sub(r"^registerExtension\(\{.*?^\}\);", "@@REGISTER@@", source, count=1, flags=re.M | re.S)
    for key in spec["drop_config"]:
        source = re.sub(r"^\s*%s\s*:.*?,?\s*$\n" % re.escape(key), "", source, flags=re.M)
    for name in spec["drop_globals"]:
        source = re.sub(r"^var %s\b.*$\n" % re.escape(name), "", source, flags=re.M)
    for name in spec.get("drop_blocks", []):
        source = re.sub(r"^(?:var|const) %s\b.*?^\];?\s*$\n|^(?:var|const) %s\b[^\n\[]*;\s*$\n" % (name, name), "", source, count=1, flags=re.M | re.S)
    parts = split(source)
    bodies = {n: spec["stubs"].get(n, t) for n, t in parts if n}
    roots = set(spec["entries"] or []) | calls(spec["register"])
    for n, t in parts:
        if n is None:
            roots |= calls(t.replace("@@REGISTER@@", ""))
    seen, stack = set(), [r for r in roots if r in bodies]
    while stack:
        n = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        stack += [c for c in calls(bodies[n]) if c in bodies and c not in seen]
    out = "".join(bodies[n] if n else t for n, t in parts if n is None or n in seen)
    out = out.replace("@@REGISTER@@", spec["register"])
    return re.sub(r"\n{3,}", "\n\n", out), len(seen), len(bodies)


def keep_members(text, names):
    """The members of the object literal in registerExtension that are in names, brace-matched."""
    body = text[text.index("{") + 1: text.rindex("}")]
    members, i = [], 0
    while i < len(body):
        m = re.compile(r"\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:").match(body, i)
        if not m:
            break
        depth, j, quote = 0, m.end(), None
        while j < len(body):
            c = body[j]
            if quote:
                if c == "\\":
                    j += 1
                elif c == quote:
                    quote = None
            elif c in "'\"`":
                quote = c
            elif c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            elif c == "," and depth == 0:
                break
            j += 1
        if m.group(1) in names:
            members.append(body[m.start():j].strip())
        i = j + 1
    return "registerExtension({\n  " + ",\n  ".join(members) + "\n});"


def main():
    src, out, commit = sys.argv[1:4]
    for name, spec in SPECS.items():
        text = open(f"{src}/{spec['source']}/index.js", encoding="utf-8").read()
        result, kept, total = strip(spec, text)
        head = (
            f"// SPDX-License-Identifier: LGPL-3.0-only\n"
            f"// From Mornye Extension (https://github.com/mornyeapp/Mornye-Extension), sources/{spec['source']}/index.js\n"
            f"// at {commit}. Copyright 2026 zarzet.\n"
            f"// Modified: cut down to its metadata by catalog/tools/strip_metadata.py. See LICENSE-LGPL-3.0.txt.\n"
        )
        open(f"{out}/{name}.js", "w", encoding="utf-8").write(head + result)
        print(name, "functions kept", kept, "of", total, "lines", result.count("\n"))


main()
