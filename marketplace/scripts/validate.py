#!/usr/bin/env python3
"""Validate a composed marketplace index against schema/marketplace-v1.json.

Run in CI between compose.py and sign.py: an index that does not validate
is never signed, so a box never verifies-then-renders a malformed
catalogue. Exit non-zero on any schema violation.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

try:
    from jsonschema import Draft7Validator
except ImportError:
    sys.exit("jsonschema required: pip install jsonschema")

HERE = pathlib.Path(__file__).resolve().parent
SCHEMA = HERE.parent / "schema" / "marketplace-v1.json"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "index",
        nargs="?",
        type=pathlib.Path,
        default=HERE.parent / "dist" / "index.json",
    )
    ap.add_argument(
        "--strict-pinning",
        action="store_true",
        help="Fail if any app is unpinned (no digest). Off by default so an "
        "offline compose still produces a validatable index.",
    )
    args = ap.parse_args()

    schema = json.loads(SCHEMA.read_text())
    data = json.loads(args.index.read_text())
    v = Draft7Validator(schema)
    errors = sorted(v.iter_errors(data), key=lambda e: list(e.path))
    for e in errors:
        path = "/".join(str(p) for p in e.path) or "(root)"
        print(f"  {path}: {e.message}", file=sys.stderr)
    if errors:
        print(f"FAIL: {len(errors)} schema error(s) in {args.index}", file=sys.stderr)
        return 1

    unpinned = [a["slug"] for a in data["apps"] if a.get("unpinned")]
    if unpinned:
        msg = f"{len(unpinned)} unpinned app(s): {', '.join(unpinned)}"
        if args.strict_pinning:
            print(f"FAIL: {msg}", file=sys.stderr)
            return 1
        print(f"WARN: {msg}", file=sys.stderr)

    print(f"OK: {args.index} valid ({len(data['apps'])} apps, index {data['index_version']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
