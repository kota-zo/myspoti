"""Player bridge tests against a real, logged-in Web Player in desktop Chrome.

Needs Google Chrome (it ships Widevine) started with a dedicated profile:
    google-chrome --user-data-dir=<profile> --remote-debugging-port=9222
and Playwright (`pip install playwright`). Sign in once in that window, then run:
    python run_bridge_tests.py [--cdp http://localhost:9222]

The host side mirrors Android: page -> host messages go through one binding
(`__mspHostPost`), host -> page commands through `Myspoti.call`.
Every test restores what it changes (volume, queue, likes, pause state).
"""
import argparse, asyncio, itertools, json, sys, time
from pathlib import Path
from playwright.async_api import async_playwright

BRIDGE = Path(__file__).resolve().parents[1] / "src/main/resources/bridge/player-bridge.js"
TEST_CONTEXT = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"  # Today's Top Hits


class Host:
    def __init__(self, page, cdp):
        self.page, self.cdp = page, cdp
        self.ids = itertools.count(1)
        self.pending = {}
        self.states = []
        self.queues = 0
        self.ready = asyncio.Event()

    def on_message(self, raw):
        msg = json.loads(raw)
        kind = msg["type"]
        if kind == "ready":
            self.ready.set()
        elif kind == "state":
            self.states.append(msg["state"])
        elif kind == "queue":
            self.queues += 1
        elif kind == "result":
            fut = self.pending.pop(msg["id"], None)
            if fut and not fut.done():
                fut.set_result(msg)
        elif kind == "error":
            print("bridge error:", msg["error"])

    async def attach(self):
        await self.cdp.send("Runtime.enable")
        await self.cdp.send("Runtime.addBinding", {"name": "__mspHostPost"})
        self.cdp.on("Runtime.bindingCalled", lambda e: e["name"] == "__mspHostPost" and self.on_message(e["payload"]))
        # A reload drops window.Myspoti: start fresh so the new binding is used.
        await self.page.reload()
        await self.page.wait_for_load_state("domcontentloaded")
        await self.page.evaluate(BRIDGE.read_text())
        await asyncio.wait_for(self.ready.wait(), 60)

    async def call(self, method, **args):
        cid = next(self.ids)
        fut = asyncio.get_running_loop().create_future()
        self.pending[cid] = fut
        # Fire and forget, like Android's evaluateJavascript: the answer comes back as a message.
        await self.page.evaluate(f"window.Myspoti.call({cid}, {json.dumps(method)}, {json.dumps(args)}).catch(() => {{}})")
        msg = await asyncio.wait_for(fut, 20)
        if not msg["ok"]:
            raise AssertionError(f"{method} failed: {msg['error']}")
        return msg["value"]

    async def wait_state(self, pred, timeout=15):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            s = await self.call("player.state")
            if s and pred(s):
                return s
            await asyncio.sleep(0.3)
        raise AssertionError("state condition not reached")


async def _wait_queue(self, pred, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        hit = pred(await self.call("player.queue"))
        if hit:
            return hit
        await asyncio.sleep(0.3)
    raise AssertionError("queue condition not reached")


Host.wait_queue = _wait_queue


def position(s):
    """Live position, extrapolated from the snapshot like the UI will."""
    if s["isPaused"]:
        return s["positionMs"]
    return s["positionMs"] + (time.time() * 1000 - s["timestamp"]) * s["speed"]


async def test_ready_and_state(h):
    v = await h.call("bridge.version")
    assert v == 1, f"version {v!r}"
    s = await h.call("player.state")
    assert s is None or "isPaused" in s, s  # null until a playback session exists


async def test_token(h):
    t = await h.call("token.get")
    assert t["accessToken"] and t["expiresAtMs"] > time.time() * 1000


async def test_play_context(h):
    await h.call("player.play", contextUri=TEST_CONTEXT, index=0)
    s = await h.wait_state(lambda s: s["contextUri"] == TEST_CONTEXT and not s["isPaused"] and s["track"])
    await asyncio.sleep(4)
    s = await h.call("player.state")
    assert position(s) > 1000, f"not advancing: {s['positionMs']}"


async def test_pause_resume(h):
    await h.call("player.pause")
    s = await h.wait_state(lambda s: s["isPaused"])
    p = s["positionMs"]
    await asyncio.sleep(2)
    assert abs((await h.call("player.state"))["positionMs"] - p) < 500
    await h.call("player.resume")
    await h.wait_state(lambda s: not s["isPaused"])


async def test_seek(h):
    s = await h.call("player.state")
    target = int(s["durationMs"] * 0.5)
    await h.call("player.seek", positionMs=target)
    s = await h.wait_state(lambda s: abs(position(s) - target) < 3000)


async def test_next_previous(h):
    first = (await h.call("player.state"))["track"]["uri"]
    await h.call("player.next")
    s = await h.wait_state(lambda s: s["track"]["uri"] != first)
    await h.call("player.previous")  # restarts the track, or goes back when near the start
    await asyncio.sleep(1)
    s = await h.call("player.state")
    if s["track"]["uri"] != first:
        await h.wait_state(lambda s: s["restrictions"].get("canSkipPrevious"), timeout=5)
        await h.call("player.previous")
    await h.wait_state(lambda s: s["track"]["uri"] == first)


async def test_queue(h):
    q = await h.call("player.queue")
    candidate = q["nextUp"][3]["uri"]
    before = h.queues
    await h.call("queue.add", uris=[candidate])
    added = await h.wait_queue(lambda q: [t for t in q["queued"] if t["uri"] == candidate])
    assert h.queues > before, "no queue event"
    await h.call("queue.remove", items=[{"uri": t["uri"], "uid": t["uid"]} for t in added])
    await h.wait_queue(lambda q: not [t for t in q["queued"] if t["uri"] == candidate] or None)


async def test_volume(h):
    old = await h.call("volume.get")
    await h.call("volume.set", level=0.3)
    await asyncio.sleep(0.5)
    assert abs(await h.call("volume.get") - 0.3) < 0.02
    await h.call("volume.set", level=old)


async def test_library(h):
    uri = (await h.call("player.state"))["track"]["uri"]
    [was] = await h.call("library.contains", uris=[uri])
    await (h.call("library.remove", uris=[uri]) if was else h.call("library.add", uris=[uri]))
    await asyncio.sleep(1.5)
    [now] = await h.call("library.contains", uris=[uri])
    assert now != was, "like did not toggle"
    await (h.call("library.add", uris=[uri]) if was else h.call("library.remove", uris=[uri]))
    await asyncio.sleep(1.5)
    assert (await h.call("library.contains", uris=[uri]))[0] == was


SEARCH_TRACKS = "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751"


async def test_gql_search(h):
    r = await h.call("gql.query", name="searchTracks", hash=SEARCH_TRACKS, variables={
        "searchTerm": "stromae", "offset": 0, "limit": 5, "numberOfTopResults": 5,
        "includeAudiobooks": True, "includeAuthors": True, "includePreReleases": False,
        "includeAlbumPreReleases": False, "includeEpisodeContentRatingsV2": True})
    items = r["data"]["searchV2"]["tracksV2"]["items"]
    assert len(items) == 5 and all(i["item"]["data"]["uri"].startswith("spotify:track:") for i in items)


async def test_services(h):
    services = await h.call("service.list")
    for name in ("PlaylistAPI", "RootlistAPI", "LibraryAPI", "ShowAPI", "ConnectDevicesAPI", "UserAPI"):
        assert name in services, f"{name} missing"
    meta = await h.call("service.call", service="PlaylistAPI", method="getMetadata", args=[TEST_CONTEXT])
    assert meta["uri"] == TEST_CONTEXT and meta.get("name"), meta
    contents = await h.call("service.call", service="PlaylistAPI", method="getContents",
                            args=[TEST_CONTEXT, {"limit": 5, "offset": 0}])
    assert len(contents["items"]) == 5 and contents["totalLength"] >= 5
    root = await h.call("service.call", service="RootlistAPI", method="getContents", args=[])
    assert "items" in root
    user = await h.call("service.call", service="UserAPI", method="getUser", args=[])
    assert user.get("username")
    devices = await h.call("service.call", service="ConnectDevicesAPI", method="getDevices", args=[])
    assert isinstance(devices, list)


async def test_state_events(h):
    assert h.states, "no state event received"
    assert any(s and s["track"] for s in h.states)


async def test_playhead(h):
    """The playhead a state carries is the page's own, not a snapshot's extrapolated one.

    On a TV the page's state snapshot is seconds old, and every reader of it ran the position
    forward by that age (skipped songs, a bar that jumped). The bridge now re-stamps a snapshot
    that is stale or disagrees with the media element, and leaves a fresh one alone.
    """
    seen = len(h.states)
    await h.call("player.play")
    await asyncio.sleep(3)
    live = [s for s in h.states[seen:] if s and s.get("track")]
    assert live, "no state after a play"
    # What the page's own element says now, against what the last state said it would be.
    media = await h.call("media.state")
    assert media and media.get("time") is not None, "the page has no media element to read"
    s = live[-1]
    stamp = s.get("timestamp")
    assert isinstance(stamp, int) and stamp > 0, f"no timestamp on the state: {stamp}"
    age = int(time.time() * 1000) - stamp
    assert age < 1500, f"a playing state {age} ms old was pushed as fresh"
    assumed = s["positionMs"] + max(0, age)
    element = media["time"] * 1000
    # The state's age is real: its position run on by that age meets the element's clock (the
    # bridge's own agreement window is 2.5 s, the element moved while the call was on its way).
    assert abs(assumed - element) < 4000, f"state {assumed:.0f} ms vs element {element:.0f} ms"


TESTS = [test_ready_and_state, test_token, test_play_context, test_pause_resume, test_seek,
         test_next_previous, test_queue, test_volume, test_library, test_gql_search, test_services,
         test_state_events, test_playhead]


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cdp", default="http://localhost:9222")
    ap.add_argument("-k", help="only run tests whose name contains one of these (comma-separated)")
    opts = ap.parse_args()
    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(opts.cdp)
        ctx = browser.contexts[0]
        page = next((pg for pg in ctx.pages if "open.spotify.com" in pg.url), None)
        if page is None:
            page = await ctx.new_page()
            await page.goto("https://open.spotify.com/")
        await page.bring_to_front()
        h = Host(page, await ctx.new_cdp_session(page))
        await h.attach()
        failed = 0
        for t in TESTS:
            if opts.k and not any(k in t.__name__ for k in opts.k.split(",")):
                continue
            t0 = time.monotonic()
            try:
                await t(h)
                print(f"PASS {t.__name__} ({time.monotonic() - t0:.1f}s)")
            except Exception as e:
                failed += 1
                print(f"FAIL {t.__name__}: {type(e).__name__}: {e}")
        try:
            await h.call("player.pause")
        except Exception:
            pass
        print(f"{len(TESTS) - failed if not opts.k else '-'} passed, {failed} failed")
        sys.exit(1 if failed else 0)


asyncio.run(main())
