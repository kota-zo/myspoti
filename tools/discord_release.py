#!/usr/bin/env python3
"""Announce a GitHub release of Myspoti on Discord (webhook), as one tidy embed.

usage: tools/discord_release.py TAG [--dry-run]
env:   DISCORD_WEBHOOK_URL (required unless --dry-run), GITHUB_TOKEN (optional, API rate limit),
       GITHUB_REPOSITORY (default kota-zo/myspoti)

The embed carries the release's notes (its CHANGELOG section, without GitHub's generated
part), a link to the release page and a direct link to the APK. No third-party packages.
"""
import json, os, re, sys, urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "kota-zo/myspoti")
GREEN = 0x1ED760
LOGO = f"https://raw.githubusercontent.com/{REPO}/main/docs/brand/myspoti-logo-512.png"
MAX_DESCRIPTION = 3900  # Discord allows 4096 in an embed description


def github(path):
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/{path}",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "myspoti-release-bot"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def clean_notes(body):
    """The CHANGELOG section only: GitHub's generated "What's Changed" / "Full Changelog" go."""
    body = (body or "").replace("\r\n", "\n")
    body = re.split(r"\n(?:## What's Changed|\*\*Full Changelog\*\*|## New Contributors)", body)[0]
    return body.strip()


def shorten(text, url):
    """Cut on a line boundary when too long, with a link to the full notes."""
    if len(text) <= MAX_DESCRIPTION:
        return text
    cut = text[:MAX_DESCRIPTION].rsplit("\n", 1)[0]
    return f"{cut}\n\n[Read the full notes]({url})"


def payload(release):
    tag = release["tag_name"]
    version = tag.removeprefix("v")
    url = release["html_url"]
    notes = clean_notes(release.get("body")) or "See the release page for details."
    # One APK per ABI: the link goes to arm64-v8a (most phones); older releases have a single one.
    apks = [a for a in release.get("assets", []) if a["name"].endswith(".apk")]
    apk = next((a for a in apks if a["name"].endswith("-arm64-v8a.apk")), apks[0] if apks else None)
    fields = []
    if apk:
        size = apk["size"] / 1_048_576
        fields.append({"name": "Download", "value": f"[{apk['name']}]({apk['browser_download_url']}) ({size:.1f} MB)", "inline": True})
    fields.append({"name": "Release", "value": f"[{tag} on GitHub]({url})", "inline": True})
    return {
        "username": "Myspoti",
        "avatar_url": LOGO,
        "content": f"**Myspoti {version}** is out.",
        "allowed_mentions": {"parse": []},
        "embeds": [{
            "title": f"Myspoti {version}",
            "url": url,
            "description": shorten(notes, url),
            "color": GREEN,
            "thumbnail": {"url": LOGO},
            "fields": fields,
            "footer": {"text": REPO},
            "timestamp": release.get("published_at") or release.get("created_at"),
        }],
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    tag = args[0] if args else None
    release = github(f"releases/tags/{tag}") if tag else github("releases/latest")
    data = payload(release)
    if dry:
        print(json.dumps(data, indent=2, ensure_ascii=False))
        return
    hook = os.environ.get("DISCORD_WEBHOOK_URL", "").strip()
    if not hook:
        print("DISCORD_WEBHOOK_URL is not set: nothing posted.")
        return
    req = urllib.request.Request(hook, data=json.dumps(data).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": "myspoti-release-bot"})
    with urllib.request.urlopen(req, timeout=20) as r:
        print(f"Posted {release['tag_name']} to Discord ({r.status}).")


if __name__ == "__main__":
    main()
