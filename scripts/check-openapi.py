#!/usr/bin/env python3
"""Structural check on packages/dashboard/openapi.yaml.

The spec is the contract the backend gets built against, and the frontend
is being written ahead of it. That only works if the spec stays honest,
so this fails on the three ways it rots in practice:

  * it stops parsing;
  * a $ref points at a schema or parameter that no longer exists;
  * a path is tagged with something the tags list never declared.

It also reports schemas nothing references, which is usually a rename
that only got half done.

Deliberately not a full OpenAPI 3.1 validator: that needs a dependency
tree far larger than the problem. Requires pyyaml only.

Usage: python3 scripts/check-openapi.py
"""

from __future__ import annotations

import pathlib
import re
import sys

import yaml

SPEC = pathlib.Path("packages/dashboard/openapi.yaml")
HTTP_VERBS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}


def main() -> int:
    if not SPEC.exists():
        print(f"::error::{SPEC} not found (run from the repo root)")
        return 1

    text = SPEC.read_text()
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        print(f"::error file={SPEC}::does not parse: {exc}")
        return 1

    components = doc.get("components", {})
    schemas = components.get("schemas", {})
    parameters = components.get("parameters", {})
    paths = doc.get("paths", {})

    problems: list[str] = []

    for ref in sorted(set(re.findall(r"#/components/schemas/([A-Za-z0-9_]+)", text))):
        if ref not in schemas:
            problems.append(f"$ref to undefined schema: {ref}")

    for ref in sorted(set(re.findall(r"#/components/parameters/([A-Za-z0-9_]+)", text))):
        if ref not in parameters:
            problems.append(f"$ref to undefined parameter: {ref}")

    declared_tags = {t["name"] for t in doc.get("tags", []) if isinstance(t, dict)}
    used_tags: set[str] = set()
    for path, operations in paths.items():
        if not isinstance(operations, dict):
            continue
        for verb, operation in operations.items():
            if verb not in HTTP_VERBS or not isinstance(operation, dict):
                continue
            if not operation.get("summary"):
                problems.append(f"{verb.upper()} {path}: no summary")
            if not operation.get("responses"):
                problems.append(f"{verb.upper()} {path}: no responses")
            used_tags.update(operation.get("tags", []))

    for tag in sorted(used_tags - declared_tags):
        problems.append(f"path tagged '{tag}' but the tags list never declares it")

    # Advisory only: an unreferenced schema is usually a half-finished
    # rename, but a schema can legitimately exist for the backend's
    # benefit before anything points at it.
    unreferenced = sorted(
        name
        for name in schemas
        if f"schemas/{name}'" not in text and f'schemas/{name}"' not in text
    )

    for problem in problems:
        print(f"::error file={SPEC}::{problem}")

    if unreferenced:
        print(f"note: schemas nothing references: {', '.join(unreferenced)}")

    print(
        f"{len(paths)} paths, {len(schemas)} schemas, {len(declared_tags)} tags"
        f" — {len(problems)} problem(s)"
    )
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
