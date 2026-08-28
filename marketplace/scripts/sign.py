#!/usr/bin/env python3
"""Sign the composed marketplace index with the Ed25519 release key.

Produces, beside ``index.json``:
  * ``index.json.sig``     — detached Ed25519 signature over the exact
                             bytes of index.json, base64-encoded.
  * ``index.json.sha256``  — bare-eye checksum, ``<hex>  index.json``.

Aurora verifies the signature against the public key pinned into the
dashboard build (``marketplace-pub.ed25519.b64``). The signature is over
the raw file bytes, not a re-serialisation, so Aurora must verify the same
bytes it caches — see MarketplaceCatalogService.

In CI the private key comes from a repository secret written to a temp
file; locally it is ``marketplace/keys/marketplace-dev.ed25519.pem`` (which
is gitignored). Never commit a private key.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import pathlib
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey,
    )
except ImportError:
    sys.exit("cryptography required: pip install cryptography")

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_KEY = HERE.parent / "keys" / "marketplace-dev.ed25519.pem"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "index",
        nargs="?",
        type=pathlib.Path,
        default=HERE.parent / "dist" / "index.json",
    )
    ap.add_argument("--key", type=pathlib.Path, default=DEFAULT_KEY)
    args = ap.parse_args()

    if not args.key.is_file():
        sys.exit(f"private key not found: {args.key}")

    priv = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    if not isinstance(priv, Ed25519PrivateKey):
        sys.exit("key is not an Ed25519 private key")

    blob = args.index.read_bytes()
    sig = priv.sign(blob)
    sig_b64 = base64.b64encode(sig).decode()

    sig_path = args.index.with_name(args.index.name + ".sig")
    sha_path = args.index.with_name(args.index.name + ".sha256")
    sig_path.write_text(sig_b64 + "\n")
    sha_path.write_text(f"{hashlib.sha256(blob).hexdigest()}  {args.index.name}\n")

    print(f"signed {args.index} ({len(blob)} bytes)")
    print(f"  -> {sig_path.name}")
    print(f"  -> {sha_path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
