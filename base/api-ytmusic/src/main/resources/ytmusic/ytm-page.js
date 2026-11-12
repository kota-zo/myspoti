/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Myspoti page script for music.youtube.com (the official web app), injected by the host after each
 * page load (Android WebView or desktop Chrome over CDP). It reads and commands the page's own player
 * element (#movie_player), whose methods are those of Google's YouTube IFrame Player API
 * (playVideo, pauseVideo, seekTo, getCurrentTime, getDuration, getPlayerState, getVideoData).
 *
 * - Only the song the app asked for plays: another one the page starts by itself (its autoplay, a
 *   suggestion) is paused at once. The asked song is kept in the tab's sessionStorage, so it holds
 *   across the page load that plays it.
 * - The page's media is held unheard (volume 0.001), never "muted", and the player's own volume
 *   setting is left alone.
 * - Ads are never touched: an ad is reported (the "ad-showing" class of the player) and left to play.
 * - The page plays unseen: its video is asked at the lowest quality (the audio is the same), and a
 *   song is opened inside the web app (its own navigation, no page load) when it can be.
 * - The state is posted on the media elements' events, plus every 5 s (it was read every 0.5 s).
 * - Nothing is sent to YouTube by this script: the page plays, the page reports what it plays.
 *
 * Host -> page: window.MyspotiPage.call(id, method, args)
 * Page -> host: one JSON string per message, to window.MyspotiHost.postMessage (Android) or
 *   window.__mspHostPost (CDP binding): {type: "ready", page, signedIn, clientVersion, hl, gl}
 *   | {type: "state", state} | {type: "result", id, ok, value | error}
 */
(() => {
  if (window.MyspotiPage && window.MyspotiPage.version) {
    if (window.__mspReannounce) { window.__mspReannounce = false; window.MyspotiPage.announce(); }
    return;
  }
  const gen = window.__mspPageGen;
  const SILENT = 0.001;
  const OPEN_CHECK_MS = 4000;
  const WANT_KEY = 'mspWant';

  const post = (m) => {
    const s = JSON.stringify(m);
    try {
      if (window.MyspotiHost && typeof window.MyspotiHost.postMessage === 'function') window.MyspotiHost.postMessage(s);
      else if (typeof window.__mspHostPost === 'function') window.__mspHostPost(s);
    } catch (e) { /* the host went away */ }
  };

  const stored = () => { try { return window.sessionStorage.getItem(WANT_KEY); } catch (e) { return null; } };
  const store = (v) => {
    try { if (v) window.sessionStorage.setItem(WANT_KEY, v); else window.sessionStorage.removeItem(WANT_KEY); } catch (e) { /* storage refused */ }
  };
  let want = stored();

  const player = () => {
    const p = document.querySelector('#movie_player');
    return p && typeof p.getPlayerState === 'function' ? p : null;
  };
  const cfg = (k) => {
    try { return window.ytcfg && typeof window.ytcfg.get === 'function' ? window.ytcfg.get(k) : undefined; } catch (e) { return undefined; }
  };

  const hold = (el) => {
    if (el.__mspHeld) return;
    el.__mspHeld = true;
    const quiet = () => { if (el.volume > SILENT) el.volume = SILENT; };
    el.addEventListener('volumechange', quiet);
    el.addEventListener('playing', quiet);
    el.addEventListener('loadedmetadata', quiet);
    quiet();
  };
  const unheard = () => { document.querySelectorAll('video, audio').forEach(hold); };

  // The smallest video the player has: nobody sees it, and the audio does not change with it.
  let lowFor = null;
  const lowest = () => {
    const p = player();
    if (!p) return;
    const id = (typeof p.getVideoData === 'function' && (p.getVideoData() || {}).video_id) || null;
    if (!id || id === lowFor) return;
    lowFor = id;
    try {
      if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange('tiny', 'tiny');
      if (typeof p.setPlaybackQuality === 'function') p.setPlaybackQuality('tiny');
    } catch (e) { /* an older player */ }
  };

  // getPlayerState: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued.
  const state = () => {
    const p = player();
    if (!p) return null;
    const d = (typeof p.getVideoData === 'function' && p.getVideoData()) || {};
    const st = p.getPlayerState();
    return {
      trackId: d.video_id || null,
      paused: !(st === 1 || st === 3),
      buffering: st === 3,
      positionMs: Math.round((Number(p.getCurrentTime()) || 0) * 1000),
      durationMs: Math.round((Number(p.getDuration()) || 0) * 1000),
      ad: !!(p.classList && p.classList.contains('ad-showing')),
      at: Date.now(),
    };
  };

  // True when it paused the page (its state is read again then).
  const guard = (s) => {
    if (!s || s.ad || s.paused) return false;
    if (want && s.trackId === want) return false;
    const p = player();
    if (p) p.pauseVideo();
    return !!p;
  };

  let last = '';
  let lastAt = 0;
  const tick = () => {
    unheard();
    lowest();
    let s = state();
    if (guard(s)) s = state();
    if (!s) return;
    const key = [s.trackId, s.paused, s.buffering, s.ad].join('|');
    const now = Date.now();
    if (key !== last || now - lastAt > 5000) { last = key; lastAt = now; post({ type: 'state', state: s }); }
  };

  const needPlayer = () => { const p = player(); if (!p) throw new Error('no player'); return p; };
  const methods = {
    state: () => state(),
    // Opens the song in the web app (its own navigation: its queue and history hold it, no page
    // load); 'load' when it falls back to loading its watch page, at once or when the app's
    // navigation did not bring the song within OPEN_CHECK_MS.
    open: (a) => {
      want = String(a.videoId);
      store(want);
      const asked = want;
      const load = () => window.location.assign('/watch?v=' + encodeURIComponent(asked));
      const app = document.querySelector('ytmusic-app');
      if (!app) { setTimeout(load, 0); return 'load'; }
      try {
        app.dispatchEvent(new CustomEvent('yt-navigate', {
          bubbles: true, composed: true,
          detail: { endpoint: { watchEndpoint: { videoId: asked }, commandMetadata: { webCommandMetadata: { url: '/watch?v=' + asked } } } },
        }));
      } catch (e) { setTimeout(load, 0); return 'load'; }
      setTimeout(() => {
        const s = state();
        if (want === asked && (!s || s.trackId !== asked)) load();
      }, OPEN_CHECK_MS);
      return 'inapp';
    },
    want: (a) => { want = a.videoId ? String(a.videoId) : null; store(want); tick(); return want; },
    pause: () => { needPlayer().pauseVideo(); tick(); return true; },
    resume: () => { const p = needPlayer(); unheard(); p.playVideo(); return true; },
    seek: (a) => { needPlayer().seekTo(Math.max(0, Number(a.ms) || 0) / 1000, true); return true; },
  };

  const announce = () => post({
    type: 'ready', page: gen, signedIn: cfg('LOGGED_IN') === true,
    clientVersion: cfg('INNERTUBE_CLIENT_VERSION') || null, hl: cfg('HL') || null, gl: cfg('GL') || null,
  });

  window.MyspotiPage = {
    version: 1,
    announce,
    call: (id, method, args) => Promise.resolve()
      .then(() => {
        const m = methods[method];
        if (!m) throw new Error('unknown method ' + method);
        return m(args || {});
      })
      .then(
        (value) => post({ type: 'result', id, ok: true, value: value === undefined ? null : value }),
        (e) => post({ type: 'result', id, ok: false, error: String((e && e.message) || e) }),
      ),
  };

  // Ready once the app's configuration is there (the player itself may come later, at the first song).
  const wait = setInterval(() => { if (cfg('INNERTUBE_CLIENT_VERSION') !== undefined || player()) { clearInterval(wait); announce(); tick(); } }, 250);
  // Media events (captured: they do not bubble) post the state as it changes; the slow tick is
  // the net for what no event says (the player's own state, a song the page started by itself).
  ['play', 'playing', 'pause', 'waiting', 'seeked', 'ended', 'emptied', 'loadedmetadata'].forEach((t) =>
    document.addEventListener(t, (e) => {
      if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) hold(e.target);
      tick();
    }, true));
  setInterval(tick, 5000);
})();
