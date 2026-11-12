#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Builds and signs the manifest of a folder of metadata scripts (ECDSA P-256, SHA-256, DER, base64).

usage: sign_scripts.py <scripts folder> <output folder> <scripts version> <private key .pem> [min catalog api, default 1]

The output folder gets manifest.json, manifest.json.sig and scripts/<name>.js: publish its content as it is.
The private key (PKCS#8 PEM) stays outside the repository and is never passed on the command line in any other way.
"""
import base64
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

NAME = re.compile(r"[a-z0-9_]{1,40}\.js")
MAX_SCRIPT = 256 * 1024


def main():
    if len(sys.argv) not in (5, 6):
        sys.exit(__doc__)
    src, out, version, keyfile = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]), Path(sys.argv[4])
    min_api = int(sys.argv[5]) if len(sys.argv) == 6 else 1
    files = sorted(p for p in src.glob("*.js") if p.name != "prelude.js")
    if not files:
        sys.exit("no scripts")
    entries = []
    for p in files:
        data = p.read_bytes()
        if not NAME.fullmatch(p.name) or not 0 < len(data) <= MAX_SCRIPT:
            sys.exit(f"{p.name}: bad name or size")
        entries.append({"name": p.name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
    manifest = json.dumps({"manifest": 1, "version": version, "minApi": min_api, "scripts": entries}, indent=2).encode() + b"\n"
    key = serialization.load_pem_private_key(keyfile.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        sys.exit("expected an EC P-256 key")
    sig = key.sign(manifest, ec.ECDSA(hashes.SHA256()))
    (out / "scripts").mkdir(parents=True, exist_ok=True)
    for p in files:
        shutil.copyfile(p, out / "scripts" / p.name)
    (out / "manifest.json").write_bytes(manifest)
    (out / "manifest.json.sig").write_text(base64.b64encode(sig).decode() + "\n")
    print(f"signed version {version}: {len(files)} scripts")


main()
