// SPDX-License-Identifier: LGPL-3.0-only
// From Mornye Extension (https://github.com/mornyeapp/Mornye-Extension), sources/ytmusic-spotiflac/index.js
// at 0c326fa36fc130127d90083808c6b51ea6d83aa2. Copyright 2026 zarzet.
// Modified: cut down to its metadata by catalog/tools/strip_metadata.py. See LICENSE-LGPL-3.0.txt.
const CONFIG = {
  fetchTimeoutMs: 15000,
  maxRetries: 2,
  baseBackoffMs: 250,
  cacheTtlMs: 120000,
  cacheMaxEntries: 500,
  thumbnailSize: 512,
  clientVersion: "1.20240801.01.00",
  debugRawJsonHead: 1200,
  maxResults: 12,
  allowlistHosts: [],
  youtubeWatchURL: "https://www.youtube.com/watch?v=",
  logLevel: "warn",
  homeCountry: "ID",
  // InnerTube ANDROID client config
  innerTubeApiKey: "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
  innerTubeClientVersion: "21.02.35",
  innerTubeUserAgent:
    "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
  directAudioChunkSize: 1024 * 1024,
};

const USER_AGENTS = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0",
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
  "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36",
];

function getRandomUserAgent() {
  return USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
}

const LOG_LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

function L() {}

function now() {
  return Date.now();
}

const _cache = new Map();
function cacheGet(k) {
  const e = _cache.get(k);
  if (!e) return null;
  if (now() - e.t > CONFIG.cacheTtlMs) {
    _cache.delete(k);
    return null;
  }
  // Refresh insertion order so eviction follows true LRU semantics.
  _cache.delete(k);
  _cache.set(k, e);
  return e.v;
}

function cacheSet(k, v) {
  if (_cache.has(k)) _cache.delete(k);
  _cache.set(k, { v, t: now() });
  while (_cache.size > CONFIG.cacheMaxEntries) {
    var oldestKey = _cache.keys().next().value;
    _cache.delete(oldestKey);
  }
}

const _inflight = new Map();

function isString(v) {
  return typeof v === "string";
}

function normalizeUrl(u) {
  if (!isString(u)) return null;
  var s = u.trim();
  if (!s) return null;
  if (!/^https?:\/\//i.test(s)) return null;
  try {
    var parsed = new URL(s);
    if (
      Array.isArray(CONFIG.allowlistHosts) &&
      CONFIG.allowlistHosts.length > 0
    ) {
      if (
        CONFIG.allowlistHosts.indexOf(parsed.hostname) === -1 &&
        !/^https?:\/\//i.test(parsed.protocol + "//")
      ) {
        // no-op, primary check already ensures http(s)
      }
    }
    return parsed.toString();
  } catch (e) {
    return null;
  }
}

function isAbsoluteHttpUrl(u) {
  return isString(u) && /^https?:\/\//i.test(u.trim());
}

// Direct clients follow yt-dlp's current client contexts. Android VR 1.65.10
// has returned 403 for all formats since August 2026, so it is not a fallback.
var YOUTUBE_AUDIO_FORMAT_PREFERENCE = [251, 250, 249, 140, 139, 600, 599, 18];

// Returns an array of {url, extension, ua, clientName} for all clients that respond.
// Used for download-level fallback: try downloading from each until one succeeds.

// Resolve and download one InnerTube candidate at a time. Fetching all client
// candidates up front adds three unnecessary player requests when the first
// client succeeds, and also lets otherwise fresh media URLs age before use.

// Build a successful download result that reports the ACTUAL container/codec
// of the written file. YouTube audio is typically Opus/AAC, never FLAC, so the
// host must not assume the requested .flac extension. Reporting the real
// extension lets the generic pipeline name the file and embed metadata with the
// correct format instead of treating Opus bytes as FLAC.

function makeSquareThumb(url) {
  var u = normalizeUrl(url);
  if (!u) return null;
  try {
    var replaced = u
      .replace(
        /=w\d+-h\d+/g,
        "=w" + CONFIG.thumbnailSize + "-h" + CONFIG.thumbnailSize,
      )
      .replace(/\/s\d+-c/g, "/s" + CONFIG.thumbnailSize + "-c");
    return normalizeUrl(replaced);
  } catch (e) {
    return null;
  }
}

function parseDurationText(t) {
  if (!t) return 0;
  var m = String(t).match(/(\d{1,2}:)?\d{1,2}:\d{2}|\d{1,2}:\d{2}/);
  if (!m) return 0;
  var parts = m[0].split(":").map(function (x) {
    return parseInt(x, 10);
  });
  if (
    parts.some(function (p) {
      return isNaN(p);
    })
  )
    return 0;
  var s = 0;
  for (var i = 0; i < parts.length; i++) s = s * 60 + parts[i];
  return s;
}

function extractVideoIdFromEndpoint(ep) {
  try {
    if (!ep) return null;
    if (ep.watchEndpoint && ep.watchEndpoint.videoId)
      return ep.watchEndpoint.videoId;
    if (
      ep.commandMetadata &&
      ep.commandMetadata.webCommandMetadata &&
      ep.commandMetadata.webCommandMetadata.url
    ) {
      var m = String(ep.commandMetadata.webCommandMetadata.url).match(
        /v=([^&]+)/,
      );
      if (m) return m[1];
    }
    if (ep.browseEndpoint && ep.browseEndpoint.browseId)
      return ep.browseEndpoint.browseId;
    return null;
  } catch (e) {
    return null;
  }
}

function extractBrowseInfoFromEndpoint(ep) {
  try {
    if (!ep) return null;
    if (ep.browseEndpoint && ep.browseEndpoint.browseId) {
      var browseId = ep.browseEndpoint.browseId;
      var type = "unknown";
      if (browseId.startsWith("MPREb_")) type = "album";
      else if (
        browseId.startsWith("VLPL") ||
        browseId.startsWith("VL") ||
        browseId.startsWith("PL")
      )
        type = "playlist";
      else if (browseId.startsWith("VLRDCLAK5uy_")) type = "playlist";
      else if (browseId.startsWith("UC")) type = "artist";
      return { browseId: browseId, type: type };
    }
    return null;
  } catch (e) {
    return null;
  }
}

function normalizeCandidate(info) {
  if (!info) return null;
  if (info.musicResponsiveListItemRenderer)
    return info.musicResponsiveListItemRenderer;
  if (info.musicTwoRowItemRenderer) return info.musicTwoRowItemRenderer;
  if (info.musicCardRenderer) return info.musicCardRenderer;
  if (info.videoRenderer) return info.videoRenderer;
  if (info.richItemRenderer && info.richItemRenderer.content)
    return info.richItemRenderer.content;
  if (info.playlistPanelVideoRenderer) return info.playlistPanelVideoRenderer;
  return info;
}

function pickLastThumbnailUrl(thumbnailObj) {
  try {
    if (!thumbnailObj) return null;
    if (Array.isArray(thumbnailObj)) {
      if (thumbnailObj.length === 0) return null;
      var last = thumbnailObj[thumbnailObj.length - 1];
      return last && last.url ? last.url : null;
    }
    if (
      thumbnailObj.thumbnails &&
      Array.isArray(thumbnailObj.thumbnails) &&
      thumbnailObj.thumbnails.length
    ) {
      var l = thumbnailObj.thumbnails[thumbnailObj.thumbnails.length - 1];
      return l && l.url ? l.url : null;
    }
    return null;
  } catch (e) {
    return null;
  }
}

function runsText(runs, separator) {
  if (!Array.isArray(runs)) return "";
  return runs
    .map(function (run) {
      return run && run.text ? String(run.text) : "";
    })
    .join(separator === undefined ? "" : separator)
    .trim();
}

function uniqueStrings(values) {
  var seen = {};
  var out = [];
  for (var i = 0; i < values.length; i++) {
    var value = String(values[i] || "").trim();
    var key = value.toLowerCase();
    if (!value || seen[key]) continue;
    seen[key] = true;
    out.push(value);
  }
  return out;
}

function findFirstRenderer(node, rendererKey, depth) {
  depth = depth || 0;
  if (!node || typeof node !== "object" || depth > 16) return null;
  if (
    Object.prototype.hasOwnProperty.call(node, rendererKey) &&
    node[rendererKey]
  ) {
    return node[rendererKey];
  }
  if (Array.isArray(node)) {
    for (var i = 0; i < node.length; i++) {
      var arrayMatch = findFirstRenderer(node[i], rendererKey, depth + 1);
      if (arrayMatch) return arrayMatch;
    }
    return null;
  }
  for (var key in node) {
    if (
      !Object.prototype.hasOwnProperty.call(node, key) ||
      key === "trackingParams" ||
      key === "clickTrackingParams"
    )
      continue;
    var match = findFirstRenderer(node[key], rendererKey, depth + 1);
    if (match) return match;
  }
  return null;
}

function browsePageType(endpoint) {
  try {
    return String(
      endpoint.browseEndpointContextSupportedConfigs
        .browseEndpointContextMusicConfig.pageType || "",
    );
  } catch (e) {
    return "";
  }
}

function collectTrackReferences(node) {
  var result = {
    artists: [],
    album_id: "",
    album_name: "",
    credits_id: "",
  };
  var seenArtists = {};

  function visit(value, depth) {
    if (!value || typeof value !== "object" || depth > 14) return;
    if (value.browseEndpoint && value.browseEndpoint.browseId) {
      var endpoint = value.browseEndpoint;
      var browseId = String(endpoint.browseId);
      var pageType = browsePageType(endpoint);
      var text = value.__runText || "";
      if (
        (browseId.startsWith("UC") || pageType === "MUSIC_PAGE_TYPE_ARTIST") &&
        text
      ) {
        if (!seenArtists[browseId]) {
          seenArtists[browseId] = true;
          result.artists.push({ id: browseId, name: text });
        }
      } else if (
        (browseId.startsWith("MPREb_") ||
          pageType === "MUSIC_PAGE_TYPE_ALBUM") &&
        !result.album_id
      ) {
        result.album_id = browseId;
        result.album_name = text;
      } else if (
        (browseId.startsWith("MPTC") ||
          pageType === "MUSIC_PAGE_TYPE_TRACK_CREDITS") &&
        !result.credits_id
      ) {
        result.credits_id = browseId;
      }
    }

    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) visit(value[i], depth + 1);
      return;
    }
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
      var child = value[key];
      if (key === "navigationEndpoint" && child && typeof child === "object") {
        var decorated = Object.assign({}, child);
        if (typeof value.text === "string")
          decorated.__runText = value.text.trim();
        visit(decorated, depth + 1);
      } else if (key !== "trackingParams" && key !== "clickTrackingParams") {
        visit(child, depth + 1);
      }
    }
  }

  visit(node, 0);
  return result;
}

function hasExplicitBadge(node) {
  try {
    var badges = node && node.badges;
    if (!Array.isArray(badges)) return false;
    for (var i = 0; i < badges.length; i++) {
      var badge = badges[i] || {};
      var inline = badge.musicInlineBadgeRenderer || {};
      var metadata = badge.metadataBadgeRenderer || {};
      var iconType =
        (inline.icon && inline.icon.iconType) ||
        (metadata.icon && metadata.icon.iconType) ||
        "";
      var label =
        (inline.accessibilityData &&
          inline.accessibilityData.accessibilityData &&
          inline.accessibilityData.accessibilityData.label) ||
        metadata.label ||
        "";
      if (
        String(iconType).toUpperCase().indexOf("EXPLICIT") >= 0 ||
        String(label).toLowerCase() === "explicit"
      ) {
        return true;
      }
    }
  } catch (e) {}
  return false;
}

function parseItemExtended(info) {
  try {
    if (!info) return null;
    var c = normalizeCandidate(info);
    if (!c) return null;
    var references = collectTrackReferences(c);

    var title = null;
    if (c.flexColumns && Array.isArray(c.flexColumns)) {
      for (var fi = 0; fi < c.flexColumns.length; fi++) {
        var fc = c.flexColumns[fi];
        if (fc && fc.musicResponsiveListItemFlexColumnRenderer) {
          var fcr = fc.musicResponsiveListItemFlexColumnRenderer;
          if (fcr.text && fcr.text.runs && fcr.text.runs[0]) {
            if (!title) title = fcr.text.runs[0].text;
          }
        }
      }
    }

    if (
      !title &&
      c.title &&
      c.title.runs &&
      c.title.runs[0] &&
      c.title.runs[0].text
    )
      title = c.title.runs[0].text;
    if (!title && c.title && c.title.simpleText) title = c.title.simpleText;
    if (
      !title &&
      c.titleText &&
      c.titleText.runs &&
      c.titleText.runs[0] &&
      c.titleText.runs[0].text
    )
      title = c.titleText.runs[0].text;
    if (!title && c.name && c.name.simpleText) title = c.name.simpleText;
    if (!title && c.video && c.video.title) title = c.video.title;
    if (!title && c.header && c.header.title && c.header.title.runs)
      title = c.header.title.runs
        .map(function (r) {
          return r.text;
        })
        .join(" ");

    var artist = "";
    if (
      c.flexColumns &&
      Array.isArray(c.flexColumns) &&
      c.flexColumns.length > 1
    ) {
      var fc2 = c.flexColumns[1];
      if (fc2 && fc2.musicResponsiveListItemFlexColumnRenderer) {
        var fcr2 = fc2.musicResponsiveListItemFlexColumnRenderer;
        if (fcr2.text && fcr2.text.runs) {
          var artistParts = [];
          for (var ri = 0; ri < fcr2.text.runs.length; ri++) {
            var run = fcr2.text.runs[ri];
            if (run && run.text) {
              var txt = run.text.trim();
              if (txt === "•" || txt === " • " || txt === "," || txt === " & ")
                continue;
              var lowerTxt = txt.toLowerCase();
              if (
                lowerTxt === "single" ||
                lowerTxt === "album" ||
                lowerTxt === "ep" ||
                lowerTxt === "playlist" ||
                lowerTxt === "video" ||
                lowerTxt === "song"
              )
                continue;
              if (/^\d{4}$/.test(txt)) continue;
              if (
                /^\d+(\.\d+)?[KMB]?\s*(views|plays|listeners|subscribers|monthly audience)/i.test(
                  txt,
                )
              )
                continue;
              if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(txt)) continue;
              if (
                run.navigationEndpoint &&
                run.navigationEndpoint.browseEndpoint
              ) {
                var artistBrowse = run.navigationEndpoint.browseEndpoint;
                if (
                  String(artistBrowse.browseId || "").startsWith("UC") ||
                  browsePageType(artistBrowse) === "MUSIC_PAGE_TYPE_ARTIST"
                ) {
                  artistParts.push(txt);
                }
              } else if (!run.navigationEndpoint) {
                if (txt.length > 1) artistParts.push(txt);
              }
            }
          }
          artist = artistParts.join(", ");
        }
      }
    }

    if (!artist && c.subtitle && c.subtitle.runs) {
      var subtitleParts = [];
      for (var si = 0; si < c.subtitle.runs.length; si++) {
        var srun = c.subtitle.runs[si];
        if (srun && srun.text) {
          var stxt = srun.text.trim();
          if (stxt === "•" || stxt === " • " || stxt === "," || stxt === " & ")
            continue;
          var lowerStxt = stxt.toLowerCase();
          if (
            lowerStxt === "single" ||
            lowerStxt === "album" ||
            lowerStxt === "ep" ||
            lowerStxt === "playlist" ||
            lowerStxt === "video" ||
            lowerStxt === "song"
          )
            continue;
          if (/^\d{4}$/.test(stxt)) continue;
          // Skip view/play counts (e.g., "159M views", "1.1B plays", "241M plays")
          if (
            /^\d+(\.\d+)?[KMB]?\s*(views|plays|listeners|subscribers|monthly audience)/i.test(
              stxt,
            )
          )
            continue;
          // Skip duration format (e.g., "3:06", "10:45", "1:23:45")
          if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(stxt)) continue;
          if (
            srun.navigationEndpoint &&
            srun.navigationEndpoint.browseEndpoint
          ) {
            var subtitleBrowse = srun.navigationEndpoint.browseEndpoint;
            if (
              String(subtitleBrowse.browseId || "").startsWith("UC") ||
              browsePageType(subtitleBrowse) === "MUSIC_PAGE_TYPE_ARTIST"
            ) {
              subtitleParts.push(stxt);
            }
          } else if (stxt.length > 1) {
            subtitleParts.push(stxt);
          }
        }
      }
      artist = subtitleParts.join(", ");
    }
    if (!artist && c.longBylineText && c.longBylineText.runs)
      artist = c.longBylineText.runs
        .map(function (r) {
          return r.text;
        })
        .join(" ");
    if (!artist && c.ownerText && c.ownerText.runs)
      artist = c.ownerText.runs
        .map(function (r) {
          return r.text;
        })
        .join(" ");
    if (references.artists.length > 0) {
      artist = uniqueStrings(
        references.artists.map(function (item) {
          return item.name;
        }),
      ).join(", ");
    }

    if (!artist) {
      L("debug", "parseItemExtended: no artist found for", title);
    }

    var album = "";
    if (
      c.flexColumns &&
      Array.isArray(c.flexColumns) &&
      c.flexColumns.length > 1
    ) {
      var fc2 = c.flexColumns[1];
      if (fc2 && fc2.musicResponsiveListItemFlexColumnRenderer) {
        var fcr2 = fc2.musicResponsiveListItemFlexColumnRenderer;
        if (fcr2.text && fcr2.text.runs) {
          for (var ri = 0; ri < fcr2.text.runs.length; ri++) {
            var run = fcr2.text.runs[ri];
            if (
              run &&
              run.text &&
              run.navigationEndpoint &&
              run.navigationEndpoint.browseEndpoint
            ) {
              var browseId =
                run.navigationEndpoint.browseEndpoint.browseId || "";
              if (browseId.startsWith("MPREb_")) {
                album = run.text.trim();
                L(
                  "debug",
                  "parseItemExtended: found album from flexColumns",
                  album,
                );
                break;
              }
            }
          }
        }
      }
    }

    if (!album && c.subtitle && c.subtitle.runs) {
      for (var si = 0; si < c.subtitle.runs.length; si++) {
        var srun = c.subtitle.runs[si];
        if (
          srun &&
          srun.text &&
          srun.navigationEndpoint &&
          srun.navigationEndpoint.browseEndpoint
        ) {
          var sBrowseId = srun.navigationEndpoint.browseEndpoint.browseId || "";
          if (sBrowseId.startsWith("MPREb_")) {
            album = srun.text.trim();
            L("debug", "parseItemExtended: found album from subtitle", album);
            break;
          }
        }
      }
    }
    if (references.album_name) album = references.album_name;

    var videoId = null;
    if (c.playlistItemData && c.playlistItemData.videoId)
      videoId = c.playlistItemData.videoId;
    if (!videoId && c.videoId) videoId = c.videoId;
    if (
      !videoId &&
      c.overlay &&
      c.overlay.musicItemThumbnailOverlayRenderer &&
      c.overlay.musicItemThumbnailOverlayRenderer.content &&
      c.overlay.musicItemThumbnailOverlayRenderer.content
        .musicPlayButtonRenderer
    ) {
      var mpbr =
        c.overlay.musicItemThumbnailOverlayRenderer.content
          .musicPlayButtonRenderer;
      if (mpbr.playNavigationEndpoint)
        videoId = extractVideoIdFromEndpoint(mpbr.playNavigationEndpoint);
    }
    if (!videoId && c.navigationEndpoint)
      videoId = extractVideoIdFromEndpoint(c.navigationEndpoint);
    if (
      !videoId &&
      c.thumbnail &&
      c.thumbnail.musicThumbnailRenderer &&
      c.thumbnail.musicThumbnailRenderer.navigationEndpoint
    )
      videoId = extractVideoIdFromEndpoint(
        c.thumbnail.musicThumbnailRenderer.navigationEndpoint,
      );
    if (!videoId && c.video && c.video.videoId) videoId = c.video.videoId;

    if (!title || !videoId) {
      if (Math.random() < 0.1)
        L("debug", "parseItemExtended failed", {
          hasTitle: !!title,
          hasVideoId: !!videoId,
          keys: Object.keys(c).slice(0, 5),
        });
      return null;
    }
    var durationText = "";
    if (c.lengthText && c.lengthText.simpleText)
      durationText = c.lengthText.simpleText;
    // Watch-next queue rows (playlistPanelVideoRenderer) carry runs instead.
    if (!durationText && c.lengthText && c.lengthText.runs)
      durationText = runsText(c.lengthText.runs, "");
    if (
      !durationText &&
      c.thumbnailOverlays &&
      c.thumbnailOverlays[0] &&
      c.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer &&
      c.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer.text &&
      c.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer.text.simpleText
    ) {
      durationText =
        c.thumbnailOverlays[0].thumbnailOverlayTimeStatusRenderer.text
          .simpleText;
    }
    if (!durationText && c.badges && c.badges.length) {
      for (var bi = 0; bi < c.badges.length; bi++) {
        var b = c.badges[bi];
        if (b && b.metadataBadgeRenderer && b.metadataBadgeRenderer.label) {
          durationText = String(b.metadataBadgeRenderer.label);
          break;
        }
      }
    }
    if (!durationText && c.fixedColumns && Array.isArray(c.fixedColumns)) {
      for (var dfi = 0; dfi < c.fixedColumns.length; dfi++) {
        var dfc = c.fixedColumns[dfi];
        if (dfc && dfc.musicResponsiveListItemFixedColumnRenderer) {
          var dfcr = dfc.musicResponsiveListItemFixedColumnRenderer;
          if (
            dfcr.text &&
            dfcr.text.runs &&
            dfcr.text.runs[0] &&
            dfcr.text.runs[0].text
          ) {
            var possibleDuration = dfcr.text.runs[0].text.trim();
            if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(possibleDuration)) {
              durationText = possibleDuration;
              L(
                "debug",
                "parseItemExtended: found duration from fixedColumns",
                durationText,
              );
              break;
            }
          }
          if (!durationText && dfcr.text && dfcr.text.simpleText) {
            var possibleDuration2 = dfcr.text.simpleText.trim();
            if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(possibleDuration2)) {
              durationText = possibleDuration2;
              L(
                "debug",
                "parseItemExtended: found duration from fixedColumns simpleText",
                durationText,
              );
              break;
            }
          }
        }
      }
    }
    if (
      !durationText &&
      c.flexColumns &&
      Array.isArray(c.flexColumns) &&
      c.flexColumns.length > 1
    ) {
      var fc3 = c.flexColumns[1];
      if (fc3 && fc3.musicResponsiveListItemFlexColumnRenderer) {
        var fcr3 = fc3.musicResponsiveListItemFlexColumnRenderer;
        if (fcr3.text && fcr3.text.runs) {
          for (var dri = 0; dri < fcr3.text.runs.length; dri++) {
            var drun = fcr3.text.runs[dri];
            if (drun && drun.text) {
              var dtxt = drun.text.trim();
              if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(dtxt)) {
                durationText = dtxt;
                L(
                  "debug",
                  "parseItemExtended: found duration from flexColumns",
                  durationText,
                );
                break;
              }
            }
          }
        }
      }
    }
    var duration = parseDurationText(durationText);
    var thumbRaw = null;
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.thumbnail &&
          c.thumbnail.musicThumbnailRenderer &&
          c.thumbnail.musicThumbnailRenderer.thumbnail &&
          c.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails) ||
          null,
      );
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl((c.thumbnail && c.thumbnail.thumbnails) || null);
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.video && c.video.thumbnail && c.video.thumbnail.thumbnails) || null,
      );
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.thumbnail &&
          c.thumbnail.thumbnail &&
          c.thumbnail.thumbnail.thumbnails) ||
          null,
      );
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.thumbnail && c.thumbnail.thumbnails && c.thumbnail.thumbnails) ||
          null,
      );
    if (!thumbRaw && c.fixedColumns) {
      for (var fi = 0; fi < c.fixedColumns.length; fi++) {
        var fc = c.fixedColumns[fi];
        if (
          fc &&
          fc.musicResponsiveListItemFixedColumnRenderer &&
          fc.musicResponsiveListItemFixedColumnRenderer.thumbnail
        ) {
          thumbRaw = pickLastThumbnailUrl(
            fc.musicResponsiveListItemFixedColumnRenderer.thumbnail.thumbnails,
          );
          if (thumbRaw) break;
        }
      }
    }
    var thumb = makeSquareThumb(thumbRaw);
    thumb = normalizeUrl(thumb) || null;
    return {
      id: String(videoId),
      title: String(title),
      artist: String(artist || ""),
      album: String(album || ""),
      album_artist: "",
      artist_id: references.artists.length ? references.artists[0].id : "",
      artist_url: references.artists.length
        ? "https://music.youtube.com/channel/" + references.artists[0].id
        : "",
      album_id: references.album_id,
      album_url: references.album_id
        ? "https://music.youtube.com/browse/" + references.album_id
        : "",
      external_urls: "https://music.youtube.com/watch?v=" + String(videoId),
      external_links: {
        youtube: "https://music.youtube.com/watch?v=" + String(videoId),
      },
      duration: Number(duration || 0),
      thumbnail: thumb,
      explicit: hasExplicitBadge(c),
      credits_id: references.credits_id || "MPTC" + String(videoId),
      source: "youtube",
      item_type: "track",
    };
  } catch (e) {
    L("warn", "parseItemExtended error", String(e));
    return null;
  }
}

function parseCollectionItem(info) {
  try {
    if (!info) return null;
    var c = normalizeCandidate(info);
    if (!c) return null;

    var browseInfo = null;
    if (c.navigationEndpoint) {
      browseInfo = extractBrowseInfoFromEndpoint(c.navigationEndpoint);
    }
    if (
      !browseInfo &&
      c.overlay &&
      c.overlay.musicItemThumbnailOverlayRenderer
    ) {
      var overlay = c.overlay.musicItemThumbnailOverlayRenderer;
      if (
        overlay.content &&
        overlay.content.musicPlayButtonRenderer &&
        overlay.content.musicPlayButtonRenderer.playNavigationEndpoint
      ) {
        browseInfo = extractBrowseInfoFromEndpoint(
          overlay.content.musicPlayButtonRenderer.playNavigationEndpoint,
        );
      }
    }

    // Only process albums, playlists, and artists
    if (
      !browseInfo ||
      (browseInfo.type !== "album" &&
        browseInfo.type !== "playlist" &&
        browseInfo.type !== "artist")
    ) {
      return null;
    }

    // Extract title
    var title = null;
    if (c.flexColumns && Array.isArray(c.flexColumns)) {
      for (var fi = 0; fi < c.flexColumns.length; fi++) {
        var fc = c.flexColumns[fi];
        if (fc && fc.musicResponsiveListItemFlexColumnRenderer) {
          var fcr = fc.musicResponsiveListItemFlexColumnRenderer;
          if (fcr.text && fcr.text.runs && fcr.text.runs[0]) {
            if (!title) title = fcr.text.runs[0].text;
          }
        }
      }
    }
    if (!title && c.title && c.title.runs && c.title.runs[0])
      title = c.title.runs[0].text;
    if (!title && c.title && c.title.simpleText) title = c.title.simpleText;

    var subtitle = "";
    var albumType = browseInfo.type === "album" ? "album" : "playlist";
    var year = "";
    var artist = "";

    if (
      c.flexColumns &&
      Array.isArray(c.flexColumns) &&
      c.flexColumns.length > 1
    ) {
      var fc2 = c.flexColumns[1];
      if (fc2 && fc2.musicResponsiveListItemFlexColumnRenderer) {
        var fcr2 = fc2.musicResponsiveListItemFlexColumnRenderer;
        if (fcr2.text && fcr2.text.runs) {
          subtitle = fcr2.text.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
      }
    }
    if (!subtitle && c.subtitle && c.subtitle.runs) {
      subtitle = c.subtitle.runs
        .map(function (r) {
          return r.text;
        })
        .join("");
    }

    if (subtitle) {
      var parts = subtitle.split(" • ");
      if (parts.length >= 1) {
        var typeStr = parts[0].toLowerCase().trim();
        if (typeStr === "album" || typeStr === "single" || typeStr === "ep") {
          albumType = typeStr === "ep" ? "ep" : typeStr;
        } else if (typeStr === "playlist") {
          albumType = "playlist";
        }
      }
      if (parts.length >= 2) {
        artist = parts[1].trim();
      }
      if (parts.length >= 3) {
        var maybeYear = parts[2].trim();
        if (/^\d{4}$/.test(maybeYear)) {
          year = maybeYear;
        }
      }
    }

    var thumbRaw = null;
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.thumbnail &&
          c.thumbnail.musicThumbnailRenderer &&
          c.thumbnail.musicThumbnailRenderer.thumbnail &&
          c.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails) ||
          null,
      );
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl((c.thumbnail && c.thumbnail.thumbnails) || null);
    thumbRaw =
      thumbRaw ||
      pickLastThumbnailUrl(
        (c.thumbnailRenderer &&
          c.thumbnailRenderer.musicThumbnailRenderer &&
          c.thumbnailRenderer.musicThumbnailRenderer.thumbnail &&
          c.thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails) ||
          null,
      );
    if (!thumbRaw && c.thumbnail && c.thumbnail.musicThumbnailRenderer) {
      var mtr = c.thumbnail.musicThumbnailRenderer;
      if (mtr.thumbnail && mtr.thumbnail.thumbnails) {
        thumbRaw = pickLastThumbnailUrl(mtr.thumbnail.thumbnails);
      }
    }
    var thumb = makeSquareThumb(thumbRaw);
    thumb = normalizeUrl(thumb) || null;
    L("debug", "parseCollectionItem thumbnail", {
      id: browseInfo ? browseInfo.browseId : "null",
      thumbRaw: thumbRaw,
      thumb: thumb,
    });

    if (!title || !browseInfo.browseId) {
      return null;
    }

    // Determine item_type
    var itemType = "album";
    if (browseInfo.type === "playlist") itemType = "playlist";
    else if (browseInfo.type === "artist") itemType = "artist";

    L("info", "parseCollectionItem returning", {
      id: browseInfo.browseId,
      title: title,
      item_type: itemType,
      browseType: browseInfo.type,
    });

    return {
      id: browseInfo.browseId,
      title: String(title),
      artist: String(artist || ""),
      album_type: browseInfo.type === "artist" ? "artist" : albumType,
      year: year,
      thumbnail: thumb,
      item_type: itemType,
      source: "youtube",
    };
  } catch (e) {
    L("warn", "parseCollectionItem error", String(e));
    return null;
  }
}

function parseArtistCardShelf(info) {
  try {
    if (!info || !info.musicCardShelfRenderer) return null;
    var c = info.musicCardShelfRenderer;

    var title = null;
    if (c.title && c.title.runs && c.title.runs[0]) {
      title = c.title.runs[0].text;
    }

    var browseId = null;
    if (
      c.title &&
      c.title.runs &&
      c.title.runs[0] &&
      c.title.runs[0].navigationEndpoint
    ) {
      var navEp = c.title.runs[0].navigationEndpoint;
      if (navEp.browseEndpoint && navEp.browseEndpoint.browseId) {
        browseId = navEp.browseEndpoint.browseId;
      }
    }
    // Alternative: check onTap or buttons
    if (!browseId && c.onTap && c.onTap.browseEndpoint) {
      browseId = c.onTap.browseEndpoint.browseId;
    }
    if (!browseId && c.buttons && c.buttons.length > 0) {
      for (var bi = 0; bi < c.buttons.length; bi++) {
        var btn = c.buttons[bi];
        if (
          btn &&
          btn.buttonRenderer &&
          btn.buttonRenderer.navigationEndpoint &&
          btn.buttonRenderer.navigationEndpoint.browseEndpoint
        ) {
          browseId =
            btn.buttonRenderer.navigationEndpoint.browseEndpoint.browseId;
          break;
        }
      }
    }

    // Check if this is an artist (browseId starts with UC)
    if (!browseId || !browseId.startsWith("UC")) {
      return null;
    }

    if (!title) return null;

    // Extract subtitle (e.g., "45M monthly audience")
    var subtitle = "";
    if (c.subtitle && c.subtitle.runs) {
      subtitle = c.subtitle.runs
        .map(function (r) {
          return r.text;
        })
        .join("");
    }

    var thumbRaw = null;
    if (
      c.thumbnail &&
      c.thumbnail.musicThumbnailRenderer &&
      c.thumbnail.musicThumbnailRenderer.thumbnail
    ) {
      thumbRaw = pickLastThumbnailUrl(
        c.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
      );
    }
    var thumb = makeSquareThumb(thumbRaw);
    thumb = normalizeUrl(thumb) || null;

    L("info", "parseArtistCardShelf found artist", {
      id: browseId,
      name: title,
      subtitle: subtitle,
    });

    return {
      id: browseId,
      title: String(title),
      artist: String(title), // For artists, artist name is the title
      thumbnail: thumb,
      item_type: "artist",
      album_type: "artist",
      source: "youtube",
    };
  } catch (e) {
    L("warn", "parseArtistCardShelf error", String(e));
    return null;
  }
}

function parseSearchItem(info) {
  if (info && info.musicCardShelfRenderer) {
    var artistCard = parseArtistCardShelf(info);
    if (artistCard) return artistCard;
  }

  var collection = parseCollectionItem(info);
  if (collection) return collection;

  return parseItemExtended(info);
}

var COLLECT_ITEMS_MAX = 5000;

function collectItemsFromNode(node, out, depth) {
  depth = depth || 0;
  // Prevent infinite recursion - max depth 20
  if (depth > 20) return;
  // Safety exit with high cap (was 100, now 5000)
  if (out.length >= COLLECT_ITEMS_MAX) return;

  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (var i = 0; i < node.length && out.length < COLLECT_ITEMS_MAX; i++)
      collectItemsFromNode(node[i], out, depth + 1);
    return;
  }
  if (
    node.videoRenderer ||
    node.musicResponsiveListItemRenderer ||
    node.musicTwoRowItemRenderer ||
    node.musicCardRenderer ||
    (node.richItemRenderer && node.richItemRenderer.content) ||
    node.playlistPanelVideoRenderer ||
    node.musicCardShelfRenderer
  ) {
    out.push(node);
  }
  for (var k in node) {
    if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
    if (out.length >= COLLECT_ITEMS_MAX) break;
    var v = node[k];
    if (!v) continue;
    if (Array.isArray(v)) {
      for (var ai = 0; ai < v.length && out.length < COLLECT_ITEMS_MAX; ai++)
        collectItemsFromNode(v[ai], out, depth + 1);
    } else if (typeof v === "object") {
      collectItemsFromNode(v, out, depth + 1);
    }
  }
}

function collectAlbumTracksOnly(data, out, continuationInfo) {
  if (!data || typeof data !== "object") return;

  var shelfFound = false;

  // Helper to extract continuation token from a shelf renderer
  function extractContinuation(shelfNode) {
    if (!continuationInfo) return;
    try {
      var conts = shelfNode.continuations;
      if (Array.isArray(conts)) {
        for (var ci = 0; ci < conts.length; ci++) {
          var c = conts[ci];
          if (
            c &&
            c.nextContinuationData &&
            c.nextContinuationData.continuation
          ) {
            continuationInfo.token = c.nextContinuationData.continuation;
            L("debug", "collectAlbumTracksOnly: found continuation token");
            return;
          }
          if (
            c &&
            c.reloadContinuationData &&
            c.reloadContinuationData.continuation
          ) {
            continuationInfo.token = c.reloadContinuationData.continuation;
            L(
              "debug",
              "collectAlbumTracksOnly: found reload continuation token",
            );
            return;
          }
        }
      }
    } catch (e) {
      L("debug", "extractContinuation error", String(e));
    }
  }

  function findFirstShelf(node, depth) {
    if (shelfFound || depth > 15) return;
    if (!node || typeof node !== "object") return;

    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) {
        findFirstShelf(node[i], depth + 1);
        if (shelfFound) return;
      }
      return;
    }

    // Helper to extract continuation token from items in a contents array
    // YouTube Music API often puts continuationItemRenderer at the end of contents
    function extractContinuationFromContents(contents) {
      if (!continuationInfo) return;
      for (
        var ci = contents.length - 1;
        ci >= 0 && ci >= contents.length - 3;
        ci--
      ) {
        var item = contents[ci];
        if (item && item.continuationItemRenderer) {
          var ep = item.continuationItemRenderer.continuationEndpoint;
          if (ep && ep.continuationCommand && ep.continuationCommand.token) {
            continuationInfo.token = ep.continuationCommand.token;
            L(
              "debug",
              "collectAlbumTracksOnly: found continuation token from continuationItemRenderer",
            );
            return;
          }
        }
      }
    }

    // PRIORITY 1: musicPlaylistShelfRenderer (YouTube Music playlists use this)
    if (
      node.musicPlaylistShelfRenderer &&
      node.musicPlaylistShelfRenderer.contents
    ) {
      shelfFound = true;
      var plShelf = node.musicPlaylistShelfRenderer;
      var contents = plShelf.contents;
      for (var i = 0; i < contents.length; i++) {
        var item = contents[i];
        if (item.musicResponsiveListItemRenderer) {
          out.push(item);
        }
      }
      // Try shelf-level continuations first, then check contents for continuationItemRenderer
      extractContinuation(plShelf);
      if (!continuationInfo || !continuationInfo.token) {
        extractContinuationFromContents(contents);
      }
      L(
        "debug",
        "collectAlbumTracksOnly: found musicPlaylistShelfRenderer with",
        contents.length,
        "items, continuation:",
        continuationInfo ? !!continuationInfo.token : "n/a",
      );
      return;
    }

    // PRIORITY 2: musicShelfRenderer (albums and some other views)
    if (node.musicShelfRenderer && node.musicShelfRenderer.contents) {
      shelfFound = true;
      var shelf = node.musicShelfRenderer;
      var contents = shelf.contents;
      for (var i = 0; i < contents.length; i++) {
        var item = contents[i];
        if (item.musicResponsiveListItemRenderer) {
          out.push(item);
        }
      }
      extractContinuation(shelf);
      if (!continuationInfo || !continuationInfo.token) {
        extractContinuationFromContents(contents);
      }
      return;
    }

    // PRIORITY 3: playlistPanelRenderer (radio/auto playlists)
    if (node.playlistPanelRenderer && node.playlistPanelRenderer.contents) {
      shelfFound = true;
      var panel = node.playlistPanelRenderer;
      var contents = panel.contents;
      for (var i = 0; i < contents.length; i++) {
        var item = contents[i];
        if (item.playlistPanelVideoRenderer) {
          out.push(item);
        }
      }
      extractContinuation(panel);
      if (!continuationInfo || !continuationInfo.token) {
        extractContinuationFromContents(contents);
      }
      return;
    }

    for (var k in node) {
      if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
      if (shelfFound) return;
      var v = node[k];
      if (v && typeof v === "object") {
        findFirstShelf(v, depth + 1);
      }
    }
  }

  findFirstShelf(data, 0);
  L(
    "debug",
    "collectAlbumTracksOnly found",
    out.length,
    "tracks, shelfFound:",
    shelfFound,
  );
}

function parseSearchResponseExtended(data) {
  try {
    if (!data || typeof data !== "object") return [];
    var rootCandidates = [];

    if (data.contents && data.contents.tabbedSearchResultsRenderer) {
      var tabs = data.contents.tabbedSearchResultsRenderer.tabs;
      if (Array.isArray(tabs)) {
        for (var ti = 0; ti < tabs.length; ti++) {
          var tab = tabs[ti];
          if (tab && tab.tabRenderer && tab.tabRenderer.content) {
            collectItemsFromNode(tab.tabRenderer.content, rootCandidates);
          }
        }
      }
    }

    if (data.contents && data.contents.sectionListRenderer) {
      collectItemsFromNode(data.contents.sectionListRenderer, rootCandidates);
    }

    if (Array.isArray(data.onResponseReceivedCommands)) {
      data.onResponseReceivedCommands.forEach(function (cmd) {
        if (cmd && typeof cmd === "object") {
          if (
            cmd.appendContinuationItemsAction &&
            cmd.appendContinuationItemsAction.continuationItems
          ) {
            collectItemsFromNode(
              cmd.appendContinuationItemsAction.continuationItems,
              rootCandidates,
            );
          }
          collectItemsFromNode(cmd, rootCandidates);
        }
      });
    }
    if (data.onResponseReceivedActions)
      collectItemsFromNode(data.onResponseReceivedActions, rootCandidates);
    if (data.contents) collectItemsFromNode(data.contents, rootCandidates);
    if (data.results) collectItemsFromNode(data.results, rootCandidates);

    // Only do full tree scan if we found nothing
    if (rootCandidates.length === 0) {
      collectItemsFromNode(data, rootCandidates);
    }

    L("debug", "parseSearchResponseExtended candidates", rootCandidates.length);

    var results = [];
    for (var i = 0; i < rootCandidates.length; i++) {
      var node = rootCandidates[i];
      var possible =
        node.musicResponsiveListItemRenderer ||
        node.musicTwoRowItemRenderer ||
        node.musicCardRenderer ||
        (node.richItemRenderer && node.richItemRenderer.content) ||
        node.videoRenderer ||
        node;
      // Parse both tracks and collections (albums/playlists)
      var parsed = parseSearchItem(possible);
      if (parsed) results.push(parsed);
    }
    var seen = {};
    var deduped = [];
    for (var r = 0; r < results.length; r++) {
      var item = results[r];
      if (!item || !item.id) continue;
      if (!seen[item.id]) {
        seen[item.id] = true;
        deduped.push(item);
        if (deduped.length >= CONFIG.maxResults) break;
      }
    }
    return deduped;
  } catch (e) {
    L("error", "parseSearchResponseExtended fatal", String(e));
    return [];
  }
}

var URL_KEY_RE = /url|uri|link|cover|download|thumbnail/i;

function stripUrlLikeFields(obj) {
  var out = {};
  for (var k in obj) {
    if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
    var v = obj[k];
    if (k === "external_links" && v && typeof v === "object") {
      var safeLinks = {};
      for (var provider in v) {
        if (!Object.prototype.hasOwnProperty.call(v, provider)) continue;
        if (isString(v[provider]) && isAbsoluteHttpUrl(v[provider])) {
          safeLinks[provider] = normalizeUrl(v[provider]);
        }
      }
      out[k] = safeLinks;
    } else if (URL_KEY_RE.test(k)) {
      if (isString(v) && isAbsoluteHttpUrl(v)) {
        out[k] = normalizeUrl(v);
      } else {
        out[k] = null;
      }
    } else {
      out[k] = v;
    }
  }
  return out;
}

function sanitizeTrackBeforeReturn(t) {
  if (!t || typeof t !== "object") return null;
  var id = t.id ? String(t.id).trim() : "";
  if (!id) return null;

  // If this is a collection (album/playlist/artist), use different sanitization
  if (
    t.item_type === "album" ||
    t.item_type === "playlist" ||
    t.item_type === "artist"
  ) {
    return sanitizeCollectionBeforeReturn(t);
  }

  var title = t.title ? String(t.title).trim() : "Unknown title";
  var artist = t.artist ? String(t.artist).trim() : "";
  var thumbCandidate = t.thumbnail || t.coverUrl || null;
  var thumb = normalizeUrl(thumbCandidate) || null;

  var sanitized = {
    id: id,
    name: title, // Mornye expects 'name' not 'title'
    artists: artist, // Mornye expects 'artists' not 'artist'
    album_name: t.album ? String(t.album).trim() : "",
    album_artist: String(t.album_artist || "").trim(),
    artist_id: String(t.artist_id || "").trim(),
    artist_url: String(t.artist_url || "").trim(),
    album_id: String(t.album_id || "").trim(),
    album_url: String(t.album_url || "").trim(),
    external_urls: String(
      t.external_urls || "https://music.youtube.com/watch?v=" + id,
    ).trim(),
    external_links: Object.assign(
      { youtube: "https://music.youtube.com/watch?v=" + id },
      t.external_links || {},
    ),
    duration_ms: (Number(t.duration || 0) || 0) * 1000, // Convert seconds to ms
    cover_url: thumb, // Mornye expects 'cover_url' not 'thumbnail'
    track_number: Number(t.track_number || 0) || 0,
    total_tracks: Number(t.total_tracks || 0) || 0,
    disc_number: Number(t.disc_number || 0) || 0,
    total_discs: Number(t.total_discs || 0) || 0,
    release_date: String(t.release_date || "").trim(),
    album_type: String(t.album_type || "").trim(),
    isrc: String(t.isrc || "").trim(),
    upc: String(t.upc || "").trim(),
    label: String(t.label || "").trim(),
    copyright: String(t.copyright || "").trim(),
    genre: String(t.genre || "").trim(),
    composer: String(t.composer || "").trim(),
    preview_url: String(t.preview_url || "").trim(),
    explicit: t.explicit === true,
    provider_id: "ytmusic-spotiflac",
    item_type: "track",
  };
  cacheSet("yt:video:" + id, sanitized);
  return sanitized;
}

// Album and playlist browse responses share the same parser, but their
// collection headers have different semantics. An album header describes
// every track's release; a playlist header does not. Preserve album metadata
// parsed from each playlist row (or leave it empty) instead of replacing it
// with the playlist title/owner.
function applyBrowseCollectionMetadata(parsed, collection, isPlaylist) {
  if (!parsed || !collection || isPlaylist) return parsed;
  if (collection.name) parsed.album = collection.name;
  parsed.album_id = collection.id || parsed.album_id || "";
  parsed.album_url = parsed.album_id
    ? "https://music.youtube.com/browse/" + parsed.album_id
    : "";
  parsed.album_artist = collection.artists || parsed.album_artist || "";
  parsed.release_date = collection.release_date || parsed.release_date || "";
  parsed.album_type = collection.album_type || parsed.album_type || "album";
  parsed.total_tracks = collection.total_tracks || parsed.total_tracks || 0;
  if (!parsed.thumbnail && collection.cover_url)
    parsed.thumbnail = collection.cover_url;
  if (!parsed.artist && collection.artists) {
    parsed.artist = collection.artists;
  }
  if (!parsed.artist_id && collection.artist_id) {
    parsed.artist_id = collection.artist_id;
    parsed.artist_url =
      "https://music.youtube.com/channel/" + collection.artist_id;
  }
  if (!parsed.disc_number) parsed.disc_number = 1;
  if (!parsed.total_discs) parsed.total_discs = 1;
  return parsed;
}

// Sanitize album/playlist collection for return
function sanitizeCollectionBeforeReturn(t) {
  if (!t || typeof t !== "object") return null;
  var id = t.id ? String(t.id).trim() : "";
  if (!id) return null;

  var title = t.title ? String(t.title).trim() : "Unknown";
  var artist = t.artist ? String(t.artist).trim() : "";
  var thumbCandidate = t.thumbnail || t.coverUrl || null;
  var thumb = normalizeUrl(thumbCandidate) || null;

  var finalItemType = t.item_type || "album";
  L("info", "sanitizeCollectionBeforeReturn", {
    id: id,
    name: title,
    input_item_type: t.item_type,
    final_item_type: finalItemType,
  });

  return {
    id: id,
    name: title,
    artists: artist,
    album_name: title,
    album_type:
      t.album_type || (t.item_type === "playlist" ? "playlist" : "album"),
    release_date: t.year || "",
    cover_url: thumb,
    provider_id: "ytmusic-spotiflac",
    item_type: finalItemType,
  };
}

// YouTube Music InnerTube search filter params (server-side filtering)
var YT_SEARCH_PARAMS = {
  tracks: "EgWKAQIIAQ%3D%3D", // Songs only
  videos: "EgWKAQIQAQ%3D%3D", // Videos only
  albums: "EgWKAQIYAQ%3D%3D", // Albums only
  artists: "EgWKAQIgAQ%3D%3D", // Artists only
  playlists: "EgWKAQIoAQ%3D%3D", // Community playlists only
};

function performSearchSync(query, searchParams) {
  var url = "https://music.youtube.com/youtubei/v1/search?alt=json";
  var requestBody = {
    context: {
      client: { clientName: "WEB_REMIX", clientVersion: CONFIG.clientVersion },
    },
    query: String(query || ""),
  };
  if (searchParams) {
    requestBody.params = searchParams;
  }
  var body = JSON.stringify(requestBody);

  L("info", "performSearchSync fetch start", query);

  var res;
  try {
    res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (compatible)",
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: body,
    });
  } catch (e) {
    L("error", "performSearchSync fetch failed", String(e));
    return [];
  }

  if (!res || !res.ok) {
    L(
      "error",
      "performSearchSync bad response",
      res ? res.status : "no response",
    );
    return [];
  }

  L("debug", "performSearchSync http status", res.status);

  var data;
  try {
    data = res.json();
  } catch (e) {
    L("error", "performSearchSync json parse failed", String(e));
    return [];
  }

  var parsed = parseSearchResponseExtended(data);
  L("info", "performSearchSync parsed results", parsed.length);
  return parsed;
}

function customSearchSync(query, options) {
  var filter = (options && options.filter) || null;
  var isFiltered = filter && filter !== "all";

  // Cache key includes filter for filtered searches
  var key =
    "yt:search:" + String(query || "") + (isFiltered ? ":" + filter : "");
  var cached = cacheGet(key);
  if (cached) {
    L(
      "info",
      "customSearch returning cached",
      query,
      cached.length,
      "filter:",
      filter || "all",
    );
    return cached;
  }

  try {
    // Use server-side filter params when available (more accurate than local filtering)
    var searchParams = isFiltered ? YT_SEARCH_PARAMS[filter] || null : null;
    var results = performSearchSync(query, searchParams);
    if (Array.isArray(results) && results.length > 0) {
      var sanitized = results
        .map(sanitizeTrackBeforeReturn)
        .filter(function (x) {
          return !!x;
        })
        .map(stripUrlLikeFields);

      // Apply local filter as fallback (in case server-side filter missed some)
      if (isFiltered && sanitized.length > 0) {
        sanitized = sanitized.filter(function (item) {
          if (filter === "tracks") return item.item_type === "track";
          if (filter === "albums") return item.item_type === "album";
          if (filter === "artists") return item.item_type === "artist";
          if (filter === "playlists") return item.item_type === "playlist";
          return true;
        });
        L(
          "info",
          "customSearch filtered to",
          filter,
          ":",
          sanitized.length,
          "items",
        );
      }

      if (sanitized.length > 0) {
        cacheSet(key, sanitized);
        L(
          "info",
          "customSearch returning results",
          query,
          sanitized.length,
          "filter:",
          filter || "all",
        );
        return sanitized;
      }
    }
    L("info", "customSearch no results", query, "filter:", filter || "all");
    return [];
  } catch (e) {
    L("error", "customSearch failed", String(e));
    return [];
  }
}

// ---- Related tracks (watch-next radio queue) ----

var RELATED_MAX_SEEDS = 5;
var RELATED_DURATION_TOLERANCE_MS = 15000;

function relatedTitleKey(value) {
  return metadataMatchValue(
    String(value || "").replace(/\s*[\(\[][^\)\]]*[\)\]]/g, " "),
  );
}

function relatedArtistKeys(value) {
  var parts = Array.isArray(value)
    ? value
    : String(value || "").split(
        /\s*,\s*|\s*;\s*|\s+&\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+x\s+/i,
      );
  var keys = [];
  for (var i = 0; i < parts.length; i++) {
    var key = metadataMatchValue(parts[i]);
    if (key && keys.indexOf(key) < 0) keys.push(key);
  }
  return keys;
}

function isConfidentSeedMatch(seed, candidate) {
  if (!seed || !candidate || !candidate.id) return false;
  var seedTitle = relatedTitleKey(seed.name);
  var candidateTitle = relatedTitleKey(candidate.name);
  if (!seedTitle || !candidateTitle) return false;
  if (
    seedTitle !== candidateTitle &&
    candidateTitle.indexOf(seedTitle + " ") !== 0 &&
    seedTitle.indexOf(candidateTitle + " ") !== 0
  )
    return false;

  var seedArtists = relatedArtistKeys(seed.artists);
  var candidateArtists = relatedArtistKeys(candidate.artists);
  var candidateArtistText = " " + metadataMatchValue(candidate.artists) + " ";
  var artistOk = false;
  for (var i = 0; i < seedArtists.length && !artistOk; i++) {
    if (
      candidateArtists.indexOf(seedArtists[i]) >= 0 ||
      candidateArtistText.indexOf(" " + seedArtists[i] + " ") >= 0
    )
      artistOk = true;
  }
  if (!artistOk) return false;

  var seedDuration = Number(seed.duration_ms) || 0;
  var candidateDuration = Number(candidate.duration_ms) || 0;
  if (
    seedDuration > 0 &&
    candidateDuration > 0 &&
    Math.abs(seedDuration - candidateDuration) > RELATED_DURATION_TOLERANCE_MS
  )
    return false;
  return true;
}

function resolveRelatedSeed(seed) {
  var artists = Array.isArray(seed.artists)
    ? seed.artists.join(", ")
    : String(seed.artists || "");
  var query = (artists ? artists + " " : "") + String(seed.name || "");
  if (!query.trim()) return null;
  var results = customSearchSync(query.trim(), { filter: "tracks" });
  for (var i = 0; i < results.length; i++) {
    var candidate = results[i];
    if (candidate && candidate.item_type === "track") {
      if (isConfidentSeedMatch(seed, candidate)) return candidate.id;
    }
  }
  return null;
}

function relatedVideoType(node) {
  var renderer = node && node.playlistPanelVideoRenderer;
  var endpoint = renderer && renderer.navigationEndpoint;
  var config =
    endpoint &&
    endpoint.watchEndpoint &&
    endpoint.watchEndpoint.watchEndpointMusicSupportedConfigs &&
    endpoint.watchEndpoint.watchEndpointMusicSupportedConfigs
      .watchEndpointMusicConfig;
  return (config && config.musicVideoType) || "";
}

// Fetches the YouTube Music radio ("RDAMVM" + videoId) queue for one song.
// Returns sanitized tracks in the customSearch shape. Throws on HTTP errors.
function fetchRelatedForVideo(videoId) {
  var key = "yt:related:" + videoId;
  var cached = cacheGet(key);
  if (cached) return cached;

  var res = fetch("https://music.youtube.com/youtubei/v1/next?alt=json", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0 (compatible)",
      "x-youtube-client-name": "WEB_REMIX",
      "x-youtube-client-version": CONFIG.clientVersion,
    },
    body: JSON.stringify({
      context: {
        client: {
          clientName: "WEB_REMIX",
          clientVersion: CONFIG.clientVersion,
        },
      },
      videoId: videoId,
      playlistId: "RDAMVM" + videoId,
      isAudioOnly: true,
    }),
  });
  if (!res || !res.ok) {
    throw new Error(
      "YouTube Music related request failed: " +
        (res ? res.status : "no response"),
    );
  }
  var data = res.json();
  var panel =
    data && data.contents
      ? findFirstRenderer(data.contents, "playlistPanelRenderer")
      : null;
  var rows = panel && Array.isArray(panel.contents) ? panel.contents : [];

  // Song seeds return songs; video seeds return music videos. Prefer songs
  // and only keep other video types when the queue has no song at all.
  var songs = [];
  var others = [];
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i];
    if (!row || !row.playlistPanelVideoRenderer) continue;
    var type = relatedVideoType(row);
    if (!type || type === "MUSIC_VIDEO_TYPE_ATV") songs.push(row);
    else others.push(row);
  }
  var picked = songs.length > 0 ? songs : others;
  var tracks = [];
  for (var j = 0; j < picked.length; j++) {
    var sanitized = sanitizeTrackBeforeReturn(parseItemExtended(picked[j]));
    if (sanitized) tracks.push(stripUrlLikeFields(sanitized));
  }
  cacheSet(key, tracks);
  return tracks;
}

function getRelatedTracks(seeds, options) {
  var limit = Number(options && options.limit) || 25;
  limit = Math.max(1, Math.min(50, Math.floor(limit)));
  var list = Array.isArray(seeds) ? seeds.slice(0, 10) : [];

  var excludedIds = {};
  var excludedIsrcs = {};
  var seedIds = [];
  var attempted = 0;
  for (var i = 0; i < list.length; i++) {
    var seed = list[i];
    if (!seed) continue;
    var isrc = String(seed.isrc || "")
      .trim()
      .toUpperCase();
    if (isrc) excludedIsrcs[isrc] = true;
    var ownId =
      seed.provider_id === "ytmusic-spotiflac"
        ? String(seed.id || "").trim()
        : "";
    if (ownId) excludedIds[ownId] = true;
    if (attempted >= RELATED_MAX_SEEDS) continue;
    attempted++;
    var videoId = ownId;
    if (!videoId) {
      try {
        videoId = resolveRelatedSeed(seed) || "";
      } catch (e) {
        L("warn", "getRelatedTracks seed resolution failed", String(e));
        videoId = "";
      }
    }
    if (!videoId || seedIds.indexOf(videoId) >= 0) continue;
    excludedIds[videoId] = true;
    seedIds.push(videoId);
  }

  var lists = [];
  var failures = 0;
  for (var s = 0; s < seedIds.length; s++) {
    try {
      lists.push(fetchRelatedForVideo(seedIds[s]));
    } catch (e) {
      failures++;
      L("warn", "getRelatedTracks related request failed", String(e));
    }
  }
  if (lists.length === 0 && failures > 0) {
    throw new Error("YouTube Music related tracks unavailable");
  }

  var out = [];
  var seen = {};
  var longest = 0;
  for (var l = 0; l < lists.length; l++) {
    if (lists[l].length > longest) longest = lists[l].length;
  }
  for (var idx = 0; idx < longest && out.length < limit; idx++) {
    for (var li = 0; li < lists.length && out.length < limit; li++) {
      var track = lists[li][idx];
      if (!track || !track.id || seen[track.id] || excludedIds[track.id])
        continue;
      var trackIsrc = String(track.isrc || "")
        .trim()
        .toUpperCase();
      if (trackIsrc && excludedIsrcs[trackIsrc]) continue;
      seen[track.id] = true;
      out.push(track);
    }
  }
  return out;
}

// Extract video ID from YouTube URL

// Extract album/browse ID from YouTube Music URL

// Max pages to fetch for continuation (safety limit)
var MAX_CONTINUATION_PAGES = 50;

// Fetch a single continuation page
function fetchContinuationSync(continuationToken) {
  L("debug", "fetchContinuationSync: fetching next page");

  var url =
    "https://music.youtube.com/youtubei/v1/browse?ctoken=" +
    encodeURIComponent(continuationToken) +
    "&continuation=" +
    encodeURIComponent(continuationToken) +
    "&alt=json";
  var body = JSON.stringify({
    context: {
      client: { clientName: "WEB_REMIX", clientVersion: CONFIG.clientVersion },
    },
  });

  var res;
  try {
    res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": getRandomUserAgent(),
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: body,
    });
  } catch (e) {
    L("error", "fetchContinuationSync fetch failed", String(e));
    return null;
  }

  if (!res || !res.ok) {
    L(
      "error",
      "fetchContinuationSync bad response",
      res ? res.status : "no response",
    );
    return null;
  }

  try {
    return res.json();
  } catch (e) {
    L("error", "fetchContinuationSync json parse failed", String(e));
    return null;
  }
}

// Parse tracks and continuation token from a continuation response
function parseContinuationResponse(data) {
  var result = { tracks: [], continuationToken: null };
  if (!data) return result;

  var items = [];

  // Check continuationContents (standard continuation response)
  if (data.continuationContents) {
    var cc = data.continuationContents;

    // Helper: extract continuation token from shelf-level .continuations array
    function extractShelfContinuation(shelf) {
      if (shelf.continuations && Array.isArray(shelf.continuations)) {
        for (var ci = 0; ci < shelf.continuations.length; ci++) {
          var c = shelf.continuations[ci];
          if (
            c &&
            c.nextContinuationData &&
            c.nextContinuationData.continuation
          ) {
            result.continuationToken = c.nextContinuationData.continuation;
            return;
          }
          if (
            c &&
            c.reloadContinuationData &&
            c.reloadContinuationData.continuation
          ) {
            result.continuationToken = c.reloadContinuationData.continuation;
            return;
          }
        }
      }
    }

    // Helper: extract continuation token from continuationItemRenderer inside contents array
    function extractContItemFromContents(contents) {
      for (
        var ci = contents.length - 1;
        ci >= 0 && ci >= contents.length - 3;
        ci--
      ) {
        var item = contents[ci];
        if (item && item.continuationItemRenderer) {
          var ep = item.continuationItemRenderer.continuationEndpoint;
          if (ep && ep.continuationCommand && ep.continuationCommand.token) {
            result.continuationToken = ep.continuationCommand.token;
            return;
          }
        }
      }
    }

    // musicPlaylistShelfContinuation (playlist continuations)
    if (cc.musicPlaylistShelfContinuation) {
      var shelf = cc.musicPlaylistShelfContinuation;
      if (shelf.contents) {
        for (var i = 0; i < shelf.contents.length; i++) {
          var item = shelf.contents[i];
          if (item.musicResponsiveListItemRenderer) {
            items.push(item);
          }
        }
        // Check both shelf-level .continuations and continuationItemRenderer in contents
        extractShelfContinuation(shelf);
        if (!result.continuationToken) {
          extractContItemFromContents(shelf.contents);
        }
      }
    }

    // musicShelfContinuation (album/search continuations)
    if (cc.musicShelfContinuation) {
      var mShelf = cc.musicShelfContinuation;
      if (mShelf.contents) {
        for (var i = 0; i < mShelf.contents.length; i++) {
          var item = mShelf.contents[i];
          if (item.musicResponsiveListItemRenderer) {
            items.push(item);
          }
        }
        extractShelfContinuation(mShelf);
        if (!result.continuationToken) {
          extractContItemFromContents(mShelf.contents);
        }
      }
    }

    // sectionListContinuation
    if (cc.sectionListContinuation && cc.sectionListContinuation.contents) {
      var contInfo = { token: null };
      collectAlbumTracksOnly(cc.sectionListContinuation, items, contInfo);
      if (contInfo.token) result.continuationToken = contInfo.token;
    }
  }

  // Also check onResponseReceivedActions (alternative continuation format)
  if (
    data.onResponseReceivedActions &&
    Array.isArray(data.onResponseReceivedActions)
  ) {
    for (var ai = 0; ai < data.onResponseReceivedActions.length; ai++) {
      var action = data.onResponseReceivedActions[ai];
      if (
        action &&
        action.appendContinuationItemsAction &&
        action.appendContinuationItemsAction.continuationItems
      ) {
        var contItems = action.appendContinuationItemsAction.continuationItems;
        for (var ci = 0; ci < contItems.length; ci++) {
          var item = contItems[ci];
          if (item.musicResponsiveListItemRenderer) {
            items.push(item);
          }
          // Check for continuation item
          if (
            item.continuationItemRenderer &&
            item.continuationItemRenderer.continuationEndpoint
          ) {
            var ep = item.continuationItemRenderer.continuationEndpoint;
            if (ep.continuationCommand && ep.continuationCommand.token) {
              result.continuationToken = ep.continuationCommand.token;
            }
          }
        }
      }
    }
  }

  result.tracks = items;
  L(
    "debug",
    "parseContinuationResponse: found",
    items.length,
    "items, hasMore:",
    !!result.continuationToken,
  );
  return result;
}

// Fetch album/playlist tracks using browse API (sync version for goja)
// Extract MPREb_ album browseId from a browse response's track data.
// VLOLAK5uy_ responses don't include album header, but each track's
// album column links to the real MPREb_ album page.
function _extractAlbumBrowseIdFromResponse(data) {
  try {
    var json = JSON.stringify(data);
    var match = json.match(/MPREb_[a-zA-Z0-9_-]+/);
    return match ? match[0] : null;
  } catch (e) {
    return null;
  }
}

function applyResponsiveHeaderMetadata(renderer, info) {
  if (!renderer || !info) return info;
  if (renderer.title && renderer.title.runs) {
    info.name = runsText(renderer.title.runs);
  }

  var subtitleRuns = (renderer.subtitle && renderer.subtitle.runs) || [];
  for (var i = 0; i < subtitleRuns.length; i++) {
    var subtitleText = String(
      (subtitleRuns[i] && subtitleRuns[i].text) || "",
    ).trim();
    var lower = subtitleText.toLowerCase();
    if (
      lower === "album" ||
      lower === "single" ||
      lower === "ep" ||
      lower === "playlist"
    ) {
      info.album_type = lower;
    } else if (/^\d{4}$/.test(subtitleText)) {
      info.release_date = subtitleText;
    }
  }

  var artistRuns =
    (renderer.straplineTextOne && renderer.straplineTextOne.runs) || [];
  if (artistRuns.length) {
    info.artists = uniqueStrings(
      artistRuns.map(function (run) {
        return run && run.text;
      }),
    ).join(", ");
    for (var ar = 0; ar < artistRuns.length; ar++) {
      var endpoint =
        artistRuns[ar] &&
        artistRuns[ar].navigationEndpoint &&
        artistRuns[ar].navigationEndpoint.browseEndpoint;
      if (
        endpoint &&
        (String(endpoint.browseId || "").startsWith("UC") ||
          browsePageType(endpoint) === "MUSIC_PAGE_TYPE_ARTIST")
      ) {
        info.artist_id = String(endpoint.browseId);
        break;
      }
    }
  }

  if (
    renderer.thumbnail &&
    renderer.thumbnail.musicThumbnailRenderer &&
    renderer.thumbnail.musicThumbnailRenderer.thumbnail
  ) {
    var thumbnailURL = pickLastThumbnailUrl(
      renderer.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
    );
    if (thumbnailURL) info.cover_url = makeSquareThumb(thumbnailURL);
  }

  var secondSubtitle = runsText(
    (renderer.secondSubtitle && renderer.secondSubtitle.runs) || [],
  );
  var totalMatch = secondSubtitle.match(/(\d[\d,.]*)\s+(songs?|tracks?)/i);
  if (totalMatch) {
    info.total_tracks =
      parseInt(totalMatch[1].replace(/[^\d]/g, ""), 10) ||
      info.total_tracks ||
      0;
  }
  return info;
}

// Fetch album header metadata (name, artist, cover, etc.) from a MPREb_ browseId.
// Used as a follow-up call when the primary browse response lacks header info.
function _fetchAlbumHeaderMetadata(albumBrowseId) {
  try {
    var cacheKey = "yt:album-header:" + albumBrowseId;
    var cached = cacheGet(cacheKey);
    if (cached) return cached;
    var url = "https://music.youtube.com/youtubei/v1/browse?alt=json";
    var res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": getRandomUserAgent(),
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: JSON.stringify({
        context: {
          client: {
            clientName: "WEB_REMIX",
            clientVersion: CONFIG.clientVersion,
          },
        },
        browseId: albumBrowseId,
      }),
    });
    if (!res || !res.ok) {
      L(
        "warn",
        "_fetchAlbumHeaderMetadata bad response",
        res ? res.status : "no response",
      );
      return null;
    }
    var data = res.json();

    var info = {
      name: "",
      artists: "",
      artist_id: "",
      cover_url: null,
      release_date: "",
      album_type: "album",
      total_tracks: 0,
    };

    var responsiveHeader =
      (data.header && data.header.musicResponsiveHeaderRenderer) ||
      findFirstRenderer(data.contents, "musicResponsiveHeaderRenderer");
    if (responsiveHeader) applyResponsiveHeaderMetadata(responsiveHeader, info);

    // microformat has the cleanest title: "Acoustic - Album by Queen"
    if (data.microformat && data.microformat.microformatDataRenderer) {
      var mf = data.microformat.microformatDataRenderer;
      if (mf.title) {
        var m = mf.title.match(
          /^(.+?)\s*-\s*(Album|Single|EP|Playlist)\s+by\s+(.+)$/i,
        );
        if (m) {
          info.name = m[1].trim();
          info.artists = m[3].trim();
          var typeStr = m[2].toLowerCase();
          if (typeStr === "single") info.album_type = "single";
          else if (typeStr === "ep") info.album_type = "ep";
        } else {
          info.name = mf.title;
        }
      }
      if (!info.artists && mf.description) {
        var parts = mf.description.split(" • ");
        if (parts.length >= 2) {
          var lower = parts[0].toLowerCase().trim();
          if (lower === "album" || lower === "single" || lower === "ep") {
            info.artists = parts[1].trim();
          } else {
            info.artists = parts[0].trim();
          }
        }
      }
    }

    // background has the cover image
    if (
      data.background &&
      data.background.musicThumbnailRenderer &&
      data.background.musicThumbnailRenderer.thumbnail
    ) {
      var thumbUrl = pickLastThumbnailUrl(
        data.background.musicThumbnailRenderer.thumbnail.thumbnails,
      );
      if (thumbUrl) info.cover_url = makeSquareThumb(thumbUrl);
    }

    // frameworkUpdates may have artist_id and release_date
    if (data.frameworkUpdates && data.frameworkUpdates.entityBatchUpdate) {
      var mutations = data.frameworkUpdates.entityBatchUpdate.mutations;
      if (Array.isArray(mutations)) {
        for (var i = 0; i < mutations.length; i++) {
          var mut = mutations[i];
          if (mut && mut.payload && mut.payload.musicAlbumRelease) {
            var release = mut.payload.musicAlbumRelease;
            if (release.releaseDate) {
              var rd = release.releaseDate;
              info.release_date =
                (rd.year || "") +
                (rd.month ? "-" + String(rd.month).padStart(2, "0") : "") +
                (rd.day ? "-" + String(rd.day).padStart(2, "0") : "");
            }
            if (release.artistDisplayName && !info.artists) {
              info.artists = release.artistDisplayName;
            }
            break;
          }
        }
      }
    }

    cacheSet(cacheKey, info);
    L("info", "_fetchAlbumHeaderMetadata result", {
      name: info.name,
      artists: info.artists,
      album_type: info.album_type,
    });
    return info;
  } catch (e) {
    L("error", "_fetchAlbumHeaderMetadata failed", String(e));
    return null;
  }
}

function fetchBrowseTracksSync(browseId) {
  L("info", "fetchBrowseTracksSync", browseId);

  var url = "https://music.youtube.com/youtubei/v1/browse?alt=json";
  var body = JSON.stringify({
    context: {
      client: { clientName: "WEB_REMIX", clientVersion: CONFIG.clientVersion },
    },
    browseId: browseId,
  });

  var res;
  try {
    res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": getRandomUserAgent(),
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: body,
    });
  } catch (e) {
    L("error", "fetchBrowseTracksSync fetch failed", String(e));
    return null;
  }

  if (!res || !res.ok) {
    L(
      "error",
      "fetchBrowseTracksSync bad response",
      res ? res.status : "no response",
    );
    return null;
  }

  var data;
  try {
    data = res.json();
  } catch (e) {
    L("error", "fetchBrowseTracksSync json parse failed", String(e));
    return null;
  }

  var result = parseBrowseResponse(data, browseId);
  if (!result) return null;
  var rawBrowseData = data; // Keep reference for album ID extraction later

  // Follow continuation tokens to fetch ALL tracks (pagination)
  var continuationToken = result._continuationToken;
  var page = 0;
  var seenTokens = {};
  var seenVideoIds = {};

  // Build set of video IDs from first page to detect duplicates
  for (var si = 0; si < result.tracks.length; si++) {
    var tid = result.tracks[si].id || result.tracks[si].track_id;
    if (tid) seenVideoIds[tid] = true;
  }

  while (continuationToken && page < MAX_CONTINUATION_PAGES) {
    // Detect infinite loop: same token returned again
    if (seenTokens[continuationToken]) {
      L(
        "warn",
        "fetchBrowseTracksSync: duplicate continuation token detected, stopping pagination",
      );
      break;
    }
    seenTokens[continuationToken] = true;

    page++;
    L("info", "fetchBrowseTracksSync: fetching continuation page", page);

    var contData = fetchContinuationSync(continuationToken);
    if (!contData) break;

    var contResult = parseContinuationResponse(contData);
    if (!contResult || contResult.tracks.length === 0) break;

    // Parse the continuation tracks, skipping duplicates
    var newTracksThisPage = 0;
    for (var i = 0; i < contResult.tracks.length; i++) {
      var node = contResult.tracks[i];
      var possible =
        node.musicResponsiveListItemRenderer ||
        node.playlistPanelVideoRenderer ||
        node;
      var parsed = parseItemExtended(possible);
      if (parsed) {
        // Skip duplicate video IDs
        var vid = parsed.id;
        if (vid && seenVideoIds[vid]) {
          continue;
        }
        if (vid) seenVideoIds[vid] = true;

        applyBrowseCollectionMetadata(
          parsed,
          result.album,
          result.type === "playlist",
        );
        var sanitized = sanitizeTrackBeforeReturn(parsed);
        if (sanitized) {
          if (!sanitized.track_number) {
            sanitized.track_number = result.tracks.length + 1;
          }
          result.tracks.push(sanitized);
          newTracksThisPage++;
        }
      }
    }

    L(
      "info",
      "fetchBrowseTracksSync: page",
      page,
      "added",
      newTracksThisPage,
      "new tracks, total:",
      result.tracks.length,
    );

    // If no new unique tracks were added, we've exhausted the playlist
    if (newTracksThisPage === 0) {
      L(
        "info",
        "fetchBrowseTracksSync: no new tracks on page",
        page,
        ", stopping pagination",
      );
      break;
    }

    continuationToken = contResult.continuationToken;
  }

  // Update total_tracks after all pages fetched
  if (result.album) {
    result.album.total_tracks = result.tracks.length;
    for (var trackIndex = 0; trackIndex < result.tracks.length; trackIndex++) {
      result.tracks[trackIndex].total_tracks = result.tracks.length;
    }
  }

  // Clean up internal field
  delete result._continuationToken;

  // If album header info is missing (e.g. VLOLAK5uy_ playlist IDs that
  // represent albums), try to find the MPREb_ album browseId from the
  // response and do a follow-up browse to get proper album metadata.
  if (!result.name && result.tracks && result.tracks.length > 0) {
    var albumBrowseId = _extractAlbumBrowseIdFromResponse(rawBrowseData);
    if (albumBrowseId) {
      L(
        "info",
        "fetchBrowseTracksSync: header missing, fetching album metadata from",
        albumBrowseId,
      );
      var albumMeta = _fetchAlbumHeaderMetadata(albumBrowseId);
      if (albumMeta) {
        result.name = albumMeta.name || result.name;
        result.cover_url = albumMeta.cover_url || result.cover_url;
        // This is actually an album, not a playlist -- override type
        result.type = "album";
        if (!result.album) {
          result.album = {
            id: albumBrowseId,
            total_tracks: result.tracks.length,
          };
        }
        result.album.name = albumMeta.name || result.album.name;
        result.album.artists = albumMeta.artists || result.album.artists;
        result.album.artist_id = albumMeta.artist_id || result.album.artist_id;
        result.album.cover_url = albumMeta.cover_url || result.album.cover_url;
        result.album.release_date =
          albumMeta.release_date || result.album.release_date;
        result.album.album_type =
          albumMeta.album_type || result.album.album_type;
        result.album.total_tracks =
          albumMeta.total_tracks || result.album.total_tracks;
        result.album.id = albumBrowseId;
      }
    }
  }

  // For playlists, remove album field so Flutter routes to PlaylistScreen
  // (matches Spotify Web extension convention: playlists don't have album field)
  if (result.type === "playlist") {
    delete result.album;
  }

  L("info", "fetchBrowseTracksSync: final total tracks:", result.tracks.length);
  return result;
}

function parseBrowseResponse(data, browseId) {
  try {
    if (!data) return null;

    var headerInfo = {
      id: browseId,
      name: "",
      artists: "",
      artist_id: "",
      cover_url: null,
      album_type: "album",
      release_date: "",
      total_tracks: 0,
    };

    var header = data.header;

    if (!header && data.contents) {
      var scbr = data.contents.singleColumnBrowseResultsRenderer;
      if (
        scbr &&
        scbr.tabs &&
        scbr.tabs[0] &&
        scbr.tabs[0].tabRenderer &&
        scbr.tabs[0].tabRenderer.content
      ) {
        var tabContent = scbr.tabs[0].tabRenderer.content;
        if (
          tabContent.sectionListRenderer &&
          tabContent.sectionListRenderer.header
        ) {
          header = tabContent.sectionListRenderer.header;
        }
      }
      // Check twoColumnBrowseResultsRenderer
      var tcbr = data.contents.twoColumnBrowseResultsRenderer;
      if (
        !header &&
        tcbr &&
        tcbr.tabs &&
        tcbr.tabs[0] &&
        tcbr.tabs[0].tabRenderer &&
        tcbr.tabs[0].tabRenderer.content
      ) {
        var tabContent2 = tcbr.tabs[0].tabRenderer.content;
        if (
          tabContent2.sectionListRenderer &&
          tabContent2.sectionListRenderer.header
        ) {
          header = tabContent2.sectionListRenderer.header;
        }
      }
    }
    if (!header && data.contents) {
      var nestedResponsiveHeader = findFirstRenderer(
        data.contents,
        "musicResponsiveHeaderRenderer",
      );
      if (nestedResponsiveHeader)
        header = { musicResponsiveHeaderRenderer: nestedResponsiveHeader };
    }

    if (!header && data.background) {
      var bgThumb = pickLastThumbnailUrl(
        data.background.musicThumbnailRenderer &&
          data.background.musicThumbnailRenderer.thumbnail &&
          data.background.musicThumbnailRenderer.thumbnail.thumbnails,
      );
      if (bgThumb) {
        headerInfo.cover_url = makeSquareThumb(bgThumb);
      }
    }

    if (data.microformat && data.microformat.microformatDataRenderer) {
      var mf = data.microformat.microformatDataRenderer;
      if (mf.title) {
        var titleStr = mf.title;
        var albumByMatch = titleStr.match(
          /^(.+?)\s*-\s*(Album|Single|EP|Playlist)\s+by\s+(.+)$/i,
        );
        if (albumByMatch) {
          if (!headerInfo.name) headerInfo.name = albumByMatch[1].trim();
          if (!headerInfo.artists) headerInfo.artists = albumByMatch[3].trim();
          var typeStr = albumByMatch[2].toLowerCase();
          if (typeStr === "single") headerInfo.album_type = "single";
          else if (typeStr === "ep") headerInfo.album_type = "ep";
          else if (typeStr === "playlist") headerInfo.album_type = "playlist";
        } else if (!headerInfo.name) {
          headerInfo.name = titleStr;
        }
      }
      if (!headerInfo.artists && mf.description) {
        var descParts = mf.description.split(" • ");
        if (descParts.length >= 2) {
          var lowerFirst = descParts[0].toLowerCase().trim();
          if (
            lowerFirst === "album" ||
            lowerFirst === "single" ||
            lowerFirst === "ep" ||
            lowerFirst === "playlist"
          ) {
            if (descParts.length >= 2) headerInfo.artists = descParts[1].trim();
          } else {
            headerInfo.artists = descParts[0].trim();
          }
        }
      }
    }

    if (
      !headerInfo.artists &&
      data.frameworkUpdates &&
      data.frameworkUpdates.entityBatchUpdate
    ) {
      var mutations = data.frameworkUpdates.entityBatchUpdate.mutations;
      if (Array.isArray(mutations)) {
        for (var mi = 0; mi < mutations.length; mi++) {
          var mut = mutations[mi];
          if (mut && mut.payload && mut.payload.musicAlbumRelease) {
            var release = mut.payload.musicAlbumRelease;
            if (release.artistDisplayName) {
              headerInfo.artists = release.artistDisplayName;
              break;
            }
          }
        }
      }
    }

    L(
      "debug",
      "parseBrowseResponse header keys",
      header ? Object.keys(header) : "no header",
    );
    L("debug", "parseBrowseResponse data keys", Object.keys(data));
    L("debug", "parseBrowseResponse headerInfo after fallbacks", {
      name: headerInfo.name,
      artists: headerInfo.artists,
    });
    if (header) {
      if (header.musicResponsiveHeaderRenderer) {
        applyResponsiveHeaderMetadata(
          header.musicResponsiveHeaderRenderer,
          headerInfo,
        );
        L(
          "debug",
          "musicResponsiveHeaderRenderer cover_url",
          headerInfo.cover_url,
        );
      }
      if (header.musicDetailHeaderRenderer) {
        var h = header.musicDetailHeaderRenderer;
        if (h.title && h.title.runs) {
          headerInfo.name = h.title.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
        if (h.subtitle && h.subtitle.runs) {
          var subtitleParts = h.subtitle.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
          headerInfo.artists = subtitleParts;
          var yearMatch = subtitleParts.match(/\d{4}/);
          if (yearMatch) headerInfo.release_date = yearMatch[0];
          var lowerSubtitle = subtitleParts.toLowerCase();
          if (lowerSubtitle.indexOf("ep") !== -1) headerInfo.album_type = "ep";
          else if (lowerSubtitle.indexOf("single") !== -1)
            headerInfo.album_type = "single";
          else if (lowerSubtitle.indexOf("playlist") !== -1)
            headerInfo.album_type = "playlist";

          // Extract artist_id from runs with browseEndpoint
          for (var ri = 0; ri < h.subtitle.runs.length; ri++) {
            var run = h.subtitle.runs[ri];
            if (
              run.navigationEndpoint &&
              run.navigationEndpoint.browseEndpoint
            ) {
              var browseEndpoint = run.navigationEndpoint.browseEndpoint;
              if (
                browseEndpoint.browseId &&
                browseEndpoint.browseId.startsWith("UC")
              ) {
                headerInfo.artist_id = browseEndpoint.browseId;
                // Also get clean artist name from this run
                if (run.text && run.text.trim()) {
                  headerInfo.artists = run.text.trim();
                }
                L(
                  "debug",
                  "Extracted artist_id from subtitle runs:",
                  headerInfo.artist_id,
                );
                break;
              }
            }
          }
        }
        if (
          h.thumbnail &&
          h.thumbnail.croppedSquareThumbnailRenderer &&
          h.thumbnail.croppedSquareThumbnailRenderer.thumbnail
        ) {
          var thumbUrl = pickLastThumbnailUrl(
            h.thumbnail.croppedSquareThumbnailRenderer.thumbnail.thumbnails,
          );
          headerInfo.cover_url = makeSquareThumb(thumbUrl);
        } else if (
          h.thumbnail &&
          h.thumbnail.musicThumbnailRenderer &&
          h.thumbnail.musicThumbnailRenderer.thumbnail
        ) {
          var thumbUrl = pickLastThumbnailUrl(
            h.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
          );
          headerInfo.cover_url = makeSquareThumb(thumbUrl);
        }
        L("debug", "musicDetailHeaderRenderer cover_url", headerInfo.cover_url);
      }
      if (header.musicImmersiveHeaderRenderer) {
        var ih = header.musicImmersiveHeaderRenderer;
        if (ih.title && ih.title.runs) {
          headerInfo.name = ih.title.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
        if (ih.description && ih.description.runs) {
          headerInfo.artists = ih.description.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
        if (
          ih.thumbnail &&
          ih.thumbnail.musicThumbnailRenderer &&
          ih.thumbnail.musicThumbnailRenderer.thumbnail
        ) {
          var thumbUrl2 = pickLastThumbnailUrl(
            ih.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
          );
          headerInfo.cover_url = makeSquareThumb(thumbUrl2);
        }
        headerInfo.album_type = "playlist";
        L(
          "debug",
          "musicImmersiveHeaderRenderer cover_url",
          headerInfo.cover_url,
        );
      }
      if (header.musicVisualHeaderRenderer) {
        var vh = header.musicVisualHeaderRenderer;
        if (vh.title && vh.title.runs) {
          headerInfo.name = vh.title.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
        if (
          vh.foregroundThumbnail &&
          vh.foregroundThumbnail.musicThumbnailRenderer &&
          vh.foregroundThumbnail.musicThumbnailRenderer.thumbnail
        ) {
          var thumbUrl3 = pickLastThumbnailUrl(
            vh.foregroundThumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
          );
          headerInfo.cover_url = makeSquareThumb(thumbUrl3);
        }
        L("debug", "musicVisualHeaderRenderer cover_url", headerInfo.cover_url);
      }
    }

    L(
      "debug",
      "parseBrowseResponse headerInfo.cover_url after header parse",
      headerInfo.cover_url,
    );

    var trackCandidates = [];
    var continuationInfo = { token: null };
    if (data.contents) {
      collectAlbumTracksOnly(data.contents, trackCandidates, continuationInfo);
    }

    if (trackCandidates.length === 0 && data.contents) {
      L(
        "debug",
        "parseBrowseResponse: collectAlbumTracksOnly found nothing, falling back to collectItemsFromNode",
      );
      collectItemsFromNode(data.contents, trackCandidates, 0);
    }

    L(
      "debug",
      "parseBrowseResponse found candidates",
      trackCandidates.length,
      "continuation:",
      !!continuationInfo.token,
    );

    // Classify the collection before parsing its tracks. Playlist headers are
    // containers, not release metadata, and must never become ALBUM/ARTIST.
    if (
      browseId.startsWith("VL") ||
      browseId.startsWith("PL") ||
      browseId.startsWith("RDCLAK5uy_")
    ) {
      headerInfo.album_type = "playlist";
    } else if (browseId.startsWith("MPREb_")) {
      if (!headerInfo.album_type) headerInfo.album_type = "album";
    }
    var isPlaylist = headerInfo.album_type === "playlist";

    var tracks = [];
    for (var i = 0; i < trackCandidates.length; i++) {
      var node = trackCandidates[i];
      var possible =
        node.musicResponsiveListItemRenderer ||
        node.playlistPanelVideoRenderer ||
        node;
      var parsed = parseItemExtended(possible);
      if (parsed) {
        applyBrowseCollectionMetadata(parsed, headerInfo, isPlaylist);
        var sanitized = sanitizeTrackBeforeReturn(parsed);
        if (sanitized) {
          if (!sanitized.track_number) {
            sanitized.track_number = tracks.length + 1;
          }
          tracks.push(sanitized);
        }
      }
    }

    L("info", "parseBrowseResponse parsed tracks", tracks.length);

    headerInfo.total_tracks = Math.max(
      headerInfo.total_tracks || 0,
      tracks.length,
    );
    for (var trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
      tracks[trackIndex].total_tracks = headerInfo.total_tracks;
      if (!tracks[trackIndex].album_artist)
        tracks[trackIndex].album_artist = headerInfo.artists;
      if (!tracks[trackIndex].release_date)
        tracks[trackIndex].release_date = headerInfo.release_date;
      if (!tracks[trackIndex].album_type)
        tracks[trackIndex].album_type = headerInfo.album_type;
    }

    if (!headerInfo.cover_url && tracks.length > 0 && tracks[0].cover_url) {
      headerInfo.cover_url = tracks[0].cover_url;
      L(
        "debug",
        "parseBrowseResponse using first track cover as fallback",
        headerInfo.cover_url,
      );
    }

    L("debug", "parseBrowseResponse final cover_url", headerInfo.cover_url);

    return {
      type: headerInfo.album_type === "playlist" ? "playlist" : "album",
      album: headerInfo,
      tracks: tracks,
      cover_url: headerInfo.cover_url,
      name: headerInfo.name,
      _continuationToken: continuationInfo.token || null,
    };
  } catch (e) {
    L("error", "parseBrowseResponse error", String(e));
    return null;
  }
}

function fetchVideoMetadataSync(videoId) {
  return null;
}

function getTrack(trackID) {
  var videoID = String(trackID || "").trim();
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoID)) return null;
  var cached = cacheGet("yt:video:" + videoID);
  if (cached) return enrichTrack(cached);

  var raw = fetchVideoMetadataSync(videoID);
  if (!raw) return null;
  var candidates = performSearchSync(
    (raw.artist ? raw.artist + " " : "") + raw.title,
    YT_SEARCH_PARAMS.tracks,
  );
  for (var i = 0; i < candidates.length; i++) {
    if (candidates[i] && candidates[i].id === videoID) {
      raw = candidates[i];
      break;
    }
  }
  var sanitized = sanitizeTrackBeforeReturn(raw);
  return sanitized ? enrichTrack(sanitized) : null;
}

function getAlbum(albumId) {
  L("info", "getAlbum called", albumId);
  try {
    var result = fetchBrowseTracksSync(albumId);
    if (result && result.tracks) {
      return {
        id: albumId,
        name: result.name || "",
        artists: result.album ? result.album.artists : "",
        artist_id: result.album ? result.album.artist_id : "",
        cover_url: result.cover_url,
        release_date: result.album ? result.album.release_date : "",
        total_tracks: result.tracks.length,
        album_type: result.album ? result.album.album_type : "album",
        tracks: result.tracks,
        provider_id: "ytmusic-spotiflac",
      };
    }
    return null;
  } catch (e) {
    L("error", "getAlbum error", String(e));
    return null;
  }
}

function getPlaylist(playlistId) {
  L("info", "getPlaylist called", playlistId);
  try {
    var browseId = playlistId;
    if (!playlistId.startsWith("VL") && !playlistId.startsWith("RDCLAK5uy_")) {
      browseId = "VL" + playlistId;
    }

    var result = fetchBrowseTracksSync(browseId);
    if (result && result.tracks) {
      return {
        id: playlistId,
        name: result.name || "",
        owner: result.album ? result.album.artists : "",
        cover_url: result.cover_url,
        total_tracks: result.tracks.length,
        tracks: result.tracks,
        provider_id: "ytmusic-spotiflac",
      };
    }
    return null;
  } catch (e) {
    L("error", "getPlaylist error", String(e));
    return null;
  }
}

function getArtist(artistId) {
  L("info", "getArtist called", artistId);
  try {
    var url = "https://music.youtube.com/youtubei/v1/browse?alt=json";
    var body = JSON.stringify({
      context: {
        client: {
          clientName: "WEB_REMIX",
          clientVersion: CONFIG.clientVersion,
        },
      },
      browseId: artistId,
    });

    var res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": getRandomUserAgent(),
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: body,
    });

    if (!res || !res.ok) {
      L("error", "getArtist fetch failed", res ? res.status : "no response");
      return null;
    }

    var data = res.json();
    if (!data) {
      L("error", "getArtist json parse failed");
      return null;
    }

    var artistName = "";
    var artistImage = null;
    var headerImage = null;
    var monthlyListeners = null;

    if (data.header) {
      var header =
        data.header.musicImmersiveHeaderRenderer ||
        data.header.musicVisualHeaderRenderer ||
        data.header.musicDetailHeaderRenderer;
      if (header) {
        if (header.title && header.title.runs) {
          artistName = header.title.runs
            .map(function (r) {
              return r.text;
            })
            .join("");
        }
        if (
          header.thumbnail &&
          header.thumbnail.musicThumbnailRenderer &&
          header.thumbnail.musicThumbnailRenderer.thumbnail
        ) {
          var thumbUrl = pickLastThumbnailUrl(
            header.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
          );
          artistImage = makeSquareThumb(thumbUrl);
        }
        if (
          !artistImage &&
          header.foregroundThumbnail &&
          header.foregroundThumbnail.musicThumbnailRenderer
        ) {
          var thumbUrl2 = pickLastThumbnailUrl(
            header.foregroundThumbnail.musicThumbnailRenderer.thumbnail
              .thumbnails,
          );
          artistImage = makeSquareThumb(thumbUrl2);
        }
        if (
          header.subscriptionButton &&
          header.subscriptionButton.subscribeButtonRenderer
        ) {
          var subButton = header.subscriptionButton.subscribeButtonRenderer;
          if (
            subButton.subscriberCountText &&
            subButton.subscriberCountText.runs
          ) {
            var subText = subButton.subscriberCountText.runs
              .map(function (r) {
                return r.text;
              })
              .join("");
            var subMatch = subText.match(/^([\d.]+)([KMB])?/i);
            if (subMatch) {
              var num = parseFloat(subMatch[1]);
              var mult = subMatch[2] ? subMatch[2].toUpperCase() : "";
              if (mult === "K") num *= 1000;
              else if (mult === "M") num *= 1000000;
              else if (mult === "B") num *= 1000000000;
              monthlyListeners = Math.round(num);
            }
          }
        }
      }
    }

    var topTracks = [];
    var albums = [];

    if (data.contents) {
      var sectionList = findSectionList(data.contents);
      if (sectionList && sectionList.contents) {
        for (var si = 0; si < sectionList.contents.length; si++) {
          var section = sectionList.contents[si];

          if (section.musicShelfRenderer) {
            var shelf = section.musicShelfRenderer;
            var shelfTitle = "";
            if (shelf.title && shelf.title.runs) {
              shelfTitle = shelf.title.runs
                .map(function (r) {
                  return r.text;
                })
                .join("")
                .toLowerCase();
            }

            if (
              shelfTitle.indexOf("song") !== -1 ||
              shelfTitle.indexOf("top") !== -1 ||
              shelfTitle.indexOf("popular") !== -1
            ) {
              L("info", "getArtist found songs section:", shelfTitle);
              if (shelf.contents) {
                for (
                  var ti = 0;
                  ti < shelf.contents.length && topTracks.length < 10;
                  ti++
                ) {
                  var trackNode = shelf.contents[ti];
                  var parsed = parseItemExtended(trackNode);
                  if (parsed && parsed.id) {
                    var sanitized = sanitizeTrackBeforeReturn(parsed);
                    if (sanitized) {
                      // Add artist name if missing
                      if (!sanitized.artists) {
                        sanitized.artists = artistName;
                      }
                      topTracks.push(sanitized);
                    }
                  }
                }
              }
              L("info", "getArtist found top tracks", topTracks.length);
            }
          }

          if (section.musicCarouselShelfRenderer) {
            var carousel = section.musicCarouselShelfRenderer;
            if (carousel.contents) {
              for (var ci = 0; ci < carousel.contents.length; ci++) {
                var node = carousel.contents[ci];
                var parsed = parseCollectionItem(node);
                if (
                  parsed &&
                  (parsed.item_type === "album" ||
                    parsed.item_type === "playlist")
                ) {
                  var sanitized = sanitizeCollectionBeforeReturn(parsed);
                  if (sanitized) {
                    albums.push({
                      id: sanitized.id,
                      name: sanitized.name,
                      artists: sanitized.artists || artistName,
                      cover_url: sanitized.cover_url,
                      release_date: sanitized.release_date || "",
                      total_tracks: 0,
                      album_type: sanitized.album_type || "album",
                      provider_id: "ytmusic-spotiflac",
                    });
                  }
                }
              }
            }
          }
        }
      }

      if (albums.length === 0) {
        var candidates = [];
        collectItemsFromNode(data.contents, candidates, 0);

        for (var i = 0; i < candidates.length; i++) {
          var node = candidates[i];
          var parsed = parseCollectionItem(node);
          if (
            parsed &&
            (parsed.item_type === "album" || parsed.item_type === "playlist")
          ) {
            var sanitized = sanitizeCollectionBeforeReturn(parsed);
            if (sanitized) {
              albums.push({
                id: sanitized.id,
                name: sanitized.name,
                artists: sanitized.artists || artistName,
                cover_url: sanitized.cover_url,
                release_date: sanitized.release_date || "",
                total_tracks: 0,
                album_type: sanitized.album_type || "album",
                provider_id: "ytmusic-spotiflac",
              });
            }
          }
        }
      }
    }

    L("info", "getArtist found albums", albums.length);
    L("info", "getArtist found top_tracks", topTracks.length);

    return {
      id: artistId,
      name: artistName,
      image_url: artistImage,
      header_image: headerImage,
      listeners: monthlyListeners,
      albums: albums,
      top_tracks: topTracks,
      provider_id: "ytmusic-spotiflac",
    };
  } catch (e) {
    L("error", "getArtist error", String(e));
    return null;
  }
}

function findSectionList(contents) {
  if (!contents) return null;

  if (contents.sectionListRenderer) {
    return contents.sectionListRenderer;
  }

  if (contents.singleColumnBrowseResultsRenderer) {
    var tabs = contents.singleColumnBrowseResultsRenderer.tabs;
    if (tabs && tabs[0] && tabs[0].tabRenderer && tabs[0].tabRenderer.content) {
      if (tabs[0].tabRenderer.content.sectionListRenderer) {
        return tabs[0].tabRenderer.content.sectionListRenderer;
      }
    }
  }

  if (contents.twoColumnBrowseResultsRenderer) {
    var secondaryContents =
      contents.twoColumnBrowseResultsRenderer.secondaryContents;
    if (secondaryContents && secondaryContents.sectionListRenderer) {
      return secondaryContents.sectionListRenderer;
    }
    var tabs = contents.twoColumnBrowseResultsRenderer.tabs;
    if (tabs && tabs[0] && tabs[0].tabRenderer && tabs[0].tabRenderer.content) {
      if (tabs[0].tabRenderer.content.sectionListRenderer) {
        return tabs[0].tabRenderer.content.sectionListRenderer;
      }
    }
  }

  return null;
}

function fetchJSONSync(url, options) {
  try {
    var response = fetch(
      url,
      options || {
        method: "GET",
        headers: { "User-Agent": getRandomUserAgent() },
      },
    );
    if (!response || !response.ok) return null;
    var data = response.json();
    return data && !data.error ? data : null;
  } catch (e) {
    L("debug", "fetchJSONSync failed", url, String(e));
    return null;
  }
}

function metadataMatchValue(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[’‘`]/g, "'")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function primaryArtistMatches(expected, actual) {
  var expectedPrimary = String(expected || "").split(
    /\s*,\s*|\s+feat\.?\s+|\s+ft\.?\s+/i,
  )[0];
  return metadataMatchValue(expectedPrimary) === metadataMatchValue(actual);
}

function parseTrackCredits(data) {
  var result = {
    title: "",
    artists: "",
    composer: "",
    producer: "",
    release_date: "",
    cover_url: null,
  };
  var actions = data && data.onResponseReceivedActions;
  if (!Array.isArray(actions)) return result;
  for (var ai = 0; ai < actions.length; ai++) {
    var dialog =
      actions[ai] &&
      actions[ai].openPopupAction &&
      actions[ai].openPopupAction.popup &&
      actions[ai].openPopupAction.popup.dismissableDialogRenderer;
    if (!dialog) continue;
    var metadata =
      dialog.metadata && dialog.metadata.musicMultiRowListItemRenderer;
    if (metadata) {
      result.title = runsText((metadata.title && metadata.title.runs) || []);
      result.artists = runsText(
        (metadata.subtitle && metadata.subtitle.runs) || [],
      );
      var secondTitle = runsText(
        (metadata.secondTitle && metadata.secondTitle.runs) || [],
      );
      var yearMatch = secondTitle.match(/\b(19|20)\d{2}\b/);
      if (yearMatch) result.release_date = yearMatch[0];
      var thumbnailURL = pickLastThumbnailUrl(
        metadata.thumbnail &&
          metadata.thumbnail.musicThumbnailRenderer &&
          metadata.thumbnail.musicThumbnailRenderer.thumbnail &&
          metadata.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails,
      );
      if (thumbnailURL) result.cover_url = makeSquareThumb(thumbnailURL);
    }
    var sections = dialog.sections || [];
    for (var si = 0; si < sections.length; si++) {
      var section =
        sections[si] && sections[si].dismissableDialogContentSectionRenderer;
      if (!section) continue;
      var role = runsText(
        (section.title && section.title.runs) || [],
      ).toLowerCase();
      var names = uniqueStrings(
        ((section.subtitle && section.subtitle.runs) || []).map(function (run) {
          return String((run && run.text) || "").trim();
        }),
      ).join("; ");
      if (/written by|songwriters?|composers?|lyrics by/.test(role)) {
        result.composer = names;
      } else if (/produced by|producers?/.test(role)) {
        result.producer = names;
      } else if (/performed by|artists?/.test(role) && !result.artists) {
        result.artists = names;
      }
    }
    break;
  }
  return result;
}

function fetchTrackCreditsSync(videoID) {
  var cacheKey = "yt:credits:" + videoID;
  var cached = cacheGet(cacheKey);
  if (cached) return cached;
  var data = fetchJSONSync(
    "https://music.youtube.com/youtubei/v1/browse?alt=json",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept-Language": "en-US,en;q=0.9",
        "User-Agent": getRandomUserAgent(),
        "x-youtube-client-name": "WEB_REMIX",
        "x-youtube-client-version": CONFIG.clientVersion,
      },
      body: JSON.stringify({
        context: {
          client: {
            clientName: "WEB_REMIX",
            clientVersion: CONFIG.clientVersion,
            hl: "en",
            gl: "US",
          },
        },
        browseId: "MPTC" + videoID,
      }),
    },
  );
  var result = parseTrackCredits(data);
  cacheSet(cacheKey, result);
  return result;
}

function normalizeDeezerAlbumType(value) {
  var type = String(value || "album").toLowerCase();
  if (type === "ep" || type === "single") return type;
  return "album";
}

function deezerComposerNames(trackData) {
  var contributors = (trackData && trackData.contributors) || [];
  var names = [];
  for (var i = 0; i < contributors.length; i++) {
    var role = String(
      (contributors[i] && contributors[i].role) || "",
    ).toLowerCase();
    if (/composer|songwriter|writer|author/.test(role))
      names.push(contributors[i].name);
  }
  return uniqueStrings(names).join("; ");
}

function fetchValidatedDeezerMetadata(track) {
  var title = String(track.name || track.title || "").trim();
  var artists = String(track.artists || track.artist || "").trim();
  var albumName = String(track.album_name || track.album || "").trim();
  var durationSeconds =
    Math.round(Number(track.duration_ms || 0) / 1000) ||
    Number(track.duration || 0) ||
    0;
  if (!title || !artists) return null;

  var query =
    'track:"' +
    title.replace(/"/g, "") +
    '" artist:"' +
    artists.split(",")[0].replace(/"/g, "") +
    '"';
  if (albumName) query += ' album:"' + albumName.replace(/"/g, "") + '"';
  var search = fetchJSONSync(
    "https://api.deezer.com/search?q=" +
      encodeURIComponent(query) +
      "&limit=10",
  );
  if (!search || !Array.isArray(search.data)) return null;

  var candidate = null;
  for (var i = 0; i < search.data.length; i++) {
    var item = search.data[i];
    if (metadataMatchValue(item.title) !== metadataMatchValue(title)) continue;
    if (!primaryArtistMatches(artists, item.artist && item.artist.name))
      continue;
    if (
      albumName &&
      metadataMatchValue(item.album && item.album.title) !==
        metadataMatchValue(albumName)
    )
      continue;
    if (
      durationSeconds &&
      item.duration &&
      Math.abs(Number(item.duration) - durationSeconds) > 5
    )
      continue;
    candidate = item;
    break;
  }
  if (!candidate) return null;

  var trackData = fetchJSONSync(
    "https://api.deezer.com/track/" + encodeURIComponent(String(candidate.id)),
  );
  if (
    !trackData ||
    metadataMatchValue(trackData.title) !== metadataMatchValue(title) ||
    !primaryArtistMatches(artists, trackData.artist && trackData.artist.name)
  )
    return null;
  if (
    durationSeconds &&
    trackData.duration &&
    Math.abs(Number(trackData.duration) - durationSeconds) > 5
  )
    return null;

  var deezerAlbumName = String(
    (trackData.album && trackData.album.title) ||
      (candidate.album && candidate.album.title) ||
      "",
  );
  var sameAlbum =
    !!albumName &&
    metadataMatchValue(deezerAlbumName) === metadataMatchValue(albumName);
  var albumData =
    sameAlbum && trackData.album && trackData.album.id
      ? fetchJSONSync(
          "https://api.deezer.com/album/" +
            encodeURIComponent(String(trackData.album.id)),
        )
      : null;
  if (
    albumData &&
    metadataMatchValue(albumData.title) !== metadataMatchValue(albumName)
  )
    albumData = null;

  var deezerURL = String(
    trackData.link || "https://www.deezer.com/track/" + candidate.id,
  );
  var result = {
    isrc: String(trackData.isrc || "").trim(),
    preview_url: String(trackData.preview || "").trim(),
    explicit: trackData.explicit_lyrics === true,
    deezer_id: String(candidate.id),
    deezer_url: deezerURL,
    composer: deezerComposerNames(trackData),
  };
  if (albumData) {
    result.album_artist = String(
      (albumData.artist && albumData.artist.name) || "",
    ).trim();
    result.release_date = String(albumData.release_date || "").trim();
    result.track_number = Number(trackData.track_position || 0) || 0;
    result.total_tracks = Number(albumData.nb_tracks || 0) || 0;
    result.disc_number = Number(trackData.disk_number || 0) || 0;
    result.total_discs = 1;
    if (albumData.tracks && Array.isArray(albumData.tracks.data)) {
      for (var ti = 0; ti < albumData.tracks.data.length; ti++) {
        result.total_discs = Math.max(
          result.total_discs,
          Number(albumData.tracks.data[ti].disk_number || 1),
        );
      }
    }
    result.album_type = normalizeDeezerAlbumType(albumData.record_type);
    result.upc = String(albumData.upc || "").trim();
    result.label = String(albumData.label || "").trim();
    result.copyright = String(albumData.copyright || "").trim();
    var genres = (albumData.genres && albumData.genres.data) || [];
    result.genre = uniqueStrings(
      genres.map(function (genre) {
        return genre && genre.name;
      }),
    ).join("; ");
  }
  return result;
}

function enrichTrack(track) {
  L("info", "enrichTrack called", track ? track.id : "null");
  if (!track || !track.id) return track;

  var cacheKey = "yt:enrichment:" + track.id;
  var cached = cacheGet(cacheKey);
  if (cached) return Object.assign({}, track, cached);

  var youtubeURL =
    "https://music.youtube.com/watch?v=" + encodeURIComponent(track.id);
  var enrichment = {
    external_urls: youtubeURL,
    external_links: Object.assign({}, track.external_links || {}, {
      youtube: youtubeURL,
    }),
  };

  var credits = fetchTrackCreditsSync(String(track.id));
  if (credits) {
    if (credits.composer) enrichment.composer = credits.composer;
    if (!track.artists && credits.artists) enrichment.artists = credits.artists;
    if (!track.name && credits.title) enrichment.name = credits.title;
    if (!track.release_date && credits.release_date)
      enrichment.release_date = credits.release_date;
    if (!track.cover_url && credits.cover_url)
      enrichment.cover_url = credits.cover_url;
  }

  var albumID = String(track.album_id || "").trim();
  if (albumID.startsWith("MPREb_")) {
    var nativeAlbum = _fetchAlbumHeaderMetadata(albumID);
    if (nativeAlbum) {
      if (!track.album_name && nativeAlbum.name)
        enrichment.album_name = nativeAlbum.name;
      if (!track.album_artist && nativeAlbum.artists)
        enrichment.album_artist = nativeAlbum.artists;
      if (!track.artist_id && nativeAlbum.artist_id)
        enrichment.artist_id = nativeAlbum.artist_id;
      if (!track.artist_url && nativeAlbum.artist_id)
        enrichment.artist_url =
          "https://music.youtube.com/channel/" + nativeAlbum.artist_id;
      if (!track.release_date && nativeAlbum.release_date)
        enrichment.release_date = nativeAlbum.release_date;
      if (!track.album_type && nativeAlbum.album_type)
        enrichment.album_type = nativeAlbum.album_type;
      if (!track.total_tracks && nativeAlbum.total_tracks)
        enrichment.total_tracks = nativeAlbum.total_tracks;
      if (!track.cover_url && nativeAlbum.cover_url)
        enrichment.cover_url = nativeAlbum.cover_url;
    }
  }

  var lookupTrack = Object.assign({}, track, enrichment);
  var deezer = fetchValidatedDeezerMetadata(lookupTrack);
  if (deezer) {
    if (deezer.isrc) enrichment.isrc = deezer.isrc;
    if (deezer.preview_url) enrichment.preview_url = deezer.preview_url;
    if (!enrichment.composer && !track.composer && deezer.composer)
      enrichment.composer = deezer.composer;
    if (deezer.explicit) enrichment.explicit = true;
    enrichment.deezer_id = deezer.deezer_id;
    enrichment.external_links.deezer = deezer.deezer_url;
    var albumFields = [
      "album_artist",
      "track_number",
      "total_tracks",
      "disc_number",
      "total_discs",
      "album_type",
      "upc",
      "label",
      "copyright",
      "genre",
    ];
    for (var fi = 0; fi < albumFields.length; fi++) {
      var field = albumFields[fi];
      if (
        (!track[field] ||
          field === "genre" ||
          field === "label" ||
          field === "copyright" ||
          field === "upc") &&
        deezer[field]
      ) {
        enrichment[field] = deezer[field];
      }
    }
    if (
      deezer.release_date &&
      (!track.release_date || /^\d{4}$/.test(String(track.release_date)))
    ) {
      enrichment.release_date = deezer.release_date;
    }
  }

  cacheSet(cacheKey, enrichment);
  L("info", "enrichTrack: success", {
    id: track.id,
    hasComposer: !!enrichment.composer,
    hasIsrc: !!enrichment.isrc,
    hasGenre: !!enrichment.genre,
  });
  return Object.assign({}, track, enrichment);
}

var YT_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";

function getTimeBasedGreeting() {
  var localTime = gobackend.getLocalTime();
  var hour = localTime.hour;

  L(
    "debug",
    "getTimeBasedGreeting: localHour=" +
      hour +
      ", timezone=" +
      localTime.timezone,
  );

  if (hour >= 5 && hour < 12) {
    return "Good morning";
  } else if (hour >= 12 && hour < 17) {
    return "Good afternoon";
  } else if (hour >= 17 && hour < 21) {
    return "Good evening";
  } else {
    return "Good night";
  }
}

function parseHomeFeedSection(section) {
  try {
    var sectionRenderer =
      section.musicCarouselShelfRenderer || section.musicShelfRenderer;
    if (!sectionRenderer) return null;

    var title = "";
    var header = sectionRenderer.header;
    if (header) {
      var headerRenderer =
        header.musicCarouselShelfBasicHeaderRenderer ||
        header.musicShelfBasicHeaderRenderer;
      if (
        headerRenderer &&
        headerRenderer.title &&
        headerRenderer.title.runs &&
        headerRenderer.title.runs[0]
      ) {
        title = headerRenderer.title.runs[0].text;
      }
    }

    if (!title) return null;

    var contents = sectionRenderer.contents || [];
    var items = [];

    for (var i = 0; i < contents.length && items.length < 20; i++) {
      var item = parseHomeFeedItem(contents[i]);
      if (item) items.push(item);
    }

    if (items.length === 0) return null;

    return {
      uri: "",
      title: title,
      items: items,
    };
  } catch (e) {
    L("warn", "parseHomeFeedSection error", String(e));
    return null;
  }
}

function parseHomeFeedItem(itemContainer) {
  try {
    var item =
      itemContainer.musicTwoRowItemRenderer ||
      itemContainer.musicResponsiveListItemRenderer ||
      itemContainer.musicNavigationButtonRenderer;

    if (!item) return null;

    var name = "";
    if (item.title && item.title.runs && item.title.runs[0]) {
      name = item.title.runs[0].text;
    }
    if (!name && item.flexColumns && item.flexColumns[0]) {
      var fcr = item.flexColumns[0].musicResponsiveListItemFlexColumnRenderer;
      if (fcr && fcr.text && fcr.text.runs && fcr.text.runs[0]) {
        name = fcr.text.runs[0].text;
      }
    }

    if (!name) return null;

    var artists = "";
    var durationMs = 0;
    if (item.subtitle && item.subtitle.runs) {
      var artistParts = [];
      for (var i = 0; i < item.subtitle.runs.length; i++) {
        var run = item.subtitle.runs[i];
        if (run && run.text) {
          var txt = run.text.trim();
          if (txt === "•" || txt === " • " || txt === "," || txt === " & ")
            continue;
          var lowerTxt = txt.toLowerCase();
          if (
            lowerTxt === "single" ||
            lowerTxt === "album" ||
            lowerTxt === "ep" ||
            lowerTxt === "playlist" ||
            lowerTxt === "video" ||
            lowerTxt === "song" ||
            lowerTxt === "artist"
          )
            continue;
          if (/^\d{4}$/.test(txt)) continue;
          if (
            /^\d+(\.\d+)?[KMB]?\s*(views|plays|listeners|subscribers)/i.test(
              txt,
            )
          )
            continue;
          if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(txt)) {
            var durationSec = parseDurationText(txt);
            if (durationSec > 0) {
              durationMs = durationSec * 1000;
            }
            continue;
          }
          if (txt.length > 1) artistParts.push(txt);
        }
      }
      artists = artistParts.join(", ");
    }

    var itemType = "track";
    var itemId = "";
    var albumId = "";
    var albumName = "";

    var navEp = item.navigationEndpoint;
    if (navEp) {
      if (navEp.watchEndpoint && navEp.watchEndpoint.videoId) {
        itemType = "track";
        itemId = navEp.watchEndpoint.videoId;
        if (navEp.watchEndpoint.playlistId) {
          var plId = navEp.watchEndpoint.playlistId;
          if (plId.startsWith("OLAK5uy_")) {
            albumId = plId;
          }
        }
      } else if (navEp.browseEndpoint && navEp.browseEndpoint.browseId) {
        var browseId = navEp.browseEndpoint.browseId;
        itemId = browseId;

        if (browseId.startsWith("MPREb_")) {
          itemType = "album";
        } else if (
          browseId.startsWith("VL") ||
          browseId.startsWith("PL") ||
          browseId.startsWith("RDCLAK")
        ) {
          itemType = "playlist";
        } else if (browseId.startsWith("UC")) {
          itemType = "artist";
        }
      }
    }

    if (
      !itemId &&
      item.overlay &&
      item.overlay.musicItemThumbnailOverlayRenderer
    ) {
      var overlay = item.overlay.musicItemThumbnailOverlayRenderer;
      if (overlay.content && overlay.content.musicPlayButtonRenderer) {
        var playNav =
          overlay.content.musicPlayButtonRenderer.playNavigationEndpoint;
        if (playNav && playNav.watchEndpoint && playNav.watchEndpoint.videoId) {
          itemType = "track";
          itemId = playNav.watchEndpoint.videoId;
        }
      }
    }

    if (!itemId) return null;

    var coverUrl = null;
    if (
      item.thumbnailRenderer &&
      item.thumbnailRenderer.musicThumbnailRenderer
    ) {
      var thumbs = item.thumbnailRenderer.musicThumbnailRenderer.thumbnail;
      if (thumbs && thumbs.thumbnails && thumbs.thumbnails.length > 0) {
        coverUrl = thumbs.thumbnails[thumbs.thumbnails.length - 1].url;
      }
    }
    if (!coverUrl && item.thumbnail && item.thumbnail.musicThumbnailRenderer) {
      var thumbs2 = item.thumbnail.musicThumbnailRenderer.thumbnail;
      if (thumbs2 && thumbs2.thumbnails && thumbs2.thumbnails.length > 0) {
        coverUrl = thumbs2.thumbnails[thumbs2.thumbnails.length - 1].url;
      }
    }

    if (coverUrl) {
      coverUrl = makeSquareThumb(coverUrl);
    }

    return {
      id: itemId,
      uri: "ytmusic:" + itemType + ":" + itemId,
      type: itemType,
      name: name,
      artists: artists,
      description: "",
      cover_url: coverUrl,
      album_id: albumId,
      album_name: albumName,
      duration_ms: durationMs,
      provider_id: "ytmusic-spotiflac",
    };
  } catch (e) {
    L("warn", "parseHomeFeedItem error", String(e));
    return null;
  }
}

function getHomeFeed() {
  L("info", "getHomeFeed called");

  var cacheKey = "ytmusic:homefeed:" + CONFIG.homeCountry;
  var cached = cacheGet(cacheKey);
  if (cached) {
    L("debug", "getHomeFeed returning cached data");
    return cached;
  }

  try {
    var url = "https://music.youtube.com/youtubei/v1/browse?key=" + YT_API_KEY;
    var body = JSON.stringify({
      context: {
        client: {
          clientName: "WEB_REMIX",
          clientVersion: CONFIG.clientVersion,
          hl: "en",
          gl: CONFIG.homeCountry,
        },
      },
      browseId: "FEmusic_home",
    });

    var res = fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Origin: "https://music.youtube.com",
        Referer: "https://music.youtube.com/",
        "User-Agent": getRandomUserAgent(),
      },
      body: body,
    });

    if (!res || !res.ok) {
      L("error", "getHomeFeed fetch failed", res ? res.status : "no response");
      return {
        success: false,
        error: "Failed to fetch home feed",
        sections: [],
      };
    }

    var data = res.json();
    if (!data) {
      L("error", "getHomeFeed json parse failed");
      return {
        success: false,
        error: "Failed to parse response",
        sections: [],
      };
    }

    var sections = [];

    var sectionList = null;
    if (data.contents && data.contents.singleColumnBrowseResultsRenderer) {
      var tabs = data.contents.singleColumnBrowseResultsRenderer.tabs;
      if (
        tabs &&
        tabs[0] &&
        tabs[0].tabRenderer &&
        tabs[0].tabRenderer.content
      ) {
        sectionList = tabs[0].tabRenderer.content.sectionListRenderer;
      }
    }

    if (sectionList && sectionList.contents) {
      for (
        var i = 0;
        i < sectionList.contents.length && sections.length < 10;
        i++
      ) {
        var sectionData = sectionList.contents[i];
        var section = parseHomeFeedSection(sectionData);
        if (section) {
          sections.push(section);
        }
      }
    }

    L("info", "getHomeFeed parsed", sections.length, "sections");

    var result = {
      success: true,
      greeting: getTimeBasedGreeting(),
      sections: sections,
    };

    cacheSet(cacheKey, result);

    return result;
  } catch (e) {
    L("error", "getHomeFeed error", String(e));
    return { success: false, error: String(e), sections: [] };
  }
}

registerExtension({
  customSearch: function (query, options) {
    L("info", "customSearch", query, "options:", JSON.stringify(options));
    try {
      return customSearchSync(query, options);
    } catch (e) {
      L("error", "customSearch fatal", String(e));
      return [];
    }
  },
  getTrack: getTrack,
  getAlbum: getAlbum,
  getPlaylist: getPlaylist,
  getArtist: getArtist,
  getHomeFeed: getHomeFeed,
  getRelatedTracks: getRelatedTracks,
  enrichTrack: enrichTrack
});
