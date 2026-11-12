#!/usr/bin/env python3
"""Evaluate JavaScript in the engine's WebView over the Chrome DevTools protocol (debug builds).

usage: tools/cdp.py [--serial SERIAL] 'JS expression'
Forwards localabstract:webview_devtools_remote_<engine pid> to tcp:9223, then runs
Runtime.evaluate (awaitPromise, returnByValue) on the open.spotify.com page.
No third-party dependencies: minimal WebSocket client below.
"""
import argparse, base64, json, os, socket, struct, subprocess, sys, urllib.request

ADB = os.environ.get("ADB", os.path.expanduser("~/android-sdk/platform-tools/adb"))
PORT = 9223


def adb(serial, *args):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()


def ws_connect(url):
    host_port, path = url[len("ws://"):].split("/", 1)
    host, port = host_port.split(":")
    s = socket.create_connection((host, int(port)), timeout=150)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET /{path} HTTP/1.1\r\nHost: {host_port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += s.recv(1)
    if b" 101 " not in resp.split(b"\r\n")[0]:
        raise SystemExit("websocket handshake failed: " + resp.decode(errors="replace"))
    return s


def ws_send(s, text):
    data = text.encode()
    header = bytes([0x81])
    mask = os.urandom(4)
    n = len(data)
    if n < 126:
        header += bytes([0x80 | n])
    elif n < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", n)
    s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def ws_recv(s):
    def read(n):
        buf = b""
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return buf
    payload = b""
    while True:
        b1, b2 = read(2)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", read(8))[0]
        payload += read(n)
        if b1 & 0x80:
            return payload.decode(errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--trace", type=float, default=0, help="seconds of Network tracing after evaluating expr")
    ap.add_argument("--bodies", default="", help="regex: print request/response bodies for matching URLs (trace mode)")
    ap.add_argument("--headers", action="store_true",
                    help="trace mode: print the headers of every spotify.com request, as the browser really "
                         "sends them (Network.requestWillBeSentExtraInfo), with the tells that matter: "
                         "X-Requested-With (the app's own package name), Sec-Fetch-*, client hints, language")
    ap.add_argument("--click", default="", help="CSS selector: dispatch a trusted mouse click on its center")
    ap.add_argument("expr", nargs="?", default="0")
    a = ap.parse_args()
    ps = adb(a.serial, "shell", "ps", "-A", "-o", "PID,NAME")
    unix = adb(a.serial, "shell", "cat", "/proc/net/unix")
    pid = next((l.split()[0] for l in ps.splitlines()[1:] if l.split()[-1] in ("app.orpheon.beta", "app.orpheon:engine", "app.orpheon.beta.dev", "app.orpheon")
                and f"webview_devtools_remote_{l.split()[0]}" in unix), "")
    if not pid:
        raise SystemExit("engine process not running")
    adb(a.serial, "forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid.split()[0]}")
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
    # The engine page is the one attached to the 1280x800 virtual display, not a login WebView.
    cands = [p for p in pages if p.get("type") == "page" and "open.spotify.com" in p.get("url", "")]
    page = next((p for p in cands if '"height":800' in p.get("description", "")), cands[0] if cands else None)
    if not page:
        raise SystemExit("no open.spotify.com page: " + json.dumps([p.get("url") for p in pages]))
    s = ws_connect(page["webSocketDebuggerUrl"])
    if a.click:
        sel = json.dumps(a.click)
        ws_send(s, json.dumps({"id": 2, "method": "Runtime.evaluate", "params": {"returnByValue": True,
            "expression": f"(()=>{{const e=document.querySelector({sel}); if(!e) return null; e.scrollIntoView({{block:'center'}}); const r=e.getBoundingClientRect(); return [r.x+r.width/2, r.y+r.height/2]}})()"}}))
        while True:
            msg = json.loads(ws_recv(s))
            if msg.get("id") == 2:
                xy = msg.get("result", {}).get("result", {}).get("value")
                break
        if not xy:
            raise SystemExit("selector not found: " + a.click)
        for i, t in enumerate(("mousePressed", "mouseReleased")):
            ws_send(s, json.dumps({"id": 3 + i, "method": "Input.dispatchMouseEvent", "params": {
                "type": t, "x": xy[0], "y": xy[1], "button": "left", "clickCount": 1}}))
        print("clicked", a.click, "at", xy)
    if a.trace:
        ws_send(s, json.dumps({"id": 99, "method": "Network.enable", "params": {}}))
    ws_send(s, json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
        "expression": a.expr, "awaitPromise": not a.trace, "returnByValue": True}}))
    if a.trace:
        import time
        end = time.time() + a.trace
        s.settimeout(1)
        reqs = {}
        import re as _re
        body_re = _re.compile(a.bodies) if a.bodies else None
        pending_bodies = {}
        next_id = 1000
        while time.time() < end:
            try:
                msg = json.loads(ws_recv(s))
            except (socket.timeout, TimeoutError):
                continue
            m, p = msg.get("method"), msg.get("params", {})
            if m == "Network.requestWillBeSent":
                r = p["request"]
                reqs[p["requestId"]] = r["method"] + " " + r["url"].split("?")[0][:200]
                body = r.get("postData")
                if body and body_re and body_re.search(r["url"]):
                    print("  >>", reqs[p["requestId"]], body[:2000])
            elif m == "Network.requestWillBeSentExtraInfo":
                if not a.headers:
                    continue
                h = {k.lower(): v for k, v in (p.get("headers") or {}).items()}
                url = reqs.get(p.get("requestId"), "")
                if "spotify.com" not in url and "scdn.co" not in url:
                    continue
                shown = [f"xrw={'YES' if 'x-requested-with' in h else 'no'}"]
                for k in ("x-requested-with", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
                          "accept-language", "referer", "origin", "sec-ch-ua", "sec-ch-ua-mobile",
                          "sec-ch-ua-platform", "client-token", "spotify-app-version", "app-platform"):
                    if k in h:
                        shown.append(f"{k}={h[k][:60]}")
                shown.append("auth=" + ("yes" if "authorization" in h else "no"))
                print("HDR", url.split("?")[0][:95], "|", " ".join(shown))
            elif m == "Network.responseReceived":
                print("HTTP", p["response"]["status"], reqs.get(p["requestId"], p["response"]["url"][:110]))
            elif m == "Network.loadingFinished" and body_re and body_re.search(reqs.get(p["requestId"], "")):
                next_id += 1
                pending_bodies[next_id] = reqs.get(p["requestId"])
                ws_send(s, json.dumps({"id": next_id, "method": "Network.getResponseBody", "params": {"requestId": p["requestId"]}}))
            elif msg.get("id") in pending_bodies:
                print("  <<", pending_bodies.pop(msg["id"]), (msg.get("result", {}).get("body") or "")[:2000])
            elif m == "Network.loadingFailed":
                print("FAIL", p.get("errorText"), reqs.get(p["requestId"], ""))
            elif m in ("Network.webSocketFrameReceived", "Network.webSocketFrameSent"):
                d = p["response"]["payloadData"]
                print("WS" + ("<" if m.endswith("Received") else ">"), d[:260].replace("\n", " "))
            elif msg.get("id") == 1:
                print("EVAL:", json.dumps(msg.get("result", {}).get("result", {}).get("value"))[:300])
        return
    while True:
        msg = json.loads(ws_recv(s))
        if msg.get("id") == 1:
            r = msg.get("result", {})
            if "exceptionDetails" in r:
                print("EXCEPTION:", json.dumps(r["exceptionDetails"], indent=1)[:2000])
            else:
                print(json.dumps(r.get("result", {}).get("value"), indent=1, ensure_ascii=False))
            break


if __name__ == "__main__":
    main()
