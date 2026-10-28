// Runs before every extension. Gives it `myspoti`, the only door to the app, and closes the
// ones a reviewed script could use to change after review (code built from strings).
// The checks that matter (hosts, rights, sizes) are made by the app, in Kotlin: this file only
// shapes the API.
(function (host, global) {
  'use strict';
  var hooks = {};
  var listeners = {};
  var parse = function (text) { return text == null ? undefined : JSON.parse(text); };

  function response(r) {
    r.ok = r.status >= 200 && r.status < 300;
    r.json = function () { return JSON.parse(r.body); };
    return r;
  }

  // A request as the app reads it: a body that is not text is sent as JSON.
  function shape(r) {
    if (r == null || typeof r.url !== 'string') throw new TypeError('http.request({ url, method, headers, body })');
    var body = r.body;
    var headers = r.headers || {};
    if (body != null && typeof body !== 'string') {
      body = JSON.stringify(body);
      if (!Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; })) {
        headers = Object.assign({ 'Content-Type': 'application/json' }, headers);
      }
    }
    return { url: r.url, method: r.method || 'GET', headers: headers, body: body == null ? undefined : body, timeout: r.timeout };
  }

  function request(r) { return response(parse(host.http(JSON.stringify(shape(r))))); }

  // Up to 4 requests sent together; the answers in the same order, { error } for one that failed.
  function all(list) {
    if (!Array.isArray(list)) throw new TypeError('http.all([{ url, method, headers, body }, ...])');
    return parse(host.httpAll(JSON.stringify(list.map(shape)))).map(function (r) { return r.error != null ? r : response(r); });
  }

  // myspoti.spotify.<area>.<method>(...): built from the app's own table of methods, so nothing
  // here decides what is allowed. Arguments go as a JSON list; the answer is the method's result.
  var spotify = {};
  var methods = parse(host.spotifyMethods());
  Object.keys(methods).forEach(function (area) {
    var calls = {};
    methods[area].forEach(function (name) {
      calls[name] = function () {
        return parse(host.spotify(area, name, JSON.stringify(Array.prototype.slice.call(arguments))));
      };
    });
    spotify[area] = Object.freeze(calls);
  });
  // The player's media manifest of a song (file ids included): spotify.media(trackUri).
  if (spotify.player && spotify.player.media) spotify.media = spotify.player.media;

  var myspoti = {
    apiVersion: 1,
    extension: Object.freeze(parse(host.info())),

    /**
     * Answers the app at one of its fixed points, unlocked by an action of the manifest:
     * lyrics, audio-source, download-source, trackinfo, home, search, menu, player (blocks),
     * page, message (its web page), tap (a block with an action was tapped); and status (no action
     * needed): { live, detail? }, whether its source answers now.
     */
    provide: function (hook, fn) {
      if (typeof fn !== 'function') throw new TypeError('provide("' + hook + '", fn): fn must be a function');
      host.mayProvide(String(hook));
      hooks[hook] = fn;
    },

    /**
     * Listens to the app: on('track' | 'playback', fn) needs playback.events, on('auth', fn)
     * webview.auth, on('online', fn) network, on('setting', fn) settings.page ({ key } when one of
     * its settings changed, { key, pressed: true } for a button setting).
     */
    on: function (event, fn) {
      if (typeof fn !== 'function') throw new TypeError('on("' + event + '", fn): fn must be a function');
      host.mayListen(String(event));
      (listeners[event] = listeners[event] || []).push(fn);
    },

    http: Object.freeze({
      request: request,
      all: all,
      get: function (url, options) { return request(Object.assign({}, options, { url: url, method: 'GET' })); },
      post: function (url, body, options) { return request(Object.assign({}, options, { url: url, method: 'POST', body: body })); },
    }),

    /** Builds "a=1&b=two" from { a: 1, b: 'two' } (undefined and null are left out). */
    query: function (params) {
      return Object.keys(params || {}).filter(function (k) { return params[k] != null; })
        .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(String(params[k])); }).join('&');
    },

    storage: Object.freeze({
      get: function (key) { return parse(host.storageGet(String(key))); },
      set: function (key, value) { host.storageSet(String(key), JSON.stringify(value === undefined ? null : value)); },
      remove: function (key) { host.storageRemove(String(key)); },
    }),

    settings: Object.freeze({
      get: function (key) { return parse(host.setting(String(key))); },
    }),

    player: Object.freeze({
      current: function () { return parse(host.nowPlaying()); },
      play: function () { return host.control('play', -1); },
      pause: function () { return host.control('pause', -1); },
      next: function () { return host.control('next', -1); },
      previous: function () { return host.control('previous', -1); },
      seek: function (ms) { return host.control('seek', Number(ms)); },
    }),

    /**
     * The signed-in Spotify account: search, catalog, library, playlists, player, home, podcasts,
     * users, extras, devices, recents, blends (typed, like the app's own API) and raw (GraphQL,
     * page services, Spotify URLs). Each needs a spotify.* permission; removing things asks the user.
     */
    spotify: Object.freeze(spotify),

    notify: function (text) { host.notify(String(text)); },

    /**
     * What this app understands beyond the first API, for an extension to adapt to: "audio.manifest",
     * "audio.expires", "audio.quality", "audio.source", "audio.ahead", "http.all", "status", "raw.base64", "results" (source hooks may answer { outcome }), "player.localMute" only when the user granted playback.mirror, and "attest" only when it granted attest.
     */
    features: Object.freeze(['audio.manifest', 'audio.expires', 'audio.quality', 'audio.source', 'audio.ahead', 'http.all', 'status', 'raw.base64', 'results'].concat(parse(host.info()).mirror ? ['player.localMute'] : [], parse(host.info()).attest ? ['attest'] : [])),

    /**
     * A sign-in page (webview.auth): open({ url, callback, hidden }) shows url in a web view of its
     * own and returns an id; on('auth', fn) later gets { id, url } once the page reaches callback
     * (its query is the answer), or { id, error }. hidden: true runs the page out of sight first and
     * shows it only if it needs the user. The page's cookies stay in the app.
     */
    auth: Object.freeze({
      open: function (r) {
        if (r == null || typeof r.url !== 'string' || typeof r.callback !== 'string') throw new TypeError('auth.open({ url, callback })');
        return host.authOpen(r.url, r.callback, r.hidden === true);
      },
    }),

    /**
     * A signing key of its own in the phone's hardware keystore (attest), so its server can tell its
     * requests come from a genuine Myspoti install. No key exists before enroll(challenge), which
     * returns { publicKey, certChain } (base64; the Android key attestation chain, the challenge
     * inside). publicKey() is that key or null, sign(text) a base64 DER ECDSA-SHA256 signature of
     * the UTF-8 text (it throws before enroll), reset() deletes the key.
     */
    attest: Object.freeze({
      enroll: function (challenge) { return parse(host.attestEnroll(String(challenge))); },
      publicKey: function () { return parse(host.attestPublicKey()); },
      sign: function (text) { return host.attestSign(String(text)); },
      reset: function () { host.attestReset(); },
    }),

    /** Work that waits for the network (network): whenOnline() asks for one on('online') once it is there. */
    work: Object.freeze({
      whenOnline: function () { host.whenOnline(); },
    }),

    /** The blocks it drew: ui.refresh('home' | 'search' | 'menu' | 'player' | 'trackinfo') asks the app to ask again. */
    ui: Object.freeze({
      refresh: function (place) { host.refresh(String(place)); },
    }),

    /** Its own web page (webview.page): page.post(data) sends data to the page, if it is open. */
    page: Object.freeze({
      post: function (data) { host.pagePost(JSON.stringify(data === undefined ? null : data)); },
    }),

    log: function () {
      host.log(Array.prototype.map.call(arguments, function (a) {
        return typeof a === 'string' ? a : JSON.stringify(a);
      }).join(' '));
    },

    util: Object.freeze({
      base64Encode: function (text) { return host.util('base64Encode', String(text)); },
      base64Decode: function (text) { return host.util('base64Decode', String(text)); },
      md5: function (text) { return host.util('md5', String(text)); },
      sha1: function (text) { return host.util('sha1', String(text)); },
      sha256: function (text) { return host.util('sha256', String(text)); },
      hmacSha256: function (key, text) { return host.util('hmacSha256', String(key) + '\u0000' + String(text)); },
    }),
  };

  // The app calls in here, and only here: a hook's answer, or an event delivered.
  function dispatch(kind, name, args) {
    var input = parse(args);
    if (kind === 'hook') {
      var fn = hooks[name];
      if (!fn) return null;
      var out = fn(input);
      return out == null ? null : JSON.stringify(out);
    }
    (listeners[name] || []).forEach(function (fn) { fn(input); });
    return null;
  }

  var console = Object.freeze({ log: myspoti.log, info: myspoti.log, warn: myspoti.log, error: myspoti.log });
  var fixed = { writable: false, configurable: false, enumerable: false };
  Object.defineProperty(global, 'myspoti', Object.assign({ value: Object.freeze(myspoti) }, fixed));
  Object.defineProperty(global, 'console', Object.assign({ value: console }, fixed));
  Object.defineProperty(global, '__dispatch', Object.assign({ value: dispatch }, fixed));

  // Size guards: native string operations are not interrupted by the time limit, so a script
  // could ask for gigabytes in one call. The limits are far above what a lyrics, audio or page
  // extension needs; past them the call throws a RangeError, which the script can catch.
  var MAX_STRING = 4 * 1024 * 1024;   // result of repeat / padStart / padEnd / join
  var MAX_INPUT = 8 * 1024 * 1024;    // JSON.parse and regular expression input
  function guard(proto, name, check) {
    var original = proto[name];
    if (typeof original !== 'function') return;
    Object.defineProperty(proto, name, {
      value: function () { check.apply(this, arguments); return original.apply(this, arguments); },
      writable: true, configurable: true, enumerable: false,
    });
  }
  function tooBig(what) { throw new RangeError(what + ': too large'); }
  guard(String.prototype, 'repeat', function (count) {
    if (String(this).length * Math.max(0, Number(count) || 0) > MAX_STRING) tooBig('String.prototype.repeat');
  });
  ['padStart', 'padEnd'].forEach(function (name) {
    guard(String.prototype, name, function (length) {
      if (Number(length) > MAX_STRING) tooBig('String.prototype.' + name);
    });
  });
  guard(Array.prototype, 'join', function (sep) {
    var s = sep === undefined ? 1 : String(sep).length;
    if (this.length * s > MAX_STRING) tooBig('Array.prototype.join');
  });
  var jsonParse = JSON.parse;
  Object.defineProperty(JSON, 'parse', {
    value: function (text, reviver) {
      if (typeof text === 'string' && text.length > MAX_INPUT) tooBig('JSON.parse');
      return jsonParse.call(JSON, text, reviver);
    },
    writable: true, configurable: true, enumerable: false,
  });
  // A regular expression runs natively and cannot be interrupted: at least the input is capped
  // (a catastrophic pattern on a short input is still the script's own time to lose, see docs).
  ['exec', 'test'].forEach(function (name) {
    guard(RegExp.prototype, name, function (input) {
      if (String(input).length > MAX_INPUT / 4) tooBig('RegExp.prototype.' + name);
    });
  });
  ['match', 'matchAll', 'replace', 'replaceAll', 'search', 'split'].forEach(function (name) {
    guard(String.prototype, name, function () {
      if (String(this).length > MAX_INPUT / 4) tooBig('String.prototype.' + name);
    });
  });

  // No code built from strings: what was reviewed is what runs.
  // Function keeps its prototype, so `instanceof Function` and Function.prototype.call still work.
  var refused = function Function() { throw new Error('code from strings is not allowed in extensions'); };
  refused.prototype = Function.prototype;
  Object.defineProperty(global, 'eval', Object.assign({ value: refused }, fixed));
  Object.defineProperty(Function.prototype, 'constructor', Object.assign({ value: refused }, fixed));
  Object.defineProperty(global, 'Function', Object.assign({ value: refused }, fixed));
  try {
    var generator = Object.getPrototypeOf(function* () {});
    Object.defineProperty(generator, 'constructor', Object.assign({ value: refused }, fixed));
  } catch (e) { /* no generators in this engine */ }
})(__host, this);
delete this.__host;
