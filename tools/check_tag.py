#!/usr/bin/env python3
"""Checks a release tag before the CI builds it, so Obtainium (and the app's own update check)
can always compare versions in strict semver.

    python3 tools/check_tag.py v2.2.1-beta.1

A tag is `vX.Y.Z` (stable) or `vX.Y.Z-beta.N` (beta, N from 1). A beta must be newer than every
existing tag: its X.Y.Z is the next version (above the last stable), N counts up from 1.
A stable must be newer than every other stable (a hotfix may follow a newer beta line).
Run from a clone that has the tags; exits 1 with a message when the tag does not fit.
"""
import re
import subprocess
import sys

TAG = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.([1-9]\d*))?$")


def key(tag):
    """Semver order: X.Y.Z, then a stable above its betas, then the beta number."""
    m = TAG.match(tag)
    if not m:
        return None
    n = m.group(4)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), 1 if n is None else 0, int(n or 0))


def check(tag, others):
    mine = key(tag)
    if mine is None:
        return f'"{tag}" is not vX.Y.Z or vX.Y.Z-beta.N (strict semver, N from 1).'
    stable = mine[3] == 1
    for other in others:
        k = key(other)
        if k is None or other == tag:
            continue  # older tags in another format are not compared
        if (not stable or k[3] == 1) and k >= mine:
            return (f'"{tag}" must be newer than the existing "{other}": a beta follows the last stable '
                    f'(2.2.0 -> 2.2.1-beta.1 or 2.3.0-beta.1, then beta.2...), a stable follows its betas.')
    return None


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: check_tag.py vX.Y.Z[-beta.N]")
    existing = subprocess.run(["git", "tag", "-l", "v*"], capture_output=True, text=True, check=True).stdout.split()
    problem = check(sys.argv[1], existing)
    if problem:
        print(f"::error::{problem}")
        sys.exit(1)
    print(f"{sys.argv[1]} is a valid next tag.")
