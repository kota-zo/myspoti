/*
 * Myspoti player bridge.
 *
 * Runs inside the official Web Player page and exposes its player through one
 * small, host-agnostic contract. The same file is loaded by the Android WebView
 * and by desktop Chrome over CDP, so every command can be tested on a PC.
 *
 * Host -> page: window.Myspoti.call(id, method, args)  (also returns a Promise)
 * Page -> host: one JSON string per message, sent to the first host channel found:
 *   - window.MyspotiHost.postMessage(json)  Android web message listener (open.spotify.com only)
 *   - window.__mspHostPost(json)            CDP Runtime.addBinding
 * Messages: {type: "ready"} | {type: "state", state} | {type: "queue", queue}
 *         | {type: "result", id, ok, value | error} | {type: "error", error}
 *
 * The bridge only reads the page's own service registry; it patches nothing.
 */
(() => {
  // Injected again (a second page-finished event for the same page): say ready again only
  // when the host asks for it (window.__mspReannounce, set when it is not ready). A second
  // "ready" for one page load would run every ready collector twice (state refresh, web
  // token, audio quality, volume).
  if (window.Myspoti && window.Myspoti.version) {
    if (window.Myspoti.announce && window.__mspReannounce) {
      window.__mspReannounce = false;
      window.Myspoti.announce();
    }
    return;
  }

  // The page always sees itself visible: a hidden page has its timers and rendering throttled,
  // and the engine runs with the app in the background (the host already tells Chromium the view
  // is shown). Nothing in this bridge reads the page's visibility.
  try {
    Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => false });
    Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => 'visible' });
    Object.defineProperty(Document.prototype, 'webkitHidden', { configurable: true, get: () => false });
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', { configurable: true, get: () => 'visible' });
    document.hasFocus = () => true;
    for (const t of ['visibilitychange', 'webkitvisibilitychange']) {
      document.addEventListener(t, e => e.stopImmediatePropagation(), true);
      window.addEventListener(t, e => e.stopImmediatePropagation(), true);
    }
  } catch (e) { /* keep the real values */ }

  const VERSION = 1;
  const READY_TIMEOUT_MS = 60000;

  const post = msg => {
    const json = JSON.stringify(msg);
    try {
      if (window.MyspotiHost && typeof window.MyspotiHost.postMessage === 'function') return window.MyspotiHost.postMessage(json);
      if (typeof window.__mspHostPost === 'function') return window.__mspHostPost(json);
    } catch (e) { /* host gone: nothing to report to */ }
  };

  // --- service lookup -------------------------------------------------------

  const findPlatform = () => {
    const host = document.getElementById('main') || document.body;
    const roots = [host, ...host.querySelectorAll('*')];
    for (const el of roots) {
      const key = Object.keys(el).find(k => k.startsWith('__reactContainer$'));
      if (!key) continue;
      const stack = [el[key]];
      while (stack.length) {
        const f = stack.pop();
        const v = f.memoizedProps && f.memoizedProps.value;
        if (v && typeof v === 'object' && typeof v.getRegistry === 'function') return v;
        if (f.sibling) stack.push(f.sibling);
        if (f.child) stack.push(f.child);
      }
    }
    return null;
  };

  let services = null;
  const resolveServices = () => {
    const platform = findPlatform();
    if (!platform) return null;
    const registry = platform.getRegistry();
    const byName = {};
    for (const key of registry._map.keys()) {
      if (key && key.description) byName[key.description] = key;
    }
    const get = name => {
      if (!byName[name]) throw new Error(`service ${name} not found`);
      return registry.resolve(byName[name]);
    };
    const player = get('PlayerAPI');
    if (!player || typeof player.getState !== 'function') return null;
    return {
      platform,
      player,
      playback: get('PlaybackAPI'),
      library: get('LibraryAPI'),
      token: get('TokenProvider'),
      graphql: platform.getGraphQLLoader(),
      byName,
      resolve: name => get(name),
    };
  };

  // --- state mapping --------------------------------------------------------

  const image = images => {
    if (!images || !images.length) return null;
    return [...images].sort((a, b) => (b.width || 0) - (a.width || 0))[0].url;
  };

  // Ads come in the same queue as tracks, with `type: "ad"` and a title instead of a name.
  // Some markets give them a track-like uri: the metadata still flags them.
  const isAd = item => item.type === 'ad' || /^spotify:ad:/.test(item.uri || '') ||
    item.provider === 'ad' || !!item.advertiser ||
    (item.metadata && (item.metadata.is_advertisement === 'true' || item.metadata.is_advertisement === true || !!item.metadata.ad_id));
  const kind = item => isAd(item) ? 'ad'
    : /^spotify:episode:/.test(item.uri) ? 'episode' : 'track';

  const track = item => item && {
    kind: kind(item),
    uri: item.uri,
    uid: item.uid || null,
    name: item.name || item.title || '',
    advertiser: item.advertiser || null,
    durationMs: item.duration ? item.duration.milliseconds : null,
    artists: (item.artists || []).map(a => ({ uri: a.uri, name: a.name })),
    album: item.album ? { uri: item.album.uri, name: item.album.name } : null,
    image: image(item.images || (item.album && item.album.images)),
    explicit: !!item.isExplicit,
    mediaType: item.mediaType || 'audio',
    provider: item.provider || null,
  };

  const lastReal = items => (items || []).filter(i => kind(i) !== 'ad');
  const mapState = s => s && {
    track: track(s.item),
    contextUri: s.context ? s.context.uri : null,
    contextName: s.context && s.context.metadata ? s.context.metadata.context_description || null : null,
    previous: track(lastReal(s.previousItems).slice(-1)[0]),
    next: track(lastReal(s.nextItems)[0]),
    isPaused: !!s.isPaused,
    isBuffering: !!s.isBuffering,
    positionMs: s.positionAsOfTimestamp,
    timestamp: s.timestamp,
    durationMs: s.duration,
    shuffle: !!s.shuffle,
    repeat: s.repeat, // 0 off, 1 context, 2 track
    speed: s.speed,
    restrictions: s.restrictions || {},
    playbackId: s.playbackId || null,
  };

  const mapQueue = q => q && {
    current: track(q.current),
    queued: (q.queued || []).map(track),
    nextUp: (q.nextUp || []).map(track),
  };

  // --- serialization --------------------------------------------------------

  const methodsOf = obj => {
    const names = new Set();
    for (let p = obj; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
      for (const n of Object.getOwnPropertyNames(p)) {
        if (n === 'constructor' || n.startsWith('_')) continue;
        try { if (typeof p[n] === 'function') names.add(n); } catch (e) { /* getter threw */ }
      }
    }
    return [...names].sort();
  };

  // Plain JSON copy of whatever a service returns: drops functions, breaks cycles,
  // turns Maps/Sets into objects/arrays and BigInts into strings.
  const toJson = (value, depth = 0, seen = new WeakSet()) => {
    if (value === null || value === undefined) return null;
    const t = typeof value;
    if (t === 'bigint') return value.toString();
    if (t === 'function' || t === 'symbol') return undefined;
    if (t !== 'object') return value;
    if (depth > 12 || seen.has(value)) return null;
    seen.add(value);
    if (value instanceof Date) return value.toISOString();
    if (value instanceof Map) value = Object.fromEntries(value);
    if (value instanceof Set) value = [...value];
    if (ArrayBuffer.isView(value)) return Array.from(value);
    if (Array.isArray(value)) return value.map(v => { const j = toJson(v, depth + 1, seen); return j === undefined ? null : j; });
    const out = {};
    for (const k of Object.keys(value)) {
      if (k.startsWith('_')) continue;
      const j = toJson(value[k], depth + 1, seen);
      if (j !== undefined) out[k] = j;
    }
    return out;
  };

  // The page player's own media elements: kept inside its objects, never in the document.
  const playerMedia = async () => {
    const found = new Set();
    const sdk = need().resolve('PlayerSDK');
    const harmony = sdk && sdk.harmony;
    const streamer = harmony && harmony._streamer;
    const roots = [harmony];
    if (streamer && streamer._playerPromise) roots.push(await streamer._playerPromise);
    const seen = new WeakSet();
    const walk = (o, depth) => {
      if (!o || typeof o !== 'object' || seen.has(o) || depth > 6) return;
      seen.add(o);
      if (o instanceof HTMLMediaElement) { found.add(o); return; }
      if (o instanceof Node || o instanceof Window) return;
      let keys = [];
      try { keys = Object.keys(o); } catch (e) { return; }
      for (const k of keys.slice(0, 80)) {
        let v;
        try { v = o[k]; } catch (e) { continue; }
        walk(v, depth + 1);
      }
    };
    roots.forEach(r => walk(r, 0));
    return [...found];
  };

  // The page's own audio player (PlayerSDK.harmony's streamer), once it exists.
  const localPlayer = async () => {
    const sdk = need().resolve('PlayerSDK');
    const streamer = sdk && sdk.harmony && sdk.harmony._streamer;
    if (!streamer || !streamer._playerPromise) return null;
    return Promise.race([streamer._playerPromise, new Promise(r => setTimeout(() => r(null), 2000))]);
  };

  // Not heard, yet not "muted audio" to Chromium (see 'media.mute').
  const SILENT = 0.001;

  // A fade is a list of setTimeout steps: a second fade, a plain volume set or a mute must
  // cancel the pending ones, or they would override it seconds later (the up-ramp of a
  // crossfade undone by the tail of its own down-ramp, a restored volume pulled down again).
  let fadeTimers = [];
  // The watch of a change of song (riseOnSwitch): apart, as it outlives the fade-out it follows.
  let riseTimers = [];
  const cancelFade = () => { fadeTimers.forEach(clearTimeout); fadeTimers = []; riseTimers.forEach(clearTimeout); riseTimers = []; };

  // A ramp heard as even: the gain follows a cubic curve (the usual "audio taper", the one the
  // page applies to its own volume when _cubicVolume is set), so equal times give equal steps
  // to the ear. A linear or stepped ramp was heard as a jump: most of it in the first moments.
  // Ticks every 16 ms; each sets the level the clock is at (a late tick never stretches it).
  // fromK/toK: positions on that curve (0 = silent, 1 = the player's own level, read at every
  // tick so a volume change meanwhile is kept). -> a function that starts it.
  const ramp = (el, player, fromK, toK, ms, done) => () => {
    cancelFade();
    const t0 = performance.now();
    const tick = () => {
      const k = Math.min(1, (performance.now() - t0) / ms);
      const p = fromK + (toK - fromK) * k;
      el.volume = Math.min(1, Math.max(SILENT, ownLevel(player) * p * p * p));
      if (k < 1) fadeTimers.push(setTimeout(tick, 16));
      else if (done) done();
    };
    tick();
  };
  // The sound held down through a change of song, and up along the curve over {ms} once the next
  // song's own sound starts. Reported: a skip faded the old song out, and the level came back on
  // the state's word that the next song was there, while the element still played the old one's
  // last buffered moments (heard as a fragment of it, or a pop). The change is the element's own:
  // another element, another source, or its clock jumping (a new song starts from 0, or where it
  // was asked to). Until then the fade-out that came with it goes on; from then the element stays
  // at SILENT (the page sets its own level on a new song) until its clock moves. Never left down:
  // the level comes back after {wait} ms whatever happened. Any other fade or volume set ends it.
  const riseOnSwitch = (player, ms, wait) => {
    const first = player._player;
    const src = first.currentSrc;
    const now = () => (player._player instanceof HTMLMediaElement ? player._player : first);
    let el = first, switched = false, prev = first.currentTime, at = performance.now();
    const tick = () => {
      const cur = now();
      if (!switched) {
        const t = cur.currentTime, wall = performance.now();
        // What the clock should have done since the last look: anything else is a jump (a late
        // timer on a busy page is not one).
        const expected = cur.paused ? 0 : (wall - at) / 1000 * (cur.playbackRate || 1);
        if (cur !== first || cur.currentSrc !== src || Math.abs(t - prev - expected) > 1) {
          switched = true;
          el = cur;
          // The fade-out is over: its last steps would land on the new song.
          fadeTimers.forEach(clearTimeout); fadeTimers = [];
          el.volume = SILENT;
          prev = el.currentTime;
        } else { prev = t; at = wall; }
        riseTimers.push(setTimeout(tick, 16));
        return;
      }
      if (cur !== el) { el = cur; prev = el.currentTime; }
      if (el.volume > SILENT) el.volume = SILENT;
      const t = el.currentTime, step = t - prev;
      prev = t;
      if (!el.paused && step > 0.005 && step < 0.5) { ramp(el, player, 0, 1, Math.max(1, ms))(); return; }
      riseTimers.push(setTimeout(tick, 16));
    };
    riseTimers.push(setTimeout(() => { cancelFade(); now().volume = ownLevel(player); }, Math.max(500, Number(wait) || 3000)));
    tick();
  };
  // Where the element stands on that curve now.
  const curveAt = (el, player) => {
    const own = ownLevel(player);
    return own > 0 ? Math.min(1, Math.cbrt(Math.max(0, el.volume) / own)) : 1;
  };

  // "Is this page Spotify Connect's active device": a full device-list round trip, so it is
  // read at most once every ACTIVE_CACHE_MS and reused (a transfer happens minutes apart).
  const ACTIVE_CACHE_MS = 6000;
  let activeValue = null, activeAt = 0;
  const activeDevice = async () => {
    const now = Date.now();
    if (activeValue !== null && now - activeAt < ACTIVE_CACHE_MS) return activeValue;
    try {
      const me = (await need().resolve('ConnectDevicesAPI').getDevices()).find(d => d.isLocal);
      activeValue = !!(me && me.isActive);
      activeAt = now;
    } catch (e) { /* unknown: the caller keeps the last answer */ }
    return activeValue;
  };

  // The element volume the page player itself sets for its volume (cubic curve or not).
  const ownLevel = player => {
    const v = typeof player._playerVolume === 'number' ? player._playerVolume : 1;
    return player._cubicVolume ? v * v * v : v;
  };

  // --- commands -------------------------------------------------------------

  const need = () => {
    if (!services) throw new Error('not ready');
    return services;
  };

  // --- ads ------------------------------------------------------------------
  // The page's own ad managers drive the audio ads and their mute. SpotX patches the bundled
  // web player to force `adsEnabled` off; the very same managers are reachable at runtime through
  // the service registry, so they are turned off here instead of patching a bundle we do not ship.
  //
  // `enable`/`disable` sit on the prototype and `enabled` is a plain field, so the instance is
  // shadowed here: after this, the page cannot arm its own managers again for the life of the page.
  // This does not stop audio ads: Spotify's server puts those in the queue itself (measured on
  // 20/10, desktop, free account: one ad after ~17 min with this latch on, as without it). Those
  // are left out of the queue by the host (AdBlock.withoutAds). The "Test ad" once taken for an
  // ad that came through was tools/playback_test.py's own fake.
  let adsLatched = false;
  const adsOff = () => {
    if (!services) return 'not ready';
    if (adsLatched) return 'already off';
    let audio = null;
    try { audio = services.resolve('AdManagers').audio; } catch (e) { return 'unresolved'; }
    if (!audio) return 'no ad manager';
    const done = [];
    for (const [label, mgr] of [['audio', audio], ['inStream', audio.inStreamApi]]) {
      if (!mgr) continue;
      try {
        if (typeof mgr.disable === 'function') { mgr.disable(); done.push(label); }
        // The page's own `enable` is replaced on the instance: it can only ever answer "not on".
        if (typeof mgr.enable === 'function') {
          Object.defineProperty(mgr, 'enable', { value: () => false, writable: true, configurable: true });
        }
        if ('enabled' in mgr) mgr.enabled = false;
      } catch (e) { /* already off */ }
    }
    return done.length ? (adsLatched = true, done.join(',')) : 'nothing to disable';
  };

  /**
   * Asks the in-stream ad manager to end its break. Kept for diagnosis only: on a real ad whose
   * audio was refused it did nothing (20/10), the server holds the ad ("ad_disallow").
   */
  const adsSkip = () => {
    if (!services) return 'not ready';
    try {
      const api = services.resolve('AdManagers').audio.inStreamApi;
      if (api && typeof api.skipToNext === 'function') { api.skipToNext(); return 'inStream.skipToNext'; }
    } catch (e) { return 'failed: ' + e; }
    return 'no lever';
  };

  const commands = {
    'bridge.version': () => VERSION,
    'ads.off': () => adsOff(),
    'ads.skip': () => adsSkip(),

    'player.state': () => mapState(need().player.getState()),
    'player.queue': () => mapQueue(need().player.getQueue()),
    // {contextUri, trackUri?, trackUid?, index?, positionMs?}
    'player.play': a => {
      const skipTo = {};
      if (a.trackUri) skipTo.uri = a.trackUri;
      if (a.trackUid) skipTo.uid = a.trackUid;
      if (Number.isInteger(a.index)) skipTo.index = a.index;
      const extra = {};
      if (Object.keys(skipTo).length) extra.skipTo = skipTo;
      if (a.positionMs) extra.seekTo = a.positionMs;
      // A given order (sorted playlist): the context plays as one page of those songs.
      const context = Array.isArray(a.order) && a.order.length
        ? { uri: a.contextUri, url: 'context://' + a.contextUri + '?order=' + Date.now(), pages: [{ items: a.order.map(uri => ({ uri })) }] }
        : { uri: a.contextUri };
      return need().player.play(context, {}, extra);
    },
    'player.pause': () => need().player.pause(),
    'player.resume': () => need().player.resume(),
    'player.seek': a => need().player.seekTo(a.positionMs),
    'player.next': () => need().player.skipToNext(),
    'player.previous': () => need().player.skipToPrevious(),
    'player.setShuffle': a => need().player.setShuffle(!!a.on),
    'player.setRepeat': a => need().player.setRepeat(a.mode),
    'player.setSpeed': a => need().player.setSpeed(a.speed),
    // Streaming quality, as the desktop app's setting: "auto", "low", "normal", "high",
    // "very_high". The page's own player (PlayerSDK.harmony) picks the files with it.
    // What the page plays now: bitrate, codec and the chosen quality level.
    'player.audioInfo': async () => {
      const sdk = need().resolve('PlayerSDK');
      const streamer = sdk && sdk.harmony && sdk.harmony._streamer;
      const player = streamer && streamer._playerPromise ? await streamer._playerPromise : null;
      const content = player && player._currentContent;
      return {
        bitrate: content && content._bitrate || 0,
        mime: content && content._mimeType || '',
        level: player && player._preferredMediaPlaybackQuality && player._preferredMediaPlaybackQuality.audio || '',
      };
    },
    'player.setAudioQuality': async a => {
      const sdk = need().resolve('PlayerSDK');
      const harmony = sdk && sdk.harmony;
      // The page's own streamer first: the public entry refuses while Spotify Connect
      // calls another device the active one ("remote player"), yet this page plays.
      const target = [harmony && harmony._streamer, harmony]
        .find(t => t && typeof t.setMediaPlaybackQuality === 'function');
      if (!target) throw new Error('no audio quality setting');
      return !!(await target.setMediaPlaybackQuality({ audio: a.level }));
    },
    // Loads the song after this one now (its file, first segment and license), as the page
    // itself does only 10 s before the end: a skip then starts that much sooner. -> the result.
    'player.preloadNext': async () => {
      const sdk = need().resolve('PlayerSDK');
      const lp = sdk && sdk.harmony && sdk.harmony._streamer && sdk.harmony._streamer._listPlayer;
      if (!lp || typeof lp._preloadUpcomingTrack !== 'function') return null;
      return String(await lp._preloadUpcomingTrack());
    },
    'queue.add': a => need().player.addToQueue(a.uris.map(uri => ({ uri }))),
    'queue.remove': a => need().player.removeFromQueue(a.items),
    'queue.clear': () => need().player.clearQueue(),
    // Jumps to a queued or upcoming item.
    'queue.skipTo': a => need().player.skipToNext({ uri: a.uri, uid: a.uid }),
    // {uris, before: {uri, uid}} : "play next" puts the tracks before the first queued item.
    'queue.insert': a => need().player.insertIntoQueue(a.uris.map(uri => ({ uri })), { before: a.before }),
    // {items: [{uri, uid}], before | after: {uri, uid}}
    'queue.reorder': a => need().player.reorderQueue(a.items, a.after ? { after: a.after } : { before: a.before }),

    // Mutes the page's own media elements (ads), whatever Spotify Connect says about the
    // volume. Elements are known from the host's document-start hook (window.__mspMedia),
    // or found in the document. {on} -> how many elements were set.
    //
    // Not `muted`, and never volume 0: Chromium pauses a hidden page's media that starts (a
    // new song, a restart, a seek) muted or at volume 0, and the song after the ad stayed
    // paused. A volume of SILENT is not heard and keeps playback going.
    'media.mute': async a => {
      cancelFade();
      window.__mspMuted = !!a.on;
      const player = await localPlayer().catch(() => null);
      const els = new Set([...(window.__mspMedia || []), ...document.querySelectorAll('audio, video'), ...await playerMedia()]);
      // The end of an ad's mute leaves a hushed page unheard ('media.hush'): seen, the page heard
      // over the local player it mirrors after an ad, on an element the hush had not reached yet.
      for (const el of els) {
        el.muted = false;
        el.volume = a.on || window.__mspHushed ? SILENT : (player && el === player._player ? ownLevel(player) : 1);
      }
      return els.size;
    },

    // "Clip sound": the music video's own soundtrack is heard instead of the song. The song keeps
    // playing here (still Spotify's play of it, counted as one), only unheard, at the same
    // unheard volume as above. Held against the page's own volume sets (a new song, a fade, a
    // volume change) by a listener on each element; an ad's mute keeps the upper hand. {on} -> elements.
    'media.hush': async a => {
      window.__mspHushed = !!a.on;
      const player = await localPlayer().catch(() => null);
      const els = new Set([...(window.__mspMedia || []), ...document.querySelectorAll('audio, video'), ...await playerMedia()]);
      for (const el of els) {
        if (!el.__mspHush) {
          el.__mspHush = () => { if (window.__mspHushed && !window.__mspMuted && el.volume > SILENT) el.volume = SILENT; };
          el.addEventListener('volumechange', el.__mspHush);
          el.addEventListener('playing', el.__mspHush);
        }
        if (a.on) { cancelFade(); el.muted = false; el.volume = SILENT; }
        else if (!window.__mspMuted) el.volume = player && el === player._player ? ownLevel(player) : 1;
      }
      return els.size;
    },

    // The page player's own media element now: what is really heard, whatever the state says.
    // active: this page is Spotify Connect's active device (else the state is another device's).
    // level: the element's volume (0..1), which local fades move; own: the level the page
    // player itself wants there. playerVolume: what the page player itself is set to, read
    // here too so the host's periodic volume check costs no second call.
    // -> {paused, muted, volume, level, own, time, readyState, networkState, error, keys, ended, playerVolume, active} | null (no element yet)
    'media.state': async () => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      let playerVolume = null;
      try { playerVolume = await need().playback.getVolume(); } catch (e) { /* unknown */ }
      return { paused: el.paused, muted: el.muted, volume: el.volume, level: el.volume, own: ownLevel(player), time: el.currentTime, readyState: el.readyState, networkState: el.networkState, error: el.error ? el.error.code + ':' + String(el.error.message || '').slice(0, 80) : null, keys: el.mediaKeys ? 1 : 0, ended: el.ended, playerVolume, active: await activeDevice() };
    },
    // The media element's volume, on this phone only: no Spotify Connect command (refused on
    // some accounts, slow on a busy network) and nothing reported to Spotify, as the player's
    // own setVolume would. Fades go through it. {level} | {restore: true} (the player's own
    // volume back) -> the level set.
    'media.volume': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      cancelFade();
      // Never 0: see 'media.mute'.
      el.volume = a.restore ? ownLevel(player) : Math.min(1, Math.max(SILENT, Number(a.level) || 0));
      return el.volume;
    },

    // A whole fade in one call: the page applies the steps itself, so the host sends the play
    // command while the sound goes down instead of waiting for every step (a round trip each).
    // {steps: [level, ...], ms, restore: true?, rise: {ms, wait}?} -> true. Levels are never 0: see
    // 'media.mute'. rise: the sound is then held through the change of song (riseOnSwitch).
    'media.fade': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      const steps = (Array.isArray(a.steps) ? a.steps : []).map(Number);
      const per = Math.max(1, Number(a.ms) || 12);
      const set = v => { el.volume = Math.min(1, Math.max(SILENT, v)); };
      // A ramp replaces the one before it (a crossfade's up-ramp must not be pulled back down
      // by steps still pending from its down-ramp).
      cancelFade();
      // Each tick sets the step the clock is at, not the next one in line: a late timer (a busy
      // or throttled page) lands on the right level instead of stretching the ramp.
      const t0 = performance.now();
      const tick = () => {
        const i = Math.floor((performance.now() - t0) / per);
        if (i >= steps.length) { if (a.restore) el.volume = ownLevel(player); else if (steps.length) set(steps[steps.length - 1]); return; }
        set(steps[i]);
        fadeTimers.push(setTimeout(tick, per - ((performance.now() - t0) % per)));
      };
      tick();
      if (a.rise) riseOnSwitch(player, Number(a.rise.ms) || 80, Number(a.rise.wait));
      return true;
    },

    // The sound going down (a pause) or up, as one smooth ramp. {to: 0..1, ms} -> true
    'media.ramp': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      ramp(el, player, curveAt(el, player), Math.min(1, Math.max(0, Number(a.to) || 0)), Math.max(1, Number(a.ms) || 150))();
      return true;
    },

    // The sound rising back when a paused song plays again, as the official app does: silent
    // now, then up along the curve once the element's clock really moves, the moment sound comes
    // out. Not on the state saying "playing", nor on the element's own 'playing' event: after a
    // long pause both came 0.5 to 0.9 s before the first sound (measured), and the whole ramp
    // was spent on silence. Not playing within {wait} ms: the level comes back as it was.
    // {ms, wait, keep?, switch?} -> true. switch: held through the change of song first
    // (riseOnSwitch), the fade under way going on meanwhile.
    'media.rise': async a => {
      const player = await localPlayer();
      const el = player && player._player;
      if (!(el instanceof HTMLMediaElement)) return null;
      if (a.switch) { riseOnSwitch(player, Number(a.ms) || 80, Number(a.wait)); return true; }
      cancelFade();
      // keep: from where the element is now (the end of a crossfade), not from silence.
      const from = a.keep ? curveAt(el, player) : 0;
      if (!a.keep) el.volume = SILENT;
      const rise = ramp(el, player, from, 1, Math.max(1, Number(a.ms) || 500));
      // Moving: ahead of the last look by a little (a new song's clock starting again from 0 is
      // a jump back, not sound; a seek is a jump, not sound either).
      let prev = el.currentTime;
      const waitSound = () => {
        const t = el.currentTime, step = t - prev;
        prev = t;
        if (!el.paused && step > 0.005 && step < 0.5) rise();
        else fadeTimers.push(setTimeout(waitSound, 16));
      };
      fadeTimers.push(setTimeout(() => { cancelFade(); el.volume = ownLevel(player); }, Math.max(500, Number(a.wait) || 3000)));
      waitSound();
      return true;
    },

    'volume.get': () => need().playback.getVolume(),
    'volume.set': a => need().playback.setVolume(a.level),

    'library.contains': a => need().library.contains(...a.uris),
    'library.add': a => need().library.add({ uris: a.uris }),
    'library.remove': a => need().library.remove({ uris: a.uris }),

    // Generic access to the page's services (PlaylistAPI, RootlistAPI, ShowAPI, ConnectAPI...).
    // Typed wrappers live on the host side; this keeps the bridge small.
    'service.list': () => {
      const out = {};
      for (const name of Object.keys(need().byName)) {
        let v;
        try { v = need().resolve(name); } catch (e) { continue; }
        if (v && typeof v === 'object') out[name] = methodsOf(v);
      }
      return out;
    },
    // {service, method, args[]}
    'service.call': async a => {
      const svc = need().resolve(a.service);
      if (!svc || typeof svc[a.method] !== 'function' || a.method.startsWith('_')) {
        throw new Error(`no method ${a.service}.${a.method}`);
      }
      return toJson(await svc[a.method](...(a.args || [])));
    },

    // A playlist's cover, as the page's "Edit details" sets it: the JPEG goes to the image
    // upload service, then its token to the playlist. No jpeg (null): the cover is removed.
    // {uri, jpeg: base64 | null}
    'playlist.setCover': async a => {
      const playlists = need().resolve('PlaylistAPI');
      let token = null;
      if (a.jpeg) {
        const bytes = Uint8Array.from(atob(a.jpeg), c => c.charCodeAt(0));
        token = await playlists.uploadImage(new Blob([bytes], { type: 'image/jpeg' }));
      }
      await playlists.updateDetails(a.uri, { imageUploadToken: token });
      return true;
    },

    // Any persisted GraphQL operation of the Web Player, sent by the page's own loader
    // (its auth, client token and retries). {name, hash, variables}
    'gql.query': async a => {
      const def = { name: a.name, operation: a.operation || 'query', sha256Hash: a.hash, value: null };
      return need().graphql(def, a.variables || {});
    },

    // Web token for the HTTP API, refreshed by the page itself.
    'token.get': async () => {
      const t = await need().token.getToken();
      // clientId and appVersion let the host mint its own client token for direct HTTP calls.
      return t && {
        accessToken: t.accessToken,
        expiresAtMs: t.accessTokenExpirationTimestampMs,
        clientId: t.clientId,
        appVersion: need().platform.version,
        isAnonymous: !!t.isAnonymous,
      };
    },
  };

  // Inside one of the app's own commands (see traceSkips).
  let calling = 0;
  const call = async (id, method, args) => {
    try {
      const fn = commands[method];
      if (!fn) throw new Error(`unknown method ${method}`);
      let pending;
      calling++;
      try { pending = fn(args || {}); } finally { calling--; }
      const value = await pending;
      const out = value === undefined ? null : value;
      post({ type: 'result', id, ok: true, value: out });
      return out;
    } catch (e) {
      post({ type: 'result', id, ok: false, error: String(e && e.message || e) });
      throw e;
    }
  };

  // --- events ---------------------------------------------------------------

  let lastState = '';
  const pushState = () => {
    if (!services) return;
    const state = mapState(services.player.getState());
    // An ad is on: the page's ad managers are turned off again (it re-arms them per break).
    if (state && state.track && state.track.kind === 'ad') { try { adsOff(); } catch (e) { /* no service */ } }
    const json = JSON.stringify(state);
    if (json === lastState) return;
    lastState = json;
    post({ type: 'state', state });
  };

  const pushQueue = () => {
    if (services) post({ type: 'queue', queue: mapQueue(services.player.getQueue()) });
  };

  const attach = () => {
    const events = services.player.getEvents();
    events.addListener('update', pushState);
    events.addListener('queue_update', pushQueue);
    // Ads off from the first moment: a break queued before the bridge attached never starts.
    try { adsOff(); } catch (e) { /* no service yet */ }
    traceSkips();
  };

  // Diagnosis (read-only): who asks the page for another song. A tester's song changed with no
  // command from the app (the clip going full screen); the caller's stack, a key or a media
  // session action says where it came from.
  const stackTop = () => String(new Error().stack || '').split('\n').slice(2, 8).map(l => l.trim()).join(' < ').slice(0, 400);
  const traceSkips = () => {
    try {
      const p = services.player;
      for (const name of ['skipToNext', 'skipToPrevious', 'seekTo']) {
        const own = p[name];
        if (typeof own !== 'function' || own.__mspTraced) continue;
        const traced = function (...args) {
          if (!calling) post({ type: 'skip', by: name, stack: stackTop() });
          return own.apply(this, args);
        };
        traced.__mspTraced = true;
        p[name] = traced;
      }
    } catch (e) { /* left as it is */ }
    try {
      const ms = navigator.mediaSession;
      if (ms && !ms.__mspTraced) {
        const set = ms.setActionHandler.bind(ms);
        ms.setActionHandler = (action, handler) => set(action, handler && ((...a) => {
          post({ type: 'skip', by: 'mediaSession.' + action });
          return handler(...a);
        }));
        ms.__mspTraced = true;
      }
    } catch (e) { /* no media session */ }
    window.addEventListener('keydown', e => {
      if (/Arrow|Media|Key[NPJL]/.test(e.code)) post({ type: 'skip', by: 'key ' + e.code + (e.ctrlKey ? '+ctrl' : '') + (e.shiftKey ? '+shift' : '') });
    }, true);
  };

  // --- boot -----------------------------------------------------------------

  // --- diagnostics (read-only) ----------------------------------------------
  // Playback requests with their status and body size, and uncaught page errors,
  // so the host log shows why a play command did nothing.
  const PLAYBACK = /player\/command|track-playback|connect-state\/v1\/devices|widevine-license|storage-resolve|audio-.*\.scdn\.co|apresolve/;
  try {
    new PerformanceObserver(list => {
      for (const e of list.getEntries()) {
        if (!PLAYBACK.test(e.name)) continue;
        post({ type: 'net', url: e.name.split('?')[0].slice(0, 160), status: e.responseStatus || 0, bytes: e.encodedBodySize || e.transferSize || 0, ms: Math.round(e.duration) });
      }
    }).observe({ type: 'resource', buffered: true });
  } catch (e) { /* no resource timing */ }
  window.addEventListener('error', e => post({ type: 'jserror', error: String(e.message || e.error || '').slice(0, 300) }));
  window.addEventListener('unhandledrejection', e => post({ type: 'jserror', error: String((e.reason && (e.reason.stack || e.reason.message)) || e.reason || '').slice(0, 300) }));

  // Widevine, which every Spotify stream is encrypted with: a WebView without a working one (an
  // outdated or stripped Android System WebView, a device without a licence) plays nothing, which
  // looks like "no sound", "refused" or "stuck at 0:00". Asked once per page, from the page's own
  // EME; an empty robustness is the weakest level every Widevine device has (L3).
  // Only on the host's request after a play failed ("drm.probe"), never at boot: asked while the
  // player sets up its own EME (2.0.2-beta.1), the page never registered its playback device.
  let drm = null;
  let drmAsked = false;
  const probeDrm = () => {
    if (drmAsked) return;
    drmAsked = true;
    const report = (ok, error) => { drm = { type: 'drm', ok, error: error || '' }; post(drm); };
    try {
      if (!navigator.requestMediaKeySystemAccess) return report(false, 'no EME in this WebView');
      navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
        initDataTypes: ['cenc'],
        audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }],
      }]).then(() => report(true), e => report(false, String((e && e.message) || e).slice(0, 200)));
    } catch (e) { report(false, String((e && e.message) || e).slice(0, 200)); }
  };
  commands['drm.probe'] = () => { probeDrm(); return drm; };

  const announce = () => {
    if (!services) return;
    post({ type: 'ready', version: VERSION });
    if (drm) post(drm);
    lastState = '';
    pushState();
  };

  window.Myspoti = { version: VERSION, call, announce, adsOff, adsSkip, methods: Object.keys(commands) };

  // The page's own UI is never seen. On the home page a song change redraws ~7000 nodes and
  // ~190 images, which held the main thread for ~1 s and delayed the next song's sound; the
  // settings page has ~260 nodes and one image. It must be a real page of the Web Player: a
  // reload (by the page, the WebView or a test) loads the current URL, and an unknown one is
  // served without the player.
  const IDLE_ROUTE = '/preferences';
  const park = () => {
    try {
      const history = services.platform.getHistory && services.platform.getHistory();
      if (history && typeof history.replace === 'function' && location.pathname !== IDLE_ROUTE) history.replace(IDLE_ROUTE);
    } catch (e) { /* the page keeps its route */ }
  };

  const started = Date.now();
  const boot = () => {
    try {
      services = resolveServices();
    } catch (e) {
      services = null;
    }
    if (services) {
      attach();
      park();
      post({ type: 'ready', version: VERSION });
      pushState();
      return;
    }
    if (Date.now() - started > READY_TIMEOUT_MS) {
      post({ type: 'error', error: 'player services not found' });
      return;
    }
    setTimeout(boot, 250);
  };
  boot();
})();
