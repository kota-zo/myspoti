#!/usr/bin/env python3
"""Time from a user action to the song's sound on the phone (debug build).

usage: tools/tap_latency.py [--n 5] [--modes tap,next,previous,resume] [--wait 5.5]
The action and the page's first audio frame are both timed on the phone's clock,
so the number is what the listener waits, not what the host spends.

- tap      : tap a song row (the debug test receiver plays another track of the playlist)
- next     : the next button
- previous : the previous button
- resume   : pause, let the element freeze, then resume

Every action goes through the app's own command path (SongPlayer), as a tap does.
"""
import argparse, json, os, statistics, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import playback_test as t

PKG = "app.orpheon.beta.dev"

# First sound of a new song: the page's media element starts advancing once the
# content changed or the element reset to the start.
WATCH_RESET = """(() => {
  const sdk = (() => { const r = __mspPlat().getRegistry(); for (const k of r._map.keys()) if (k && k.description === 'PlayerSDK') return r.resolve(k); })();
  window.__mspLat = null;
  const pl = sdk.harmony._streamer._playerPromise;
  pl.then(p => {
    const from = p._currentContent && p._currentContent._track && p._currentContent._track.uri;
    const started = Date.now();
    let armed = false, mark;
    const tick = () => {
      const el = p._player;
      const uri = p._currentContent && p._currentContent._track && p._currentContent._track.uri;
      if (uri !== from || (el && el.currentTime < 0.05)) armed = true;
      if (armed && el && !el.paused && el.currentTime > 0.05) {
        if (mark === undefined) mark = el.currentTime;
        else if (el.currentTime !== mark) { window.__mspLat = Date.now(); return; }
      }
      if (Date.now() - started < 10000) setTimeout(tick, 15);
    };
    tick();
  });
  return true;
})()"""

# First sound after a resume: the element must be seen frozen, then advance again.
WATCH_RESUME = """(() => {
  const sdk = (() => { const r = __mspPlat().getRegistry(); for (const k of r._map.keys()) if (k && k.description === 'PlayerSDK') return r.resolve(k); })();
  window.__mspLat = null;
  const pl = sdk.harmony._streamer._playerPromise;
  pl.then(p => {
    const started = Date.now();
    let armed = false, mark;
    const tick = () => {
      const el = p._player;
      if (el && el.paused) { armed = true; mark = undefined; }
      else if (armed && el && el.currentTime > 0.05) {
        if (mark === undefined) mark = el.currentTime;
        else if (el.currentTime !== mark) { window.__mspLat = Date.now(); return; }
      }
      if (Date.now() - started < 10000) setTimeout(tick, 15);
    };
    tick();
  });
  return true;
})()"""

PLAT = """window.__mspPlat = () => { const host = document.getElementById('main') || document.body;
  for (const el of [host, ...host.querySelectorAll('*')]) { const key = Object.keys(el).find(k => k.startsWith('__reactContainer$')); if (!key) continue;
    const stack = [el[key]]; while (stack.length) { const f = stack.pop(); const v = f.memoizedProps && f.memoizedProps.value;
      if (v && typeof v === 'object' && typeof v.getRegistry === 'function') return v; if (f.sibling) stack.push(f.sibling); if (f.child) stack.push(f.child); } } }; true"""


def broadcast(**es):
    """Runs the debug test receiver; returns the phone clock right before the command."""
    args = ["--es", "cmd", es.pop("cmd")]
    for k, v in es.items():
        args += ["--es", k, str(v)]
    cmd = f"date +%s%3N; am broadcast -a app.myspoti.TEST -p {PKG} {' '.join(args)}"
    return int(t.adb("shell", cmd).splitlines()[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5)
    ap.add_argument("--modes", default="tap,next,previous,resume")
    ap.add_argument("--wait", type=float, default=5.5)
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    modes = [m.strip() for m in a.modes.split(",") if m.strip()]

    p = t.probe()
    pool = ([p.get("next")] if p.get("next") else []) + [u for u in (p.get("upcoming") or [])]
    if not pool:
        raise SystemExit("no upcoming tracks to tap")

    results = {}
    for mode in modes:
        print(f"== {mode}")
        vals = []
        for i in range(a.n):
            t.evaluate(PLAT)   # the page reloads on ad breaks, the helper goes with it
            t.evaluate(WATCH_RESUME if mode == "resume" else WATCH_RESET)
            if mode == "resume":
                broadcast(cmd="pause")
                time.sleep(2.0)
                pressed = broadcast(cmd="resume")
            elif mode == "tap":
                pressed = broadcast(cmd="play", track=pool[i % len(pool)], context=p.get("context") or "")
            elif mode in ("next", "previous"):
                pressed = broadcast(cmd=mode)
            else:
                raise SystemExit(f"unknown mode {mode}")
            time.sleep(a.wait)
            heard = t.evaluate("window.__mspLat")
            ms = heard - pressed if heard else None
            vals.append(ms)
            print(f"  {mode} -> {ms if ms is not None else 'no sound seen'} ms", flush=True)
            time.sleep(1.0)
        ok = sorted(v for v in vals if v is not None)
        results[mode] = vals
        if ok:
            print(f"  median {statistics.median(ok):.0f} ms, min {ok[0]}, max {ok[-1]}, n={len(ok)}/{a.n}")
        else:
            print("  no measurement")
    if a.json:
        with open(a.json, "w") as f:
            json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
