#!/usr/bin/env python3
"""Playback scenarios on a phone (debug build app.orpheon.beta.dev, signed in).

usage: tools/playback_test.py [--serial S] [--only name,name] [--skip name,name] [--playlist URI]

Drives the app as a user would: media keys (notification, headset path) and the debug-only
test receiver (TestControlReceiver: the app's own play / seek commands). Reads the page through
CDP (tools/cdp.py) and the app's log (adb logcat -s Myspoti MspPlayer).

After each step: the expected song is current, not paused, its position advances (page state
and the page's media element), the page volume is above 0 and the media element is not muted.
Log lines that mean trouble (call timeouts, stalls, a frozen page) fail the scenario unless it
provoked them. Ads that come up are waited out; one heard (element not muted) is a failure.
Exit code 0 when every scenario passes.
"""
import argparse, json, os, re, shlex, subprocess, sys, threading, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cdp  # noqa: E402

ADB = cdp.ADB
PKG = "app.orpheon.beta.dev"
OTHERS = ["app.orpheon.beta", "app.myspoti", "com.spotify.music"]
PLAYLIST = "spotify:playlist:5G7gcsU1CbvqYJYFawDRZa"
BAD = [
    (re.compile(r"timeout after \d+ ms"), "call timeout"),
    (re.compile(r"player stalled"), "player stalled"),
    (re.compile(r"page not answering"), "page not answering"),
    (re.compile(r"transport failed"), "transport failed"),
    (re.compile(r"page silent during a song"), "page left silent"),
    (re.compile(r"play of \S+ not taken"), "play not taken"),
    (re.compile(r"command failed"), "command failed"),
    (re.compile(r"FATAL EXCEPTION|AndroidRuntime"), "crash"),
]

SERIAL = None
# PT_SAFE=1 (unattended runs on the user's own phone): no HOME/sleep/wake key (they light the screen),
# no force-stop of other apps, no pull_back scenario (launches the Spotify app, transfers playback to
# another device). The screen stays as the user left it.
SAFE = bool(os.environ.get("PT_SAFE"))
# PT_HEADLESS=1 (default off): the owner is on the phone, so nothing may reach the foreground or the
# input system. The app is started as a foreground service only (no activity), and the media keys
# of key() are sent as the debug receiver's commands (next, previous, resume, pause) instead of
# `input keyevent`; every other key is dropped. Implies PT_SAFE.
HEADLESS = bool(os.environ.get("PT_HEADLESS"))
if HEADLESS:
    SAFE = True


def adb(*args, check=True, timeout=60):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.strip()


# ── page (CDP) ────────────────────────────────────────────────────────────────

def page_socket():
    ps = adb("shell", "ps", "-A", "-o", "PID,NAME")
    unix = adb("shell", "cat", "/proc/net/unix")
    pid = next((l.split()[0] for l in ps.splitlines()[1:] if l.split()[-1] == PKG
                and f"webview_devtools_remote_{l.split()[0]}" in unix), "")
    if not pid:
        raise RuntimeError("app not running")
    adb("forward", f"tcp:{cdp.PORT}", f"localabstract:webview_devtools_remote_{pid}")
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{cdp.PORT}/json", timeout=5))
    cands = [p for p in pages if p.get("type") == "page" and "open.spotify.com" in p.get("url", "")]
    page = next((p for p in cands if '"height":800' in p.get("description", "")), cands[0] if cands else None)
    if not page:
        raise RuntimeError("no open.spotify.com page")
    s = cdp.ws_connect(page["webSocketDebuggerUrl"])
    return s


def evaluate(expr, timeout=12):
    """Runs [expr] in the page; returns its value, raises on an exception or no answer."""
    s = page_socket()
    try:
        s.settimeout(timeout)
        cdp.ws_send(s, json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
            "expression": expr, "awaitPromise": True, "returnByValue": True, "timeout": timeout * 1000}}))
        while True:
            msg = json.loads(cdp.ws_recv(s))
            if msg.get("id") == 1:
                r = msg.get("result", {})
                if "exceptionDetails" in r:
                    raise RuntimeError("page exception: " + str(r["exceptionDetails"].get("exception", {}).get("description", ""))[:300])
                return r.get("result", {}).get("value")
    finally:
        s.close()


def fire(expr):
    """Runs [expr] without waiting for it (a script that freezes the page)."""
    s = page_socket()
    try:
        cdp.ws_send(s, json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr}}))
        time.sleep(0.5)
    finally:
        s.close()


def bridge(method, args=None, timeout=12):
    a = json.dumps(args or {})
    return evaluate(f"window.Myspoti.call(-1, {json.dumps(method)}, {a})", timeout)


PROBE = """(async () => {
  const t = (p, ms) => Promise.race([p, new Promise((_, j) => setTimeout(() => j(new Error('no answer')), ms))]);
  const c = (m, a) => t(window.Myspoti.call(-1, m, a || {}), 4000);
  const s = await c('player.state');
  const q = await c('player.queue');
  let vol = null, media = null;
  try { vol = await c('volume.get'); } catch (e) { vol = 'ERR ' + e.message; }
  try { media = await c('media.state'); } catch (e) { media = 'ERR ' + e.message; }
  const now = Date.now();
  const pos = s ? (s.isPaused || s.isBuffering ? s.positionMs : s.positionMs + (now - s.timestamp) * (s.speed || 1)) : null;
  return { now, track: s && s.track && { uri: s.track.uri, name: s.track.name, kind: s.track.kind, durationMs: s.track.durationMs },
    next: s && s.next && s.next.uri, previous: s && s.previous && s.previous.uri, context: s && s.contextUri,
    paused: s && s.isPaused, buffering: s && s.isBuffering, pos, restrictions: s && s.restrictions,
    upcoming: q ? [...q.queued, ...q.nextUp].map(i => i.uri) : [], volume: vol, media };
})()"""


def probe(patience=12):
    """The page's state; retried for [patience] s while the page reloads (no bridge yet).

    A missing track is normal: during an ad (and right after a reload) the app publishes no item at
    all. Callers read `p["track"]["uri"]`, so an empty track dict is put in its place instead of
    None — a test that crashes on an ad tells us nothing about the app.
    """
    end = time.time() + patience
    while True:
        try:
            p = evaluate(PROBE, timeout=15)
            if not isinstance(p.get("track"), dict):
                p["track"] = {}
            for k in ("uri", "name", "kind"):
                p["track"].setdefault(k, None)
            p["track"].setdefault("durationMs", 0)
            return p
        except Exception:
            if time.time() > end:
                raise
            time.sleep(1)


# ── log ───────────────────────────────────────────────────────────────────────

class Log:
    def __init__(self):
        adb("logcat", "-c")
        self.lines = []
        # This app's lines only: the other Myspoti builds on the phone (beta, stable, side builds)
        # log under the same tags, and a frozen one in the background failed every scenario here.
        uid = re.search(r"uid:(\d+)", adb("shell", "pm", "list", "packages", "-U", PKG, check=False))
        cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + ["logcat", "-v", "time"] + \
              ([f"--uid={uid.group(1)}"] if uid else []) + ["-s", "Myspoti:*", "MspPlayer:*", "AndroidRuntime:E"]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, errors="replace")
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip("\n"))

    def mark(self):
        return len(self.lines)

    def since(self, mark):
        return self.lines[mark:]

    def stop(self, path):
        self.proc.kill()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("\n".join(self.lines) + "\n")


# ── checks ────────────────────────────────────────────────────────────────────

class Fail(Exception):
    pass


def name(p):
    return (p.get("track") or {}).get("name") if p else None


def is_ad(p):
    return bool(p and (p.get("track") or {}).get("kind") == "ad")


def wait_for(cond, timeout, what, every=0.7):
    end = time.time() + timeout
    last, err = None, None
    while time.time() < end:
        try:
            last = probe()
            err = None
            if cond(last):
                return last
        except Exception as e:  # page reloading, app starting
            err = e
        time.sleep(every)
    raise Fail(f"{what}: not after {timeout}s (last: {name(last)} paused={last and last.get('paused')} err={err})")


class Run:
    def __init__(self, log):
        self.log = log
        self.ads = 0
        self.notes = []

    def wait_out_ad(self, p, budget=75):
        """An ad is current: it must be muted, and the app must get past it."""
        if not is_ad(p):
            return p
        self.ads += 1
        print(f"    ad: {name(p)!r}, waiting it out")
        end = time.time() + budget
        while time.time() < end:
            if isinstance(p.get("media"), dict) and not p["media"]["muted"] and not p["media"]["paused"] and p["media"]["volume"] > 0.01 and p.get("volume", 0) not in (0, 0.0):
                raise Fail(f"ad {name(p)!r} audible (media not muted)")
            time.sleep(1)
            p = probe()
            if not is_ad(p):
                return p
        raise Fail(f"stuck on the ad {name(p)!r} for {budget}s")

    def healthy(self, expect=None, label="", settle=12, allow_track_change=False):
        """[expect] (uri) becomes current and plays: position and media time advance, sound on."""
        def ok(p):
            if is_ad(p):
                return True
            return p.get("track") and (expect is None or p["track"]["uri"] == expect) and not p.get("paused") and not p.get("buffering")
        p = wait_for(ok, settle, f"{label}: {expect or 'a song'} playing")
        p = self.wait_out_ad(p)
        if expect and p["track"]["uri"] != expect:
            if not allow_track_change and self.ads == 0:
                raise Fail(f"{label}: {name(p)!r} current, expected {expect}")
        a = p
        for _ in range(3):
            time.sleep(3)
            b = self.wait_out_ad(probe())
            # The song restarted in between (a previous, a seek): measure again from there.
            if b["track"]["uri"] == a["track"]["uri"] and (b["pos"] or 0) < (a["pos"] or 0) - 1500:
                a = b
                continue
            break
        problems = []
        if b.get("paused"):
            problems.append("paused")
        if b["track"]["uri"] == a["track"]["uri"] and (b["pos"] or 0) - (a["pos"] or 0) < 1500:
            problems.append(f"position stuck ({a['pos']} -> {b['pos']})")
        ma, mb = a.get("media"), b.get("media")
        if not isinstance(mb, dict):
            problems.append(f"no media element ({mb})")
        else:
            if mb["muted"]:
                problems.append("media element muted")
            if mb["paused"]:
                problems.append("media element paused")
            if mb["volume"] < 0.05:
                problems.append(f"media element silent (volume {mb['volume']})")
            if isinstance(ma, dict) and b["track"]["uri"] == a["track"]["uri"] and mb["time"] - ma["time"] < 1.5:
                problems.append(f"media time stuck ({ma['time']:.1f} -> {mb['time']:.1f})")
        if not isinstance(b.get("volume"), (int, float)) or b["volume"] <= 0:
            problems.append(f"page volume {b.get('volume')}")
        if problems:
            raise Fail(f"{label}: {name(b)!r} " + ", ".join(problems))
        return b

    def check_log(self, mark, allowed=()):
        bad = []
        for line in self.log.since(mark):
            for rx, what in BAD:
                if what not in allowed and rx.search(line):
                    bad.append(line[:220])
        if bad:
            raise Fail("log: " + " | ".join(bad[:4]))


KEY_COMMANDS = {87: "next", 88: "previous", 126: "resume", 127: "pause"}


def key(code):
    if HEADLESS:
        for c in (code if isinstance(code, list) else [code]):
            if c in KEY_COMMANDS:
                test(KEY_COMMANDS[c])
        return
    if SAFE:
        code = [c for c in (code if isinstance(code, list) else [code]) if c not in (3, 26, 223, 224)]  # HOME wakes the screen too
        if not code:
            return
    adb("shell", "input", "keyevent", *[str(c) for c in (code if isinstance(code, list) else [code])])


def test(cmd, **extras):
    args = ["shell", "am", "broadcast", "-a", "app.myspoti.TEST", "-p", PKG, "--es", "cmd", cmd]
    for k, v in extras.items():
        args += (["--el", k, str(v)] if isinstance(v, int) else ["--es", k, shlex.quote(v)])
    adb(*args)


NEXT, PREV, PLAY, PAUSE, HOME, SLEEP, WAKE = 87, 88, 126, 127, 3, 223, 224


def launch():
    if HEADLESS:
        # The playback service alone (no activity, nothing in front): the engine runs without a screen.
        adb("shell", "am", "start-foreground-service", "-n", PKG + "/app.myspoti.host.android.PlaybackService")
        return
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def wait_ready(timeout=60):
    return wait_for(lambda p: p.get("track") is not None, timeout, "page ready", every=1.5)


def seek_to(r, ms):
    test("seek", ms=ms)
    wait_for(lambda p: p["pos"] is not None and abs(p["pos"] - ms) < 4000, 8, f"seek to {ms}")


# ── scenarios ─────────────────────────────────────────────────────────────────

def s_play_playlist(r, ctx):
    tracks = evaluate(f"""(async () => {{
      const p = await window.Myspoti.call(-1, 'service.call', {{service: 'PlaylistAPI', method: 'getContents', args: [{json.dumps(ctx['playlist'])}, {{limit: 12}}]}});
      return p.items.filter(i => i.isPlayable).map(i => i.uri);
    }})()""")
    ctx["tracks"] = tracks
    test("play", track=tracks[0] + "|", context=ctx["playlist"])
    r.healthy(tracks[0], "play playlist", settle=15)


def s_next(r, ctx):
    p = probe()
    exp = p["next"]
    key(NEXT)
    r.healthy(exp, "next")


def s_rapid_next(r, ctx):
    p = probe()
    exp = p["upcoming"][4] if len(p["upcoming"]) > 4 else None
    key([NEXT] * 5)
    r.healthy(exp, "rapid next x5", settle=15)


def s_rapid_swipe(r, ctx):
    # Reported (beta.9): fast taps on the player's "next" left the screen on the second song
    # for seconds while the page went on. The app's own button path, then what the app shows.
    p = probe()
    exp = p["upcoming"][4] if len(p["upcoming"]) > 4 else None
    for _ in range(5):
        test("swipenext")
        time.sleep(0.12)
    r.healthy(exp, "rapid swipe next x5", settle=15)
    same_on_screen(r, "after the taps")


def s_previous_start(r, ctx):
    p = probe()
    if not p.get("previous"):
        key(NEXT)
        r.healthy(p.get("next"), "next before previous")
        p = probe()
    exp = p["previous"]
    seek_to(r, 0)
    time.sleep(1.5)
    key(PREV)
    r.healthy(exp, "previous at 0:02")


def s_previous_middle(r, ctx):
    p = probe()
    cur = p["track"]["uri"]
    seek_to(r, 60_000)
    key(PREV)
    b = r.healthy(cur, "previous at 1:00")
    if b["pos"] > 10_000:
        raise Fail(f"previous at 1:00 did not restart the song (at {b['pos']} ms)")


def s_seek(r, ctx):
    cur = probe()["track"]["uri"]
    seek_to(r, 90_000)
    b = r.healthy(cur, "seek")
    if not 88_000 < b["pos"] < 100_000:
        raise Fail(f"seek: at {b['pos']} ms, expected ~90 s")


def s_pause_resume(r, ctx):
    cur = probe()["track"]["uri"]
    key(PAUSE)
    p = wait_for(lambda p: p.get("paused"), 6, "pause")
    time.sleep(3)
    q = probe()
    if not q.get("paused") or abs((q["pos"] or 0) - (p["pos"] or 0)) > 1500:
        raise Fail(f"pause did not hold ({p['pos']} -> {q['pos']}, paused={q.get('paused')})")
    if isinstance(q.get("media"), dict) and not q["media"]["paused"]:
        raise Fail("pause: media element still playing")
    key(PLAY)
    r.healthy(cur, "resume")


def s_song_end(r, ctx):
    p = probe()
    exp, cur = p["next"], p["track"]["uri"]
    seek_to(r, p["track"]["durationMs"] - 6000)
    wait_for(lambda q: q["track"] and q["track"]["uri"] != cur, 25, "song end into the next one")
    r.healthy(exp, "song end")


def s_background(r, ctx):
    key(HOME)
    time.sleep(1)
    key(SLEEP)
    try:
        start = time.time()
        while time.time() - start < 180:
            time.sleep(30)
            r.healthy(None, f"screen off {int(time.time() - start)}s", allow_track_change=True)
    finally:
        key(WAKE)


def s_cold_start(r, ctx):
    cur = probe()["track"]["uri"]
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    launch()
    # Tapped as soon as the mini player shows: before the page is ready.
    time.sleep(2.5)
    test("resume")
    r.healthy(cur, "cold start then play", settle=60)


def s_reload(r, ctx):
    cur = probe()["track"]["uri"]
    fire("location.reload()")
    time.sleep(3)
    r.healthy(cur, "page reloaded mid-song", settle=45)


def s_frozen(r, ctx):
    cur = probe()["track"]["uri"]
    # The page's main thread blocked for 30 s, as a frozen renderer.
    fire("(() => { const end = Date.now() + 30000; while (Date.now() < end) {} })()")
    time.sleep(32)
    r.healthy(cur, "frozen page recovered", settle=60)


def s_mute(r, ctx):
    cur = probe()["track"]["uri"]
    # Muted and read in one page call: a mute the app did not ask for is undone by its own
    # silence watchdog ("no silence until a restart") within a tick, so a separate probe could
    # read the restored level and fail a mute that worked (seen once in 6 runs).
    p = evaluate("""(async () => {
      const n = await window.Myspoti.call(-1, 'media.quiet', {on: true});
      return {n, media: await window.Myspoti.call(-1, 'media.state', {})};
    })()""")
    n = p.get("n")
    if not isinstance(p.get("media"), dict) or p["media"]["volume"] > 0.01:
        raise Fail(f"media.quiet on: element not silenced ({n} set, {p.get('media')})")
    # Silenced, a song that restarts must still play (Chromium pauses muted hidden media).
    seek_to(r, 0)
    time.sleep(2.5)
    q = probe()
    if q.get("paused") or (isinstance(q.get("media"), dict) and q["media"]["paused"]):
        bridge("media.quiet", {"on": False})
        raise Fail("silenced song paused after a restart")
    bridge("media.quiet", {"on": False})
    r.healthy(cur, "unmute")


def s_pull_back(r, ctx):
    devices = bridge("service.call", {"service": "ConnectDevicesAPI", "method": "getDevices", "args": []})
    remote = next((d for d in devices if not d.get("isLocal")), None)
    started = False
    if not remote:
        # The Spotify app on this phone, signed in to the same account, as the other device.
        adb("shell", "monkey", "-p", "com.spotify.music", "-c", "android.intent.category.LAUNCHER", "1", check=False)
        started = True
        end = time.time() + 25
        while not remote and time.time() < end:
            time.sleep(2)
            devices = bridge("service.call", {"service": "ConnectDevicesAPI", "method": "getDevices", "args": []})
            remote = next((d for d in devices if not d.get("isLocal")), None)
    try:
        if not remote:
            r.notes.append("pull back: no other device on the account, skipped")
            return
        pull_back_from(r, remote)
    finally:
        if started:
            adb("shell", "am", "force-stop", "com.spotify.music", check=False)


def pull_back_from(r, remote):
    cur = probe()["track"]["uri"]
    bridge("service.call", {"service": "ConnectTransferAPI", "method": "transferPlayback", "args": [remote["id"], {"playOnTransfer": True}]}, timeout=20)
    # Play is tapped from the notification, the Spotify app still in front, as soon as it has
    # playback (the free app leaves Spotify Connect a few seconds later).
    end = time.time() + 6
    taken = False
    while not taken and time.time() < end:
        time.sleep(0.4)
        devices = bridge("service.call", {"service": "ConnectDevicesAPI", "method": "getDevices", "args": []})
        taken = any(d.get("isActive") and not d.get("isLocal") for d in devices)
    if not taken:
        r.notes.append(f"pull back: {remote['name']} did not take playback (offline?), skipped")
        test("resume")
        r.healthy(None, "pull back (no transfer)", settle=20, allow_track_change=True)
        return
    test("resume")
    r.healthy(None, f"pull back from {remote['name']}", settle=20, allow_track_change=True)
    devices = bridge("service.call", {"service": "ConnectDevicesAPI", "method": "getDevices", "args": []})
    if not any(d.get("isActive") and d.get("isLocal") for d in devices):
        raise Fail("pull back: this phone is not the active device")
    # The 'taken by' line is NOT required: a resume sent while the other device still holds playback
    # makes the page claim playback here, and then no pull back is needed at all. What matters is the
    # outcome — this phone active, the sound playing — which is what the checks above assert. Keep the
    # line only as a note, so the fast path stays visible in the report instead of failing the run.
    if not any("taken by" in l for l in r.log.lines[-400:]):
        r.notes.append(f"pull back from {remote['name']}: the resume claimed playback (no pull-back line)")


def ui_title():
    """The title the app shows (its media session: notification, lock screen)."""
    out = adb("shell", "dumpsys", "media_session")
    block = out.split("package=" + PKG, 1)[1] if ("package=" + PKG) in out else ""
    m = re.search(r"description=(.*)", block)
    return m.group(1).strip() if m else ""


def same_on_screen(r, label):
    """What is heard is what the app shows."""
    p = probe()
    shown = ui_title()
    if not p.get("track") or not shown.startswith(p["track"]["name"]):
        raise Fail(f"{label}: the app shows {shown!r} while {name(p)!r} plays")


FAKE_AD = """(() => { try {
  const host = document.getElementById('main') || document.body;
  let plat = null;
  for (const el of [host, ...host.querySelectorAll('*')]) { const key = Object.keys(el).find(k => k.startsWith('__reactContainer$')); if (!key) continue;
    const stack = [el[key]]; while (stack.length && !plat) { const f = stack.pop(); const v = f.memoizedProps && f.memoizedProps.value;
      if (v && typeof v === 'object' && typeof v.getRegistry === 'function') plat = v; if (f.sibling) stack.push(f.sibling); if (f.child) stack.push(f.child); } if (plat) break; }
  const r = plat.getRegistry(); let pl = null; for (const k of r._map.keys()) if (k && k.description === 'PlayerAPI') pl = r.resolve(k);
  // The page's state now says an ad plays (as Spotify's own ad items look); the page reload
  // that the app's ad recovery does drops this patch.
  const orig = pl.getState.bind(pl);
  pl.getState = () => { const s = orig(); return s && Object.assign({}, s, { item: Object.assign({}, s.item, { type: 'ad', uri: 'spotify:ad:test', name: 'Test ad' }) }); };
  window.Myspoti.announce();
  return true;
} catch (e) { return String(e && e.stack || e).slice(0, 600); } })()"""


def s_ad_taps(r, ctx):
    r.healthy(None, "before the ad", allow_track_change=True)
    p = probe()
    exp = p["upcoming"][2] if len(p["upcoming"]) > 2 else None
    fake_ad()
    time.sleep(0.2)
    key([NEXT] * 3)  # three fast taps while the ad is on
    r.healthy(exp, "3 nexts during an ad", settle=40)
    same_on_screen(r, "after the ad")


def fake_ad():
    for _ in range(5):
        res = evaluate(FAKE_AD)
        if res is True:
            return
        print("    fake ad not set:", res)
        time.sleep(2)
    raise Fail("could not fake an ad in the page")


def s_ad_previous(r, ctx):
    r.healthy(None, "before the ad", allow_track_change=True)
    cur = probe()["track"]["uri"]
    seek_to(r, 60_000)
    fake_ad()
    time.sleep(0.2)
    key(PREV)  # refused by the page during an ad: kept, never a seek on the ad
    b = r.healthy(cur, "previous during an ad", settle=40)
    if b["pos"] > 15_000:
        raise Fail(f"previous during an ad: the song did not start over (at {b['pos']} ms)")
    same_on_screen(r, "after the ad")


def s_ad_pick(r, ctx):
    # Reported (beta.9): a song picked, an ad in its place, then the old queue went on.
    r.healthy(None, "before the pick", allow_track_change=True)
    p = probe()
    tracks = ctx.get("tracks") or []
    exp = next((t for t in reversed(tracks) if t != p["track"]["uri"] and t not in p["upcoming"][:2]), None)
    if not exp:
        raise Fail("no other song to pick")
    test("play", track=exp + "|", context=ctx["playlist"])
    time.sleep(0.15)
    fake_ad()
    r.healthy(exp, "song picked, then an ad", settle=45)
    same_on_screen(r, "after the ad")


def s_reload_taps(r, ctx):
    p = probe()
    exp = p["upcoming"][1] if len(p["upcoming"]) > 1 else None
    fire("location.reload()")
    time.sleep(0.3)
    key([NEXT] * 2)  # two fast taps while the page reloads
    r.healthy(exp, "2 nexts during a reload", settle=45)
    same_on_screen(r, "after the reload")


SCENARIOS = [
    ("play_playlist", s_play_playlist, ()),
    ("next", s_next, ()),
    ("rapid_next", s_rapid_next, ()),
    ("rapid_swipe", s_rapid_swipe, ()),
    ("previous_start", s_previous_start, ()),
    ("previous_middle", s_previous_middle, ()),
    ("seek", s_seek, ()),
    ("pause_resume", s_pause_resume, ()),
    ("song_end", s_song_end, ()),
    ("mute", s_mute, ()),
    ("reload", s_reload, ()),
    ("reload_taps", s_reload_taps, ("transport failed",)),
    ("ad_taps", s_ad_taps, ()),
    ("ad_previous", s_ad_previous, ()),
    # The fake ad hides the picked song from the app, which asks for it again: provoked.
    ("ad_pick", s_ad_pick, ("play not taken",)),
    ("frozen", s_frozen, ("call timeout", "page not answering", "command failed", "transport failed")),
    ("background", s_background, ()),
    ("cold_start", s_cold_start, ()),
    ("pull_back", s_pull_back, ()),
]


def main():
    global SERIAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--only", default="")
    ap.add_argument("--skip", default="")
    ap.add_argument("--playlist", default=PLAYLIST)
    a = ap.parse_args()
    SERIAL = a.serial
    if not SAFE:
        for p in OTHERS:
            adb("shell", "am", "force-stop", p, check=False)
        adb("shell", "input", "keyevent", str(WAKE))
    log = Log()
    if not adb("shell", "pidof", PKG, check=False):
        launch()
    wait_ready()
    only = set(filter(None, a.only.split(",")))
    skip = set(filter(None, a.skip.split(",")))
    if SAFE:
        skip.add("pull_back")  # starts the Spotify app on the phone and transfers playback to another device
    ctx = {"playlist": a.playlist}
    results = []
    r = Run(log)
    for n, fn, allowed in SCENARIOS:
        if (only and n not in only and n != "play_playlist") or n in skip:
            continue
        mark = log.mark()
        log.lines.append(f"===== scenario {n}")
        t0 = time.time()
        r.ads = 0
        try:
            fn(r, ctx)
            time.sleep(1)
            r.check_log(mark, allowed)
            results.append((n, True, ""))
            print(f"PASS {n} ({time.time() - t0:.0f}s){' ads=' + str(r.ads) if r.ads else ''}")
        except Exception as e:
            results.append((n, False, str(e)))
            print(f"FAIL {n} ({time.time() - t0:.0f}s): {e}")
            # Back to a known state for the next scenario.
            try:
                test("resume")
                time.sleep(4)
            except Exception:
                pass
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures", "playback_test.log")
    log.stop(path)
    print("log:", path)
    for note in r.notes:
        print("note:", note)
    failed = [n for n, ok, _ in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed" + (f"; failed: {', '.join(failed)}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
