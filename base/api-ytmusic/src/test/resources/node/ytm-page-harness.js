// Runs ytm-page.js (path in argv[2]) in a stand-in of music.youtube.com and prints what it did as JSON.
// The stand-in's player has the IFrame Player API methods the script uses; nothing real is loaded.
// argv[3] 'inapp': the web app's element is there and opens a song by its own navigation.
'use strict';
const fs = require('fs');
const vm = require('vm');

const posted = [];
const assigned = [];
const storage = new Map();
const media = { volume: 1, listeners: {}, addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); } };
const player = {
  classList: { list: new Set(), contains(c) { return this.list.has(c); } },
  st: 1, video: 'vidOTHER001', time: 12.5, dur: 200,
  getPlayerState() { return this.st; },
  getVideoData() { return { video_id: this.video }; },
  getCurrentTime() { return this.time; },
  getDuration() { return this.dur; },
  pauseVideo() { this.st = 2; posted.push({ type: 'harness', did: 'pause' }); },
  playVideo() { this.st = 1; posted.push({ type: 'harness', did: 'play' }); },
  seekTo(s, allow) { this.time = s; posted.push({ type: 'harness', did: 'seek', s, allow }); },
  setPlaybackQualityRange(lo, hi) { quality.push(lo + '-' + hi + ' ' + this.video); },
};
const quality = [];
const inApp = process.argv[3] === 'inapp';
const navigated = [];
// The web app's element: a 'yt-navigate' event to a watch endpoint plays that song, no page load.
const app = {
  dispatchEvent(e) {
    const id = e.detail && e.detail.endpoint && e.detail.endpoint.watchEndpoint && e.detail.endpoint.watchEndpoint.videoId;
    navigated.push(e.type + ' ' + id);
    if (e.type === 'yt-navigate' && id) { player.video = id; player.st = 1; }
    return true;
  },
};
const listened = [];
const timers = [];
const window = {
  __mspPageGen: 3,
  __mspHostPost: (s) => posted.push(JSON.parse(s)),
  ytcfg: { get: (k) => ({ LOGGED_IN: true, INNERTUBE_CLIENT_VERSION: '1.2.3', HL: 'de', GL: 'CH' })[k] },
  sessionStorage: { getItem: (k) => (storage.has(k) ? storage.get(k) : null), setItem: (k, v) => storage.set(k, String(v)), removeItem: (k) => storage.delete(k) },
  location: { assign: (u) => assigned.push(u) },
};
const document = {
  querySelector: (s) => (s === '#movie_player' ? player : s === 'ytmusic-app' && inApp ? app : null),
  querySelectorAll: (s) => (s === 'video, audio' ? [media] : []),
  addEventListener: (t, f, capture) => listened.push(t + (capture ? ' capture' : '')),
};
class CustomEvent { constructor(type, init) { this.type = type; this.detail = init && init.detail; } }
const context = {
  window, document, JSON, Math, Number, String, Promise, Date,
  setInterval: (f) => { timers.push(f); return timers.length; },
  clearInterval: (id) => { timers[id - 1] = null; },
  setTimeout: (f) => { f(); return 0; },
  encodeURIComponent, CustomEvent,
};
vm.createContext(context);
// The script reads window.* globals both ways: as window.x and as bare names.
Object.assign(context, { sessionStorage: window.sessionStorage, location: window.location });
const src = fs.readFileSync(process.argv[2], 'utf8');
const run = () => timers.forEach((f) => f && f());

(async () => {
  if (inApp) {
    vm.runInContext(src, context);
    run();
    const opened = await context.window.MyspotiPage.call(1, 'open', { videoId: 'vidBBBBBBB2' });
    run();
    console.log(JSON.stringify({ posted, assigned, navigated, quality, listened }));
    return;
  }
  vm.runInContext(src, context);
  run(); // ready, first state; the unasked song playing is paused
  const page = context.window.MyspotiPage;
  await page.call(1, 'open', { videoId: 'vidAAAAAAA1' });
  // The new page: the asked song is kept in sessionStorage across the load.
  player.video = 'vidAAAAAAA1'; player.st = 1;
  await page.call(2, 'seek', { ms: 42000 });
  await page.call(3, 'state', {});
  run();
  player.classList.list.add('ad-showing'); player.video = 'vidADVERT01'; player.st = 1;
  run(); // an ad is left to play
  await page.call(4, 'nope', {});
  await page.call(5, 'want', { videoId: null });
  player.classList.list.clear(); player.video = 'vidAAAAAAA1'; player.st = 1;
  run(); // nothing asked any more: paused
  // Injected again: no second script, a re-announce only when asked.
  context.window.__mspReannounce = true;
  vm.runInContext(src, context);
  console.log(JSON.stringify({ posted, assigned, volume: media.volume, stored: [...storage.entries()] }));
})();
