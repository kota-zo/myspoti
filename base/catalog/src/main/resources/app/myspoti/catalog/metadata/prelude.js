// SPDX-License-Identifier: GPL-3.0-only
// What a metadata script finds around it. The host gives one function, __host, and nothing else.
var __ext = null;
function registerExtension(e) { __ext = e; }
var log = { debug: function () {}, info: function () {}, warn: function () {}, error: function () {} };
var console = log;

function __request(method, url, headers, body) {
  var text = body === undefined || body === null ? null : (typeof body === "string" ? body : JSON.stringify(body));
  return JSON.parse(__host(String(method), String(url), JSON.stringify(headers || {}), text));
}

var http = {
  get: function (url, headers) { return __request("GET", url, headers, null); },
  post: function (url, body, headers) { return __request("POST", url, headers, body); },
  request: function (url, options) { options = options || {}; return __request(options.method || "GET", url, options.headers, options.body); }
};

function fetch(url, options) {
  options = options || {};
  var r = __request(options.method || "GET", url, options.headers, options.body);
  if (r.error) throw new Error(r.error);
  return {
    ok: r.statusCode >= 200 && r.statusCode < 300,
    status: r.statusCode,
    statusCode: r.statusCode,
    headers: { get: function () { return null; } },
    text: function () { return r.body; },
    json: function () { return JSON.parse(r.body); }
  };
}

function URL(href) {
  var m = /^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(String(href));
  if (!m) throw new TypeError("Invalid URL");
  this.href = String(href);
  this.protocol = m[1].toLowerCase();
  this.host = m[2].toLowerCase();
  this.hostname = this.host.replace(/:\d+$/, "");
  this.pathname = m[3] || "/";
  this.search = m[4] || "";
  this.hash = m[5] || "";
}
URL.prototype.toString = function () { return this.href; };

var __store = {};
var storage = {
  get: function (key) { return Object.prototype.hasOwnProperty.call(__store, key) ? __store[key] : null; },
  set: function (key, value) { __store[key] = value; return true; },
  remove: function (key) { delete __store[key]; return true; }
};

var utils = {
  appUserAgent: function () { return __userAgent; },
  randomUserAgent: function () { return __userAgent; },
  appVersion: function () { return "1"; },
  sleep: function () {},
  isRequestCancelled: function () { return false; },
  isDownloadCancelled: function () { return false; },
  getResolutionRemainingMs: function () { return 20000; }
};

function __distance(a, b) {
  var row = [], i, j;
  for (j = 0; j <= b.length; j++) row[j] = j;
  for (i = 1; i <= a.length; i++) {
    var last = row[0];
    row[0] = i;
    for (j = 1; j <= b.length; j++) {
      var kept = row[j];
      row[j] = a.charAt(i - 1) === b.charAt(j - 1) ? last : 1 + Math.min(last, row[j], row[j - 1]);
      last = kept;
    }
  }
  return row[b.length];
}

var matching = {
  normalizeString: function (s) { return String(s || "").toLowerCase().replace(/[\s!-\/:-@\[-`{-~ -¿‐-‧]+/g, " ").trim(); },
  compareStrings: function (a, b) {
    a = matching.normalizeString(a); b = matching.normalizeString(b);
    if (!a && !b) return 1;
    return 1 - __distance(a, b) / Math.max(a.length, b.length, 1);
  }
};

function __call(name, argsJson) {
  var f = __ext && __ext[name];
  if (typeof f !== "function") return "null";
  var out = f.apply(__ext, JSON.parse(argsJson));
  return JSON.stringify(out === undefined ? null : out);
}
