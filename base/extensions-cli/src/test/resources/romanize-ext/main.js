// The lines it knows, by text; an older Myspoti without the hook never calls it.
var KNOWN = { '夢ならば': 'yume naraba', '사랑해': 'saranghae' };

myspoti.provide('lyrics-romanize', function (input) {
  if (myspoti.features.indexOf('lyrics.romanize') < 0) throw new Error('lyrics.romanize is not a feature of this Myspoti');
  var lines = [];
  input.lines.forEach(function (l) {
    if (KNOWN[l.text]) lines.push({ index: l.index, romanization: KNOWN[l.text] });
  });
  return lines.length ? { lines: lines } : null;
});
