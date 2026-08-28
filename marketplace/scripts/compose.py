#!/usr/bin/env python3
"""Compose the Aurora marketplace index from the per-package manifests.

Walks ``packages/<slug>/`` in the aurora.local repo, and for every package
that opts into the marketplace (see ``INCLUDE`` rules below) emits one app
entry: the manifest summary, plus the embedded compose / .env.example /
caddy.snippet / README bodies (open question 1, option 1 — embedded for v1
so the whole catalogue airgaps as a single artifact).

Image digests are resolved best-effort. With ``--resolve-digests`` and a
working ``docker`` (or ``skopeo``) the composer pins every image by
``@sha256:...``; without registry access it records ``digest: null`` and
flags the app ``unpinned`` so the dashboard can warn on the consent screen
and CI can surface it without failing the whole catalogue.

Output: ``marketplace/dist/index.json`` (unsigned). Sign it with
``sign.py``.

This is deliberately the single source of truth path: the composer reads
the live ``packages/*/manifest.yml`` rather than a duplicated
``marketplace/<slug>/manifest.yml`` tree, so the catalogue can never drift
from what the box actually ships. The Phase-0 plan kept both; we collapse
them to one to remove the drift seam entirely.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: pip install pyyaml")

REPO = pathlib.Path(__file__).resolve().parents[2]
PACKAGES = REPO / "packages"

# Packages that are never marketplace apps: the dashboard itself is the
# admin plane you are looking at, not a card in its own catalogue; the
# template is scaffolding. Mirrors PackagesService.INFRASTRUCTURE_PACKAGES.
EXCLUDE = {"dashboard", "_template"}

IMAGE_RE = re.compile(r"^\s*image:\s*([^\s#]+)", re.MULTILINE)
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
MIN_DASHBOARD_VERSION = "0.1.0"
SCHEMA_VERSION = 1


def read(path: pathlib.Path) -> str | None:
    return path.read_text() if path.is_file() else None


def image_refs(compose_body: str | None) -> list[str]:
    if not compose_body:
        return []
    refs = []
    for m in IMAGE_RE.finditer(compose_body):
        ref = m.group(1).strip()
        # Strip a commented-out example line's leading '#'
        if ref.startswith("#"):
            continue
        refs.append(ref)
    # De-dupe, preserve order
    seen, out = set(), []
    for r in refs:
        if r not in seen:
            seen.add(r)
            out.append(r)
    return out


def resolve_digest(ref: str) -> str | None:
    """Best-effort digest resolution. Returns 'sha256:...' or None.

    Env-interpolated refs (``${FOO:-bar}``) can't be resolved and return
    None. Registry access is required; failures are swallowed to None.
    """
    if "${" in ref:
        return None
    for cmd in (
        ["docker", "manifest", "inspect", "--verbose", ref],
        ["skopeo", "inspect", f"docker://{ref}"],
    ):
        try:
            out = subprocess.run(
                cmd, capture_output=True, text=True, timeout=30
            ).stdout
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
        m = re.search(r'"[Dd]igest":\s*"(sha256:[0-9a-f]{64})"', out)
        if m:
            return m.group(1)
    return None


def compose_app(slug: str, resolve: bool) -> dict | None:
    d = PACKAGES / slug
    manifest_path = d / "manifest.yml"
    if not manifest_path.is_file():
        return None
    manifest = yaml.safe_load(manifest_path.read_text()) or {}

    compose_body = read(d / "compose.yml")
    refs = image_refs(compose_body)
    images = []
    unpinned = False
    for ref in refs:
        digest = resolve_digest(ref) if resolve else None
        if digest is None:
            unpinned = True
        images.append({"ref": ref, "digest": digest})
    if not images:
        # A package with no compose (should not happen for real apps) still
        # needs a non-empty images array to satisfy the schema; record the
        # absence honestly.
        images = [{"ref": f"{slug}:unknown", "digest": None}]
        unpinned = True

    app: dict = {
        "slug": slug,
        "title": manifest.get("title") or slug,
        "description": (manifest.get("description") or "").strip(),
        "category": manifest.get("category") or "productivity",
        "images": images,
        "unpinned": unpinned,
    }
    for key in ("icon", "variant_group", "source_url", "homepage_url"):
        if manifest.get(key) is not None:
            app[key] = manifest[key]
    if manifest.get("variant_default") is not None:
        app["variant_default"] = bool(manifest["variant_default"])
    for key in ("depends_on", "recommends"):
        val = manifest.get(key)
        if val:
            app[key] = list(val)
    if isinstance(manifest.get("requires"), dict) and manifest["requires"]:
        app["requires"] = manifest["requires"]

    for field, fname in (
        ("compose", "compose.yml"),
        ("env_example", ".env.example"),
        ("caddy_snippet", "caddy.snippet"),
        ("readme", "README.md"),
    ):
        body = read(d / fname)
        if body is not None:
            app[field] = body
    return app


def index_version(apps: list[dict], generated_date: str) -> str:
    """date + short hash of the apps payload. Stable across reorderings
    because apps are sorted by slug before hashing."""
    payload = json.dumps(
        sorted(apps, key=lambda a: a["slug"]),
        sort_keys=True,
        ensure_ascii=False,
    ).encode()
    short = hashlib.sha256(payload).hexdigest()[:6]
    return f"v{generated_date}-{short}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--resolve-digests",
        action="store_true",
        help="Resolve image digests via docker/skopeo (needs registry access).",
    )
    ap.add_argument(
        "--out",
        type=pathlib.Path,
        default=REPO / "marketplace" / "dist" / "index.json",
    )
    args = ap.parse_args()

    slugs = sorted(
        p.name
        for p in PACKAGES.iterdir()
        if p.is_dir()
        and not p.name.startswith((".", "_"))
        and p.name not in EXCLUDE
        and (p / "manifest.yml").is_file()
    )
    apps = []
    for slug in slugs:
        app = compose_app(slug, args.resolve_digests)
        if app:
            apps.append(app)

    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    generated_date = now.strftime("%Y.%m.%d")
    index = {
        "schema_version": SCHEMA_VERSION,
        "index_version": index_version(apps, generated_date),
        "generated_at": now.isoformat().replace("+00:00", "Z"),
        "min_dashboard_version": MIN_DASHBOARD_VERSION,
        "apps": apps,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n")
    unpinned = sum(1 for a in apps if a.get("unpinned"))
    print(
        f"composed {len(apps)} apps -> {args.out} "
        f"(index {index['index_version']}, {unpinned} unpinned)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
