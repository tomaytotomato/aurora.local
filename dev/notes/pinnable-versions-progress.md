# Pinnable versions — progress log

Goal: a curated, machine-readable list of safe pinnable versions for every
image in `packages/*/compose.yml`, feeding the existing (but currently
unused) `pins.env` mechanism (`scripts/pin.sh`, `scripts/lib/render.sh`
`render_pins`), rather than inventing a parallel scheme. See
`docs/ROADMAP.md` "Real `pins.env` generation" — this is that item.

## Design decision

`pins.env` per package already exists as a consumption path:
`render_pins()` sources `packages/<pkg>/pins.env` into the environment,
and `scripts/pin.sh --apply` (not run by this task) would rewrite
`compose.yml` `image:` lines to `${IMAGE_<SVC>}`. That is the natural
home for this data — no new manifest field, no schema change, nothing
new for yamllint to check (`pins.env` is a shell env file, not YAML).

Deviation from what `scripts/pin.sh --refresh` currently writes: that
script's `resolve_digest()` throws the tag away (`repo="${ref%%:*}"`),
writing `repo@sha256:digest` with no human-readable tag. That contradicts
`UnpinnedImageTagsRule`'s own stated ideal ("postgres:16@sha256:abc...:
tag for discoverability, digest for reproducibility"). This work writes
`repo:tag@sha256:digest` instead, and records provenance (checked date,
reasoning, multi-arch status) as comments above each `IMAGE_<SVC>` line.
`scripts/pin.sh` itself is not modified by this task (out of scope —
flagged for the owner as a follow-up).

Digests obtained with:
`docker buildx imagetools inspect --format '{{.Manifest.Digest}}' <ref>`
(never `docker manifest inspect --verbose` — gives per-platform digests
that don't match `RepoDigests`).

Compose files are NOT modified. `pins.env` files are additive data only.

## Inventory (from `grep -n image: packages/*/compose.yml`)

Package-by-package status below. Each row: current ref -> recommended pin,
multi-arch, notes.

<!-- status rows appended per package as work proceeds -->
