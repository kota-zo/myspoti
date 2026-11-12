#!/usr/bin/env python3
"""One command, one short summary: the capture -> fix -> retest loop of a change.

usage: tools/qa.py [--build] [--unit] [--battery all|light|name,name] [--perf] [--notif N]
                   [--save NAME] [--compare NAME] [--serial S]

  --build      assemble the debug APK and install it (the app restarts)
  --unit       unit tests of every module (count and failures)
  --battery    phone playback scenarios (tools/playback_test.py); "light" = the fast ones
  --perf       player screen, 12 s: frames, late frames, slow UI frames, app/renderer CPU
  --notif N    N skips; after each, is there artwork in the media notification?
  --anim LABEL record the player opening and closing: a contact sheet of the frames
               (tools/qa-results/anim-LABEL.png, 12 per second) and the late frames
  --notif-mode plain|rapid|screenoff|cold  how the N rounds skip (default plain): rapid = 5 skips
               in a burst, screenoff = skips with the screen asleep, cold = app killed and restarted
  --save NAME  keep this run's numbers in tools/qa-results/NAME.json (ignored by git)
  --compare NAME  print this run next to a saved one

Every phone step also counts Spotify's HTTP 429 on audio licences: a run that triggers them
is stopped, since more runs would only make Spotify's limit on the test account last.
Debug build only; one driver at a time on the phone.
PT_HEADLESS=1: no activity, no input (the app starts as its service, media keys become the test receiver's
commands): see tools/playback_test.py. --perf and --anim need the screen and are refused then.
"""
import argparse, glob, json, os, re, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import playback_test as pt  # noqa: E402

PKG = "app.orpheon.beta.dev"
LIGHT = "play_playlist,next,rapid_next,rapid_swipe,seek,pause_resume,mute"
# A song of the test playlist without a Canvas: the player screen then shows only the bar moving.
STILL_TRACK = "spotify:track:0cIR7aXpYCI1eKXMglSMS7"


def sh(*a, check=False, timeout=600):
    return subprocess.run(list(a), capture_output=True, text=True, timeout=timeout, check=check).stdout


def adb(*a):
    return sh(pt.ADB, *(["-s", pt.SERIAL] if pt.SERIAL else []), *a)


def licence_429():
    return sum(1 for l in adb("logcat", "-d", "-s", "Myspoti:*").splitlines() if "http 429" in l and "widevine" in l)


def step_build(out):
    r = subprocess.run(["./gradlew", ":app:assembleDebug", "--console=plain", "-q"], cwd=os.path.join(ROOT, "base"),
                       capture_output=True, text=True)
    errors = [l for l in (r.stdout + r.stderr).splitlines() if l.startswith("e: ") and "Incremental" not in l and "Daemon" not in l]
    if r.returncode != 0 and errors:
        out["build"] = "FAILED: " + " | ".join(errors[:3])
        return False
    inst = adb("install", "-r", os.path.join(ROOT, "base/app/build/outputs/apk/debug/app-debug.apk"))
    out["build"] = "ok, installed" if "Success" in inst else "built, install failed: " + inst.strip()[-120:]
    return "Success" in inst


def step_unit(out):
    subprocess.run(["./gradlew", ":app:testDebugUnitTest", ":api-spotify:test", ":host-cdp:test",
                    ":host-android:testDebugUnitTest", "--console=plain", "-q"], cwd=os.path.join(ROOT, "base"),
                   capture_output=True, text=True)
    t = f = 0
    for p in glob.glob(os.path.join(ROOT, "base/*/build/test-results/*/*.xml")):
        m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(p).read())
        if m:
            t += int(m[1]); f += int(m[2]) + int(m[3])
    out["unit"] = {"tests": t, "failed": f}


def ensure_app():
    if pt.HEADLESS:
        # PT_HEADLESS=1: the service alone, only when the app is not running (see tools/playback_test.py)
        if not adb("shell", "pidof", PKG).strip():
            pt.launch()
            time.sleep(18)
        pt.wait_ready(60)
        return
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2)
    pt.wait_ready(60)


def step_battery(out, which):
    names = LIGHT if which == "light" else ("" if which == "all" else which)
    cmd = [sys.executable, "-u", os.path.join(ROOT, "tools/playback_test.py")] + (["--serial", pt.SERIAL] if pt.SERIAL else [])
    if names:
        cmd += ["--only", names]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=1500)
    lines = [l for l in r.stdout.splitlines() if l.startswith(("PASS", "FAIL"))]
    out["battery"] = {"passed": sum(l.startswith("PASS") for l in lines), "total": len(lines),
                      "failures": [l[:200] for l in lines if l.startswith("FAIL")]}


def cpu(seconds):
    """% of one core used by the app process and its WebView renderer over [seconds]."""
    app = adb("shell", "pidof", PKG).strip()
    ren = [l.split()[0] for l in adb("shell", "ps", "-A", "-o", "PID,NAME").splitlines() if "sandboxed_process0" in l]
    def ticks():
        d = {}
        for p in [app] + ren:
            st = adb("shell", "cat", f"/proc/{p}/stat").split()
            if len(st) > 15:
                d[p] = int(st[13]) + int(st[14])
        return d
    a = ticks(); time.sleep(seconds); b = ticks()
    use = {p: (b[p] - a[p]) / 100 / seconds * 100 for p in a if p in b}
    return {"app": round(use.get(app, 0), 1), "renderer": round(max([use[p] for p in use if p != app] or [0]), 1)}


def gfx():
    g = adb("shell", "dumpsys", "gfxinfo", PKG)
    num = lambda rx: int(re.search(rx, g).group(1)) if re.search(rx, g) else None
    return {"frames": num(r"Total frames rendered: (\d+)"), "late": num(r"Janky frames: (\d+)"),
            "slow_ui": num(r"Number Slow UI thread: (\d+)")}


def step_perf(out):
    ensure_app()
    pt.test("play", track=STILL_TRACK + "|", context=pt.PLAYLIST)
    time.sleep(6)
    w, h = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups())
    adb("shell", "input", "tap", str(w * 28 // 100), str(h * 868 // 1000))  # the mini player
    time.sleep(3)
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    c = cpu(12)
    out["perf_player"] = {**gfx(), "per_s": 12, **{f"cpu_{k}": v for k, v in c.items()}}
    adb("shell", "input", "keyevent", "4")


def notif_art():
    """(title, artwork width in px, 0 when none) of the app's media notification."""
    n = adb("shell", "dumpsys", "notification", "--noredact")
    blocks = [b for b in n.split("NotificationRecord(") if f"pkg={PKG}" in b[:200]]
    media = next((b for b in blocks if "MediaStyle" in b or "mediaSession" in b or "android.mediaSession" in b), None)
    if not media:
        return None, False
    title = re.search(r"android\.title=String \((.*?)\)", media)
    m = re.search(r"android\.largeIcon=Icon \(Icon\(typ=BITMAP size=(\d+)x(\d+)", media)
    return (title.group(1) if title else "?"), (int(m.group(1)) if m else 0)


def step_notif(out, n, mode="plain"):
    ensure_app()
    # A freshly installed app restarts paused: no playback, no notification to measure.
    pt.test("resume")
    time.sleep(5)
    seen = []
    for i in range(n):
        early = None
        if mode == "cold":
            adb("shell", "am", "force-stop", PKG)
            time.sleep(1)
            pt.launch()
            time.sleep(2.5)
            pt.wait_ready(60)
            pt.test("resume")
            time.sleep(5)
        elif mode == "rapid":
            pt.key([pt.NEXT] * 5)
            time.sleep(1.5)
            early = notif_art()[1]
            time.sleep(6)
        elif mode == "screenoff":
            pt.key(pt.SLEEP)
            time.sleep(2)
            pt.key(pt.NEXT)
            time.sleep(1.5)
            early = notif_art()[1]
            time.sleep(3)
        else:
            pt.key(pt.NEXT)
            time.sleep(4)
        p = pt.probe()
        title, art = notif_art()
        if mode == "screenoff":
            pt.key(pt.WAKE)
        seen.append({"page": (p.get("track") or {}).get("name"), "notif": title, "art": art, "early_art": early})
    out["notification"] = {"mode": mode, "skips": n, "missing_art": sum(1 for s in seen if not s["art"]),
                           "missing_art_early": sum(1 for s in seen if s["early_art"] == 0),
                           "art_px": sorted({s["art"] for s in seen if s["art"]}),
                           "title_mismatch": sum(1 for s in seen if s["page"] and s["notif"] and not s["notif"].startswith(s["page"])),
                           "samples": [s for s in seen if not s["art"] or (s["page"] and s["notif"] and not s["notif"].startswith(s["page"]))][:4]}


def step_anim(out, label):
    """Opens then closes the player while recording the screen: one contact sheet of the
    frames (tools/qa-results/anim-<label>.png) and the late frames of the two transitions."""
    # No playback needed (the mini player shows the last song): no audio licence is asked for,
    # so this runs even while Spotify limits the test account.
    ensure_app()
    time.sleep(3)
    w, h = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups())
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    rec = subprocess.Popen([pt.ADB, "-s", pt.SERIAL, "shell", "screenrecord", "--time-limit", "4",
                            "--size", f"{w // 2}x{h // 2}", "/sdcard/qa-anim.mp4"])
    time.sleep(0.8)
    adb("shell", "input", "tap", str(w * 28 // 100), str(h * 868 // 1000))  # open (the mini player)
    time.sleep(1.4)
    adb("shell", "input", "keyevent", "4")  # close
    rec.wait(timeout=15)
    out["anim_frames"] = gfx()
    keep = os.path.join(ROOT, "tools", "qa-results"); os.makedirs(keep, exist_ok=True)
    mp4 = os.path.join(keep, "anim.mp4")
    adb("pull", "/sdcard/qa-anim.mp4", mp4)
    frames = os.path.join(keep, "frames"); os.makedirs(frames, exist_ok=True)
    for old in glob.glob(os.path.join(frames, "*.png")):
        os.remove(old)
    # 12 frames a second from the tap (0.8 s) to past the close (~2.8 s): both transitions.
    subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", "0.75", "-t", "2.2", "-i", mp4, "-vf", "fps=12,scale=180:-1",
                    os.path.join(frames, "f%02d.png")], check=False)
    from PIL import Image
    shots = sorted(glob.glob(os.path.join(frames, "*.png")))
    if shots:
        ims = [Image.open(p) for p in shots]
        cols = 9; cw, ch = ims[0].size; rows = (len(ims) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * cw, rows * ch))
        for i, im in enumerate(ims):
            sheet.paste(im, ((i % cols) * cw, (i // cols) * ch))
        path = os.path.join(keep, f"anim-{label}.png")
        sheet.save(path)
        out["anim_sheet"] = path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--unit", action="store_true")
    ap.add_argument("--battery")
    ap.add_argument("--perf", action="store_true")
    ap.add_argument("--notif", type=int, default=0)
    ap.add_argument("--anim", help="label: record the player opening/closing (contact sheet + late frames)")
    ap.add_argument("--notif-mode", default="plain", choices=["plain", "rapid", "screenoff", "cold"])
    ap.add_argument("--save")
    ap.add_argument("--compare")
    a = ap.parse_args()
    pt.SERIAL = a.serial or next((l.split()[0] for l in sh(pt.ADB, "devices").splitlines()[1:] if l.endswith("device")), None)
    out = {}
    if pt.HEADLESS and (a.perf or a.anim):
        sys.exit("PT_HEADLESS=1: --perf and --anim tap the screen; refused")
    if a.unit:
        step_unit(out)
    if a.build and not step_build(out):
        print(json.dumps(out, ensure_ascii=False, indent=1)); sys.exit(1)
    phone = a.battery or a.perf or a.notif or a.anim
    if phone:
        adb("logcat", "-c")
    for name, run in (("battery", lambda: step_battery(out, a.battery)), ("perf", lambda: step_perf(out)),
                      ("notif", lambda: step_notif(out, a.notif, a.notif_mode)), ("anim", lambda: step_anim(out, a.anim))):
        if not {"battery": a.battery, "perf": a.perf, "notif": a.notif, "anim": a.anim}[name]:
            continue
        run()
        n = licence_429()
        if n >= 6:
            out["stopped"] = f"Spotify limits the test account ({n} licence 429): no more phone steps"
            break
    if phone:
        out["licence_429"] = licence_429()
    print(json.dumps(out, ensure_ascii=False, indent=1))
    keep = os.path.join(ROOT, "tools", "qa-results")
    if a.save:
        os.makedirs(keep, exist_ok=True)
        json.dump(out, open(os.path.join(keep, a.save + ".json"), "w"), ensure_ascii=False, indent=1)
    if a.compare:
        before = json.load(open(os.path.join(keep, a.compare + ".json")))
        print("--- before (" + a.compare + "):")
        print(json.dumps({k: before.get(k) for k in out}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
