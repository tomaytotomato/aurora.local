# Roadmap

What is wanted but not being built yet, and why. Kept so that a deferral
is a decision with a reason attached rather than something that quietly
got forgotten.

This is not a backlog of everything imaginable. Items here have been
asked for specifically and deferred deliberately.

## Deferred packages

### LibreChat

A self-hosted front end for talking to LLMs, wanted alongside or instead
of the existing `ai` package (Ollama plus open-webui).

**Deferred because it is not a single container.** LibreChat expects
MongoDB, and in most deployments Meilisearch for conversation search, so
it is a small stack rather than one image. That makes it the largest of
the packages currently wanted, and it lands on the same day the alpha is
going onto physical hardware.

What it needs when picked up:

- A multi-service `compose.yml` (app, Mongo, optionally Meilisearch) with
  the persistence living under `../../data/librechat/` per the existing
  convention.
- A decision on whether it replaces `ai`, sits beside it, or becomes a
  `variants:` alternative within the same category. The catalogue already
  supports interchangeable alternatives.
- API keys for hosted providers are secrets, so this wants the sops and
  age encryption work that is still owed, or it ships with credentials in
  a plain `.env`.
- An `sso:` block. LibreChat has its own auth, so the question is whether
  Authelia gates the front door only or whether trusted headers are wired
  through.

Resource note: with Mongo alongside it, this is not a small package. The
`resources:` block should be honest about that before it goes in the
catalogue.

### draw.io

A single `jgraph/drawio` container behind Caddy. Genuinely small: one
image, no persistence worth speaking of (diagrams live wherever you save
them), an `sso:` block, and a vhost.

Deferred only on timing. New packages are new install risk, and it was
raised on the day the alpha goes onto physical hardware. Worth picking up
once the box is proven.

### pi.dev agentic tools

Wanted, and currently underspecified. The repo gitignores
`.pi-subagents/`, so something of this shape is already in use locally,
but what a package would actually contain has not been established.

Before this can be scoped, three things need answering:

- What runs as a service, as opposed to what is a local developer tool?
  Only the former belongs in the catalogue.
- Does it need to reach the Docker socket or the repo? If so it inherits
  the same privilege questions the dashboard has, and the same answers
  should apply rather than a second set.
- Is it single-user or does it sit behind Authelia with the rest?

Left deliberately vague here rather than guessed at.

## Decided against

### Self-hosted Obsidian

Raised and rejected. Obsidian is a desktop application, so hosting it
means one of two awkward things: running CouchDB purely as a sync backend
for the LiveSync plugin, or running the real app inside a container behind
a web desktop, which is a remote desktop in a browser tab and poor on a
phone.

The catalogue already has `notes` (SilverBullet), which is a web-native
markdown notebook, needs no client install, and already carries an `sso:`
block. That is the answer for now. Revisit only if something SilverBullet
genuinely cannot do turns up.

## Owed infrastructure, unchanged

Recorded previously and still outstanding, listed here so it is in one
place:

- `docker-socket-proxy` in front of the dashboard. It still mounts
  `docker.sock:rw` at `packages/dashboard/compose.yml`.
- Real `pins.env` generation.
- sops and age for `.env` encryption. LibreChat above depends on this.
- Dump hooks behind the `backup:` manifest blocks. The blocks exist for
  photos and documents; the hooks they describe do not.

## Backend domains with no implementation

87 of 100 specified endpoints have a controller. The remainder, all
hidden behind capability flags so they do not surface as broken pages:

| Domain | Endpoints |
|---|---|
| backup | 6 |
| custom stacks | 5 |
| per-app protection | 2 |

## Explicitly not planned

Multi-host, Swarm or Kubernetes management; PaaS-style git-push deploys;
a third-party app store replacing the curated catalogue; rebuilding
Homepage's widget grid inside the admin UI.
