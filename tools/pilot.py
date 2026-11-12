#!/usr/bin/env python3
"""Unattended playback pilot on the user's phone (debug build app.orpheon.beta.dev, screen off).

usage: tools/pilot.py [--out DIR] [--only A1,A2,B,C,D,F] [--long-pause SECS]

Per scenario: logcat (epoch) + the page's media element sampled every 50 ms (tools/pilot_lib.py) in
DIR/<scenario>.log / .media.jsonl, analysed by tools/uitrace_check.py into .analysis.json/.txt.
  A1 short pause (5 s) x3: resume latency, rise to the full level
  A2 long pause (>= 6 min, app sent to the background): page visibility, timer chain, resume rise,
     then the end of the song (the "stuck at the end" report)
  B  end of a song (seek to duration - 8 s): the next one within ~3 s at full volume
  C  4 minutes of continuous play across a song boundary
  D  10 pause/resume cycles with random gaps, next/previous, swipenext bursts, seeks
  X  crossfade 6 s over 2 natural song ends, then crossfade 0 and a third end (element volume timeline per end)
  H  headless robustness: pause, 3 min, `resume` broadcast (does the process survive, how long to playing)
  F  tools/qa.py --battery all (PT_SAFE=1: no screen key, no force-stop of other apps)
PT_HEADLESS=1: nothing is started in front and no `input` is sent (see tools/playback_test.py); the user is not waited for.
Rules: only play/pause/skip/seek; never touches the screen; waits 2 min while the user is on the phone;
stops on 6+ licence 429 lines. Results: DIR/results.json (+ REPORT.md draft via --report DIR).
"""
import argparse, json, os, random, re, signal, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pilot_lib as L  # noqa: E402
import uitrace_check as U  # noqa: E402
import playback_test as pt  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = L.PKG
PLAYLIST = pt.PLAYLIST
os.environ["PT_SAFE"] = "1"
pt.SAFE = True


class Stop(Exception):
    pass


# ── the phone is the user's ───────────────────────────────────────────────────

def user_active():
    """True when the screen is on and something else than the app is in front.
    PT_HEADLESS=1: never (the pilot touches neither screen nor foreground; it does not wait for the user)."""
    if pt.HEADLESS:
        return False
    p = L.adb("shell", "dumpsys", "power", timeout=20)
    awake = "mWakefulness=Awake" in p
    if not awake:
        return False
    a = L.adb("shell", "dumpsys", "activity", "activities", timeout=20)
    m = re.search(r"ResumedActivity: ActivityRecord\{\S+ \S+ ([\w.]+)/", a)
    return not (m and m.group(1) == PKG)


def courtesy(note=print):
    while user_active():
        note("user on the phone: pausing 2 minutes")
        time.sleep(120)


def nap(secs, step=30):
    """sleep, but give way to the user"""
    end = time.time() + secs
    while time.time() < end:
        time.sleep(min(step, max(0.0, end - time.time())))
        if time.time() < end and user_active():
            courtesy()
            end += 120


# ── helpers ───────────────────────────────────────────────────────────────────

def ensure_app():
    if not L.adb("shell", "pidof", PKG):
        pt.launch()
        time.sleep(18)
    pt.wait_ready(60)


def tracks():
    return pt.evaluate(f"""(async () => {{
      const p = await window.Myspoti.call(-1, 'service.call', {{service: 'PlaylistAPI', method: 'getContents', args: [{json.dumps(PLAYLIST)}, {{limit: 12}}]}});
      return p.items.filter(i => i.isPlayable).map(i => i.uri);
    }})()""")


def media_state():
    return pt.evaluate("window.Myspoti.call(-1, 'media.state', {})")


def start_song(uri, settle=8):
    L.cmd("play", track=uri + "|", context=PLAYLIST)
    p = pt.wait_for(lambda q: q["track"]["uri"] == uri and not q.get("paused") and not q.get("buffering") and (q.get("pos") or 0) > 1500, 25, "song playing")
    time.sleep(settle)
    return pt.probe()


def uitrace(secs):
    # --ei: the receiver reads getIntExtra("secs"); playback_test.test() sends ints as --el (ignored: 30 s default)
    L.adb("shell", "am", "broadcast", "-a", "app.myspoti.TEST", "-p", PKG, "--es", "cmd", "uitrace", "--ei", "secs", str(int(secs)))


def stop_check(cap):
    n = cap.licence_429()
    if n >= 6:
        raise Stop(f"{n} licence 429 lines: stopped")


def finish(cap, name, ref=1.0, notes=None):
    base = cap.stop()
    an = U.analyse(base, ref)
    txt, _ = U.summary(an, 30)
    open(base + ".analysis.txt", "w").write(txt + "\n")
    json.dump(an, open(base + ".analysis.json", "w"), indent=1, default=str)
    return an


def failures(an, extra=()):
    f = []
    for e in an["events"]:
        if e["scope"] == "spont" and not e.get("soft"):
            f.append(dict(kind=e["kind"], ms=e["ms"], detail=e["detail"], file=os.path.basename(an["base"]), line=e["line"], line2=e["line2"]))
    for m in an["cmd_metrics"]:
        if m.get("FAIL"):
            f.append(dict(kind="resume", ms=m["ms"], detail=f"{m['cmd']}: {m['FAIL']}", file=os.path.basename(an["base"]) + ".log", line=m["line"], line2=None))
    for k in ("player stalled", "call timeout", "not taken", "crash", "http 429"):
        for ms, no, text in an["log_flag_lines"].get(k, []):
            if k == "http 429" and "widevine" not in text.lower():
                continue
            f.append(dict(kind="log:" + k, ms=ms, detail=text, file=os.path.basename(an["base"]) + ".log", line=no, line2=None))
    f.extend(extra)
    return f


def resume_rows(an):
    return [m for m in an["cmd_metrics"] if m["cmd"] in ("resume",)]


def pct(v):
    return None if v is None else round(v, 3)


def fade_failures(an, fname):
    """The resume rise and the pause ramp (build 2009): see tools/uitrace_check.py fade_metrics."""
    f = []
    for r in an.get("rises", []):
        where = dict(file=fname + ".log", line=r["line"], ms=r["ms"])
        if r["t_move_ms"] is None:
            f.append(dict(kind="rise_no_audio", detail=f"resume at {r['ms']}: the element's clock never moved", **where))
            continue
        if r.get("vol_max_before_move") is not None and r["vol_max_before_move"] > 0.01:
            f.append(dict(kind="rise_not_silent", detail=f"element volume {r['vol_max_before_move']} before the clock moved (expected ~0.001)", **where))
        st = r.get("fit_start_after_move_ms")
        if st is not None and abs(st) > 100:
            f.append(dict(kind="rise_start_offset", detail=f"cubic rise starts {st} ms from the clock moving (expected within ~50 ms), fit rms {r.get('fit_rms')}", **where))
        if r.get("to_full_after_move_ms") is None or r["to_full_after_move_ms"] > 750:
            f.append(dict(kind="rise_slow", detail=f"full level {r.get('to_full_after_move_ms')} ms after the clock moved (expected ~500)", **where))
        if r.get("level_ratio_to_own") is not None and r["level_ratio_to_own"] < 0.98:
            f.append(dict(kind="rise_low_end", detail=f"level {r['level_after_median']} 2.5-6 s after = {r['level_ratio_to_own']} of the pre-pause level {r['own']}", **where))
        if r.get("vol_min_after_full") is not None and r["vol_min_after_full"] < 0.9 * r["own"]:
            f.append(dict(kind="rise_dip_after_full", detail=f"volume dipped to {r['vol_min_after_full']} after reaching the level", **where))
    for p in an.get("pause_fades", []):
        where = dict(file=fname + ".log", line=p["line"], ms=p["ms"])
        if p["to_pause_event_ms"] is None:
            f.append(dict(kind="pause_no_event", detail="no element pause event within 3 s", **where))
            continue
        if p["vol_at_pause_event"] is not None and p["vol_at_pause_event"] > 0.05:
            f.append(dict(kind="pause_not_silent", detail=f"element volume {p['vol_at_pause_event']} when the pause event fired", **where))
        if not 100 <= p["to_pause_event_ms"] <= 350:
            f.append(dict(kind="pause_latency", detail=f"pause event {p['to_pause_event_ms']} ms after the command (expected ~160 + call)", **where))
        if not p["monotonic_down"]:
            f.append(dict(kind="pause_not_monotonic", detail=f"ramp {p['ramp']}", **where))
    return f


def pidof():
    return L.adb("shell", "pidof", PKG).strip()


# ── scenarios ─────────────────────────────────────────────────────────────────

def sc_A1(out, T):
    cap = L.Capture(out, "A1_short_pause")
    ref = 1.0
    try:
        start_song(T[0], 8)
        ms0 = media_state()
        ref = float(ms0["own"])
        uitrace(70)
        for i in range(3):
            L.cmd("pause"); time.sleep(5.5)
            L.cmd("resume"); time.sleep(8)
        ms1 = media_state()
        stop_check(cap)
    finally:
        an = finish(cap, "A1", ref=ref)
    f = failures(an) + fade_failures(an, "A1_short_pause")
    rr = resume_rows(an)
    if not (ms1 and ms1["volume"] >= 0.98 * ms0["own"] and not ms1["paused"]):
        f.append(dict(kind="final_volume", detail=f"final media.state {ms1}", file="A1_short_pause.log"))
    return dict(name="A1 short pause x3", fail=f, key=dict(
        own_level=ms0["own"], final=ms1,
        to_audio_ms=[m.get("to_audio_ms") for m in rr], to_playing_ms=[m.get("to_playing_event_ms") for m in rr],
        to_full_after_audio_ms=[m.get("to_full_after_audio_ms") for m in rr], to_full_ms=[m.get("to_full_ms") for m in rr],
        vol_at_audio=[m.get("vol_at_audio") for m in rr], vol_min_after=[m.get("vol_min_1s_to_8s") for m in rr],
        pause_to_event_ms=[m.get("to_pause_event_ms") for m in an["cmd_metrics"] if m["cmd"] == "pause"],
        vol_at_pause_event=[m.get("vol_at_pause_event") for m in an["cmd_metrics"] if m["cmd"] == "pause"],
        ramp=[m.get("ramp") for m in rr][:1]), analysis=an)


def sc_A2(out, T, long_s, name="A2_long_pause", idx=0):
    cap = L.Capture(out, name, sampler=False)
    notes = {}
    ref = 1.0
    try:
        start_song(T[idx], 6)
        st = media_state()
        ref = float(st["own"])
        notes["before_pause"] = st
        L.cmd("pause"); time.sleep(3)
        notes["paused_state"] = media_state()
        # No HOME key: on this phone it wakes the screen. The app stays as the user leaves it (screen off).
        time.sleep(2)
        notes["wakefulness"] = re.findall(r"mWakefulness=\w+", L.adb("shell", "dumpsys", "power"))[:1]
        a = L.adb("shell", "dumpsys", "activity", "activities", timeout=20)
        m = re.search(r"ResumedActivity: ActivityRecord\{\S+ \S+ ([\w.]+)/", a)
        notes["resumed_activity"] = m.group(1) if m else None
        t0 = time.time()
        checks = {}
        for label, at in (("1min", 60), ("end", long_s - 20)):
            nap(max(0, at - (time.time() - t0)))
            try:
                checks[label] = dict(after_s=round(time.time() - t0), **pt.evaluate(L.TIMER_CHAIN_JS, timeout=30))
            except Exception as e:
                checks[label] = dict(error=str(e)[:120])
            stop_check(cap)
        nap(max(0, long_s - (time.time() - t0)))
        try:
            checks["final"] = dict(after_s=round(time.time() - t0), **pt.evaluate(L.TIMER_CHAIN_JS, timeout=30))
        except Exception as e:
            checks["final"] = dict(error=str(e)[:120])
        notes["timers"] = checks
        notes["paused_state_end"] = media_state()
        uitrace(75)
        cap.sampler = L.Sampler(cap.base + ".media.jsonl")
        cap.sampler.start()
        time.sleep(4)
        L.cmd("resume")
        time.sleep(14)
        notes["after_resume"] = media_state()
        # the end of the song after the long pause
        p = pt.probe()
        L.cmd("seek", ms=max(0, p["track"]["durationMs"] - 8000))
        time.sleep(24)
        notes["after_end"] = pt.probe()
        notes["after_end_media"] = notes["after_end"].get("media")
        stop_check(cap)
    finally:
        an = finish(cap, "A2", ref=ref)
    f = failures(an) + fade_failures(an, name)
    t = notes.get("timers", {})
    for lab, c in t.items():
        if "error" in c:
            f.append(dict(kind="timer_check", detail=f"{lab}: {c['error']}", file=name + ".log"))
        else:
            if c.get("vis") != "visible":
                f.append(dict(kind="visibility", detail=f"{lab}: visibilityState={c['vis']} hidden={c['hidden']} after {c['after_s']} s", file=name + ".json"))
            if max(c["chain8x38_ms"]) > 800:
                f.append(dict(kind="timer_throttled", detail=f"{lab}: 8 x setTimeout(38) took {c['chain8x38_ms']} ms after {c['after_s']} s", file=name + ".json"))
    b = an["boundaries"]
    if not b:
        f.append(dict(kind="no_song_change_after_seek", detail="engine never changed song after the seek to duration-8 s", file=name + ".log"))
    rr = resume_rows(an)
    return dict(name="A2 long pause then resume then end of song", fail=f, key=dict(
        notes=notes, timers=t, to_audio_ms=[m.get("to_audio_ms") for m in rr], to_playing_ms=[m.get("to_playing_event_ms") for m in rr],
        to_full_after_audio_ms=[m.get("to_full_after_audio_ms") for m in rr], vol_at_audio=[m.get("vol_at_audio") for m in rr],
        vol_min_after=[m.get("vol_min_1s_to_8s") for m in rr], ramp=[m.get("ramp") for m in rr][:1], boundaries=b), analysis=an)


def boundary_failures(an, limit_s=3.0):
    f = []
    for b in an["boundaries"]:
        gap = b.get("audio_gap_ms")
        if gap is None:
            f.append(dict(kind="boundary_no_audio", ms=b["eng_switch_ms"], detail=f"engine moved to {b['to_title']!r} but no audio progress followed: {b}", file=os.path.basename(an["base"]) + ".log", line=b["line"]))
            continue
        if gap > limit_s * 1000:
            f.append(dict(kind="boundary_gap", ms=b["eng_switch_ms"], detail=f"{gap} ms of silence between songs ({b['from_title']!r} -> {b['to_title']!r})", file=os.path.basename(an["base"]) + ".log", line=b["line"]))
        v = b.get("new_vol_min_1.5s_to_4s")
        if v is not None and v < 0.9:
            f.append(dict(kind="boundary_low_volume", ms=b["eng_switch_ms"], detail=f"new song volume min {v} 1.5-4 s after its start", file=os.path.basename(an["base"]) + ".log", line=b["line"]))
    return f


def sc_B(out, T, name="B_end_of_track", idx=1):
    cap = L.Capture(out, name)
    try:
        p = start_song(T[idx], 6)
        uitrace(60)
        time.sleep(1)
        d = p["track"]["durationMs"]
        ref = float(media_state()["own"])
        L.cmd("seek", ms=d - 8000)
        time.sleep(26)
        stop_check(cap)
    finally:
        an = finish(cap, "B")
    f = failures(an) + boundary_failures(an)
    if not an["boundaries"]:
        f.append(dict(kind="no_song_change", detail="no song change after the seek to duration-8 s", file="B_end_of_track.log"))
    return dict(name=name, fail=f, key=dict(duration_ms=d, boundaries=an["boundaries"]), analysis=an)


def sc_C(out, T, secs=250):
    cap = L.Capture(out, "C_long_play")
    try:
        p = start_song(T[2], 4)
        uitrace(secs + 15)
        d = p["track"]["durationMs"]
        L.cmd("seek", ms=max(0, d - 100000))
        nap(secs)
        stop_check(cap)
    finally:
        an = finish(cap, "C")
    f = failures(an) + boundary_failures(an)
    if not an["boundaries"]:
        f.append(dict(kind="no_song_change", detail="no song boundary crossed in the run", file="C_long_play.log"))
    return dict(name="C long play across a boundary", fail=f, key=dict(duration_ms=d, boundaries=an["boundaries"], seconds=secs), analysis=an)


def sc_D(out, T, seed=20261020):
    rnd = random.Random(seed)
    cap = L.Capture(out, "D_commands")
    plan = []
    try:
        start_song(T[3], 8)
        uitrace(330)
        own = float(media_state()["own"])
        for i in range(10):
            gap = round(rnd.uniform(1, 8), 1)
            plan.append(gap)
            L.cmd("pause"); time.sleep(gap)
            L.cmd("resume"); time.sleep(round(rnd.uniform(2.5, 5), 1))
        courtesy(); stop_check(cap)
        L.cmd("next"); time.sleep(6)
        L.cmd("next"); time.sleep(6)
        L.cmd("previous"); time.sleep(0.9)     # >3 s into the song: restart of the same song
        L.cmd("previous"); time.sleep(6)       # right after: the song before
        for burst in range(2):
            for _ in range(3):
                L.cmd("swipenext"); time.sleep(0.15)
            time.sleep(9)
        p = pt.probe()
        pos, d = p["pos"] or 0, p["track"]["durationMs"]
        L.cmd("seek", ms=min(pos + 30000, d - 30000)); time.sleep(5)
        L.cmd("seek", ms=max(5000, min(pos + 10000, d - 40000))); time.sleep(5)
        L.cmd("seek", ms=10000); time.sleep(6)
        stop_check(cap)
        final = media_state()
    finally:
        an = finish(cap, "D", ref=1.0)
    f = failures(an) + fade_failures(an, "D_commands")
    if not (final["volume"] >= 0.98 * final["own"] and not final["paused"]):
        f.append(dict(kind="final_volume", detail=f"final media.state {final}", file="D_commands.log"))
    cm = an["cmd_metrics"]
    g = lambda n, k: [m.get(k) for m in cm if m["cmd"] == n]
    return dict(name="D pause/resume cycles, next/previous, swipenext bursts, seeks", fail=f, key=dict(
        final=final, pause_gaps_s=plan, resume_to_audio_ms=g("resume", "to_audio_ms"), resume_to_full_after_audio_ms=g("resume", "to_full_after_audio_ms"),
        resume_vol_min_after=g("resume", "vol_min_1s_to_8s"), next_to_eng_uri_ms=g("next", "to_eng_uri_change_ms"), next_to_audio_ms=g("next", "to_audio_ms"),
        previous_to_audio_ms=g("previous", "to_audio_ms"), previous_to_eng_uri_ms=g("previous", "to_eng_uri_change_ms"), previous_to_restart_ms=g("previous", "to_eng_restart_ms"),
        swipenext_to_eng_uri_ms=g("swipenext", "to_eng_uri_change_ms"), swipenext_to_ui_uri_ms=g("swipenext", "to_ui_uri_change_ms"), swipenext_to_audio_ms=g("swipenext", "to_audio_ms"),
        seek_eng_pos_after=g("seek", "eng_pos_after")), analysis=an)


def wait_song_change(uri, timeout):
    """Polls the page until the current song is not [uri]; -> the probe or None."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            p = pt.probe(patience=3)
            if p["track"]["uri"] != uri and p["track"]["uri"]:
                return p
        except Exception:
            pass
        time.sleep(1.0)
    return None


def xfade_failures(an, own=1.0):
    """The crossfade scenario: the 3 natural ends (2 with crossfade, 1 without) of X_crossfade."""
    # the ramp itself is not a "low volume" anomaly: those inside a crossfade window (log line -> switch) are expected
    f = [x for x in failures(an) if not (x["kind"] == "low_volume" and any(c["log"] and c["log"][0][0] - 500 <= x["ms"] <= c["switch_ms"] + 500 for c in an["crossfades"]))]
    xf = an["crossfades"]
    if len(xf) < 3:
        f.append(dict(kind="boundaries_missing", detail=f"{len(xf)} natural song changes seen, 3 expected", file="X_crossfade.log"))
    for i, x in enumerate(xf):
        tag = "crossfade" if i < 2 else "crossfade 0"
        gap = x.get("audio_gap_ms")
        if gap is not None and gap > 1000 and i < 2:
            f.append(dict(kind="xfade_gap", ms=x["switch_ms"], detail=f"{tag}: {gap} ms with no audio progress at the switch", file="X_crossfade.log", line=x["line"]))
        if x["level_3_6s_after_min"] is not None and x["level_3_6s_after_min"] < 0.9 * own:
            f.append(dict(kind="xfade_stuck_low", ms=x["switch_ms"], detail=f"{tag}: level {x['level_3_6s_after_min']} 3-6 s after the new song started", file="X_crossfade.log", line=x["line"]))
        if i < 2 and x["longest_below_0_1_ms"] > 1500:
            f.append(dict(kind="xfade_long_low", ms=x["switch_ms"], detail=f"{tag}: {x['longest_below_0_1_ms']} ms below volume 0.1", file="X_crossfade.log", line=x["line"]))
        if i >= 2 and x["vol_min_overall"] < 0.9 * own:
            f.append(dict(kind="xfade_off_still_fading", ms=x["switch_ms"], detail=f"crossfade 0: volume fell to {x['vol_min_overall']} around the switch", file="X_crossfade.log", line=x["line"]))
    return f


def sc_X(out, T, secs=6):
    """Crossfade: [secs] s set, 2 natural song ends (seek to duration - 15 s), then crossfade 0 and a third end."""
    cap = L.Capture(out, "X_crossfade")
    notes = {}
    try:
        L.adb("shell", "am", "broadcast", "-a", "app.myspoti.TEST", "-p", PKG, "--es", "cmd", "crossfade", "--ei", "secs", str(secs))
        time.sleep(1)
        p = start_song(T[8], 5)
        uitrace(190)
        notes["own"] = media_state()["own"]
        ends = []
        for i in range(2):
            d = p["track"]["durationMs"]
            cur = p["track"]["uri"]
            L.cmd("seek", ms=d - 15000)
            nxt = wait_song_change(cur, 40)
            ends.append(dict(i=i, ok=bool(nxt), media_after=(nxt or {}).get("media")))
            if not nxt:
                break
            time.sleep(8)
            p = pt.probe()
            stop_check(cap)
        notes["crossfade_on_ends"] = ends
        notes["state_after_2"] = media_state()
        L.adb("shell", "am", "broadcast", "-a", "app.myspoti.TEST", "-p", PKG, "--es", "cmd", "crossfade", "--ei", "secs", "0")
        time.sleep(2)
        d = p["track"]["durationMs"]
        cur = p["track"]["uri"]
        L.cmd("seek", ms=d - 10000)
        nxt = wait_song_change(cur, 30)
        notes["crossfade_0_end_ok"] = bool(nxt)
        time.sleep(8)
        notes["final_state"] = media_state()
        stop_check(cap)
    finally:
        try:
            L.adb("shell", "am", "broadcast", "-a", "app.myspoti.TEST", "-p", PKG, "--es", "cmd", "crossfade", "--ei", "secs", "0")
        except Exception:
            pass
        an = finish(cap, "X")
    f = xfade_failures(an, notes.get("own") or 1)
    xf = an["crossfades"]
    return dict(name="X crossfade 6 s, 2 natural ends, then 0", fail=f, key=dict(notes=notes, crossfades=[{k: v for k, v in x.items() if k != "timeline"} for x in xf],
                                                                                 timelines=[x["timeline"] for x in xf]), analysis=an)


def sc_H(out, T, wait=180):
    """Headless robustness: pause, wait [wait] s (the service leaves the foreground 60 s after a pause), then a `resume` broadcast."""
    cap = L.Capture(out, "H_headless", sampler=False)
    notes = {"timeline": []}
    try:
        start_song(T[9], 6)
        notes["pid_at_pause"] = pidof()
        L.cmd("pause")
        t0 = time.time()
        time.sleep(3)
        while time.time() - t0 < wait:
            sv = L.adb("shell", "dumpsys", "activity", "services", PKG, timeout=30)
            fg = re.findall(r"isForeground=(\w+)", sv)
            notes["timeline"].append(dict(after_s=round(time.time() - t0), pid=pidof(), isForeground=fg[:2]))
            time.sleep(max(0, 15 - 0.5))
        notes["pid_before_resume"] = pidof()
        t1 = time.time()
        phone_ms = L.phone_now_ms()
        L.cmd("resume")
        notes["resume_sent_phone_ms"] = phone_ms
        notes["probe_timeline"] = []
        played = None
        last_pos = None
        while time.time() - t1 < 60:
            try:
                p = pt.evaluate(pt.PROBE, timeout=6)
                pos = p.get("pos")
                row = dict(dt=round(time.time() - t1, 1), paused=p.get("paused"), buffering=p.get("buffering"), pos=None if pos is None else int(pos), pid=pidof())
            except Exception as e:
                row = dict(dt=round(time.time() - t1, 1), err=str(e)[:60], pid=pidof())
                p = None
            notes["probe_timeline"].append(row)
            if p and p.get("paused") is False and not p.get("buffering") and (p.get("media") or {}).get("paused") is False:
                if last_pos is not None and (p.get("pos") or 0) > last_pos + 400:
                    played = row["dt"]
                    break
                last_pos = p.get("pos") or 0
            time.sleep(0.4)
        notes["resume_to_playing_s"] = played
        notes["pid_after_resume"] = pidof()
        if played is None:
            # the process had gone, or the engine is not running: the service started again, as the harness does
            notes["fallback"] = "headless service start + resume"
            pt.launch()
            time.sleep(20)
            pt.wait_ready(60)
            L.cmd("resume")
            time.sleep(10)
            notes["after_fallback"] = pt.probe()
        stop_check(cap)
    finally:
        cap.stop()
    f = []
    if notes.get("resume_to_playing_s") is None:
        f.append(dict(kind="headless_resume_failed", detail=f"no playback within 60 s of the resume broadcast; pid before {notes.get('pid_before_resume')!r}, after {notes.get('pid_after_resume')!r}", file="H_headless.log"))
    if not notes.get("pid_before_resume"):
        f.append(dict(kind="process_died", detail=f"process gone after {wait} s of pause (pid at pause {notes.get('pid_at_pause')})", file="H_headless.log"))
    return dict(name="H headless: pause 3 min then resume", fail=f, key=notes)


def sc_F(out):
    log = os.path.join(out, "F_qa_battery_all.txt")
    env = dict(os.environ, PT_SAFE="1")
    with open(log, "w") as lf:
        pr = subprocess.Popen([sys.executable, "-u", os.path.join(ROOT, "tools/qa.py"), "--battery", "all"], stdout=lf, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
        paused = False
        while pr.poll() is None:
            time.sleep(15)
            act = user_active()
            if act and not paused:
                pr.send_signal(signal.SIGSTOP); paused = True
                lf.write("\n[pilot] user on the phone: battery suspended\n"); lf.flush()
            elif not act and paused:
                time.sleep(120)
                if not user_active():
                    pr.send_signal(signal.SIGCONT); paused = False
                    lf.write("[pilot] battery resumed\n"); lf.flush()
    txt = open(log).read()
    f = []
    for m in re.finditer(r"^(FAIL .*)$", txt, re.M):
        f.append(dict(kind="battery", detail=m.group(1)[:300], file="F_qa_battery_all.txt"))
    pm = re.search(r'"passed": (\d+),\s*"total": (\d+)', txt)
    summ = [f"{pm[1]}/{pm[2]} passed"] if pm else re.findall(r"(\d+/\d+ passed.*)", txt)
    l429 = re.findall(r'"licence_429": (\d+)', txt)
    if l429 and int(l429[-1]) >= 6:
        f.append(dict(kind="licence_429", detail=f"{l429[-1]} licence 429", file="F_qa_battery_all.txt"))
    return dict(name="F qa.py --battery all", fail=f, key=dict(summary=summ[-1] if summ else None, licence_429=l429[-1] if l429 else None,
                                                              passes=re.findall(r"^PASS (\S+)", txt, re.M)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    ap.add_argument("--only", default="A1,A2,B,C,D,F")  # X (crossfade) and H (headless robustness) on request
    ap.add_argument("--long-pause", type=int, default=400)
    a = ap.parse_args()
    out = a.out or os.path.join(ROOT, "tools/qa-results", "pilot-" + time.strftime("%Y%m%d-%H%M"))
    os.makedirs(out, exist_ok=True)
    resf = os.path.join(out, "results.json")
    results = json.load(open(resf)) if os.path.exists(resf) else {}
    courtesy()
    ensure_app()
    T = tracks()
    print("tracks:", len(T), flush=True)
    jobs = {"A1": lambda: sc_A1(out, T), "A2": lambda: sc_A2(out, T, a.long_pause), "B": lambda: sc_B(out, T), "B2": lambda: sc_B(out, T, "B2_end_of_track", 4), "B3": lambda: sc_B(out, T, "B3_end_of_track", 6), "A2b": lambda: sc_A2(out, T, a.long_pause, "A2b_long_pause", 5), "C": lambda: sc_C(out, T),
            "D": lambda: sc_D(out, T), "F": lambda: sc_F(out), "X": lambda: sc_X(out, T), "H": lambda: sc_H(out, T)}
    for name in [x for x in a.only.split(",") if x]:
        courtesy()
        t0 = time.time()
        print(f"== {name} ...", flush=True)
        try:
            r = jobs[name]()
        except Stop as e:
            results[name] = dict(name=name, stopped=str(e))
            print("STOPPED:", e, flush=True)
            json.dump(results, open(resf, "w"), indent=1, default=str)
            break
        except Exception as e:
            r = dict(name=name, fail=[dict(kind="pilot_error", detail=repr(e)[:300], file="")], key={})
        r.pop("analysis", None)
        r["seconds"] = round(time.time() - t0)
        results[name] = r
        json.dump(results, open(resf, "w"), indent=1, default=str)
        print(f"== {name}: {'PASS' if not r['fail'] else 'FAIL (' + str(len(r['fail'])) + ')'} in {r['seconds']} s", flush=True)
        for x in r["fail"][:6]:
            print("   ", x["kind"], x["detail"][:160], x.get("file"), x.get("line"), flush=True)
        time.sleep(3)
    print("out:", out)


if __name__ == "__main__":
    main()
