#!/usr/bin/env python3
"""Walks through the app's screens on a phone and fails on a crash, an ANR or the app leaving.

usage: tools/ui_smoke.py [--serial S] [--depth 2] [--max 120]

From each screen it opens every element that can be tapped (read from uiautomator), one by
one, then comes back. Never taps what could change the account, the library or the settings
(see DENY: like, follow, add, remove, delete, download, log out, switches...), nor anything
that leaves the app on purpose (share, open in browser). Debug build app.myspoti.dev.
"""
import argparse, os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import playback_test as t  # noqa: E402

PKG = t.PKG
# Words (English and French, the phone's languages) of elements never tapped.
DENY = re.compile(r"(log ?out|sign ?out|déconnex|se déconnecter|delete|supprim|remove|retir|effac|clear|reset|réinitialis|"
                  r"like|j'aime|aimé|follow|suivre|abonn|unfollow|ban|bloquer|ne pas jouer|add|ajout|download|télécharg|"
                  r"share|partag|report|signal|buy|achet|pay|donat|don\b|sponsor|ko-fi|github|discord|reddit|privacy|"
                  r"open in|ouvrir dans|navigateur|browser|spotify app|install|update|mettre à jour|quit|quitter|"
                  r"jam|appareil|device|connect|lecture aléatoire|shuffle|repeat|répét|minuteur|sleep|timer|"
                  r"vidéo|video|canvas|paroles|lyrics|enreg|record|notifications?$|"
                  # Messages: anything there can write to a real person (send, a GIF, a group, a request).
                  r"message|send|envoy|write to|écrire|group|gif|accept|decline|refuser)", re.I)


def adb(*a, check=False):
    return t.adb(*a, check=check)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/msp-ui.xml")
    xml = adb("shell", "cat", "/sdcard/msp-ui.xml")
    out = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        get = lambda k: (re.search(fr' {k}="([^"]*)"', n) or [None, ""])[1]
        if get("package") != PKG or get("clickable") != "true" or get("checkable") == "true":
            continue
        label = (get("text") or get("content-desc")).strip()
        b = re.findall(r"\d+", get("bounds"))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        if x2 - x1 < 8 or y2 - y1 < 8:
            continue
        out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def foreground():
    return PKG in adb("shell", "dumpsys", "window", "displays").split("mCurrentFocus", 1)[-1][:200]


class Smoke:
    def __init__(self, log, max_taps):
        self.log = log
        self.max = max_taps
        self.taps = 0
        self.seen = set()
        self.problems = []

    def check(self, what):
        bad = [l for l in self.log.since(self.mark) if "FATAL EXCEPTION" in l or f"ANR in {PKG}" in l or "AndroidRuntime" in l]
        if bad:
            self.problems.append(f"{what}: " + " | ".join(bad[:3]))
        if "Application Error" in adb("shell", "dumpsys", "window", "displays"):
            self.problems.append(f"{what}: crash dialog")
            adb("shell", "input", "keyevent", "4")

    def back_to(self, screen_sig):
        for _ in range(3):
            if not foreground():
                t.launch()
                time.sleep(3)
            if signature() == screen_sig:
                return True
            adb("shell", "input", "keyevent", "4")
            time.sleep(1.2)
        return signature() == screen_sig

    def walk(self, depth, path):
        here = signature()
        for label, x, y in nodes():
            if self.taps >= self.max:
                return
            key = (here, label)
            if not label or key in self.seen or DENY.search(label):
                continue
            self.seen.add(key)
            self.mark = self.log.mark()
            self.taps += 1
            print(f"  [{self.taps}] {' > '.join(path + [label])[:110]}")
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1.8)
            self.check(" > ".join(path + [label]))
            if not foreground():
                self.problems.append(f"{' > '.join(path + [label])}: the app left the screen")
                t.launch()
                time.sleep(3)
            elif depth > 1 and signature() != here:
                self.walk(depth - 1, path + [label])
            if not self.back_to(here):
                return


def signature():
    """A screen, as the set of its tappable labels."""
    return tuple(sorted({l for l, _, _ in nodes() if l}))[:40]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--max", type=int, default=120)
    a = ap.parse_args()
    t.SERIAL = a.serial
    adb("shell", "input", "keyevent", "224")
    if "mDreamingLockscreen=true" in adb("shell", "dumpsys", "window") or "isKeyguardShowing=true" in adb("shell", "dumpsys", "window", "policy"):
        sys.exit("unlock the phone first")
    log = t.Log()
    t.launch()
    time.sleep(4)
    s = Smoke(log, a.max)
    s.mark = log.mark()
    s.walk(a.depth, [])
    s.check("end")
    log.stop(os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures", "ui_smoke.log"))
    print(f"{s.taps} taps")
    for p in s.problems:
        print("PROBLEM", p)
    sys.exit(1 if s.problems else 0)


if __name__ == "__main__":
    main()
