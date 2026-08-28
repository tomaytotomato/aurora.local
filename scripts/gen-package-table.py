#!/usr/bin/env python3
"""Regenerate README.md's package table from the manifests themselves.

The table drifted to twelve entries while the repo shipped eighteen packages
(bulwark, filebrowser, jellyfin, memos, roundcube and snappymail were all
missing), which is the failure mode of any list a human maintains beside the
thing it describes. `--check` fails when the file is stale, so CI can hold
the line.

    ./scripts/gen-package-table.py            # rewrite README.md
    ./scripts/gen-package-table.py --check    # exit 1 if it would change
"""
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parent.parent
README = REPO / "README.md"
START = "<!-- package-table:start -->"
END = "<!-- package-table:end -->"


def rows():
    for manifest in sorted((REPO / "packages").glob("*/manifest.yml")):
        name = manifest.parent.name
        if name.startswith("_"):
            continue
        data = yaml.safe_load(manifest.read_text()) or {}
        title = str(data.get("title") or name).strip()
        category = str(data.get("category") or "").strip()
        yield name, category, title


def table():
    body = [
        "| Package | Category | What |",
        "|---------|----------|------|",
    ]
    for name, category, title in rows():
        body.append(f"| `{name}` | {category} | {title} |")
    return "\n".join(body)


def main():
    text = README.read_text()
    block = f"{START}\n{table()}\n{END}"
    if START in text and END in text:
        new = re.sub(re.escape(START) + r".*?" + re.escape(END), block, text, flags=re.S)
    else:
        print("README.md has no package-table markers; add them first", file=sys.stderr)
        return 2

    if "--check" in sys.argv:
        if new != text:
            print("README.md package table is out of date; run ./scripts/gen-package-table.py",
                  file=sys.stderr)
            return 1
        return 0

    README.write_text(new)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
