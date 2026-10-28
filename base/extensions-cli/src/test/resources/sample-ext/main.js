// Answers where a song is from api.example.com, and why not when it cannot (feature "results").
var results = myspoti.features && myspoti.features.indexOf('results') >= 0;

myspoti.provide('audio-source', function (track) {
  myspoti.storage.set('lastTitle', track.title);
  var n = myspoti.storage.get('asked') || 0;
  myspoti.storage.set('asked', n + 1);
  var r = myspoti.http.get('https://api.example.com/find?title=' + encodeURIComponent(track.title), { timeout: 15000 });
  if (r.status === 429 && results) return { outcome: 'rate-limited', retryAfterMs: 1000 * Number(r.headers['retry-after'] || r.headers['Retry-After'] || 60) };
  if (r.status === 401 && results) return { outcome: 'sign-in', detail: 'Sign in again' };
  if (r.status >= 500 && results) return { outcome: 'server-down' };
  if (!r.ok) return null;
  return { url: r.json().url, mimeType: 'audio/flac' };
});
