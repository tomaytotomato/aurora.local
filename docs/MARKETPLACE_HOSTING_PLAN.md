# Marketplace hosting — separating Aurora's app catalogue from Aurora itself

**Status:** proposal, not scheduled.
**Author:** Aurora dashboard team.
**Requested by:** Bruce, 2026-08-27.

## The problem

Today Aurora's app catalogue lives inside the `aurora.local` repo. Every
package — Roundcube, Bulwark, Notes, Jellyfin, the lot — is a directory
under `packages/` with a manifest, a compose file, an env template and a
README. The dashboard reads those directories from disk at boot and
renders the marketplace from them.

This has worked well through v0.1 and v0.2, but three real limits are
starting to bite:

1. **Adding an app requires a dashboard release.** A new package on
   `main` doesn't reach users' boxes until they pull the aurora.local
   repo. There is no seam through which a fresh manifest can arrive
   between releases.

2. **The dashboard image and the catalogue upgrade together.** An
   operator who wants the latest Roundcube manifest fix has to accept
   whatever the dashboard build did that day. Reverting the catalogue
   without reverting the dashboard is not a thing you can currently do.

3. **Third-party contribution is unnatural.** Someone who writes a
   great manifest for, say, Immich has to open a PR against Aurora
   itself. That is fine for the ten packages we ship today; it does not
   scale to the hundreds we would like to reach.

Bruce's proposal solves all three by treating the catalogue as its own
product: a signed, versioned artifact Aurora fetches at runtime, with
its own release cadence and its own contributor path.

## Bruce's outline, restated

Verbatim from the 2026-08-27 conversation, with one small clarification
per line:

1. Marketplace manifest files live in a package called `marketplace`.
   *(One directory per app, one YAML per app. Analogous to today's
   `packages/*/manifest.yml` but consolidated under one tree.)*
2. Each app has a marketplace manifest.
   *(The single YAML from step 1 is the contract; everything else
   about the app — compose, README, env template — is expressed
   inside or fetched from it.)*
3. GitHub Actions on `main` composes all files into one main manifest.
   *(A CI job walks every per-app manifest, validates against a schema,
   and produces a single index blob.)*
4. Produces an artifact hosted on GitHub.
   *(GitHub Releases asset, or Pages, or both. Versioned URL plus a
   `latest` tag.)*
5. Aurora consumes the latest.
   *(Runtime fetch. Cached to disk so a box without internet still
   renders the catalogue it last saw.)*
6. If a new version is pushed, Aurora lets the user know whether they
   want to latest version of app manifest.
   *(Notification in the dashboard, opt-in acceptance. No auto-apply
   of the catalogue itself, even.)*
7. Updating the manifest does not update apps.
   *(A catalogue refresh changes what the marketplace shows and what a
   fresh install would install. It does not touch running containers,
   does not upgrade images, does not rewrite anyone's `.env`. Upgrades
   remain an explicit per-app action.)*

Point 7 is the one that has to be beaten into the design at every level.
It is the reason a marketplace catalogue is a lower-trust surface than a
container image: the worst a malicious marketplace update can do is
mislead the operator's next install decision. It cannot silently swap
what is already running.

## Architecture sketch

```
  ┌───────────────────────────────────────────────────────────────┐
  │  github.com/tomaytotomato/aurora-marketplace  (new repo)      │
  │                                                               │
  │   marketplace/                                                │
  │     roundcube/manifest.yml                                    │
  │     bulwark/manifest.yml                                      │
  │     jellyfin/manifest.yml                                     │
  │     ...                                                       │
  │   schema/marketplace-v1.json                                  │
  │   .github/workflows/publish.yml                               │
  │                                                               │
  │        │                                                      │
  │        ▼  (on push to main)                                   │
  │                                                               │
  │   1. Validate each manifest against schema/marketplace-v1     │
  │   2. Compose into one index                                   │
  │   3. Sign with the marketplace release key                    │
  │   4. Upload as GitHub Release asset                           │
  └───────────────────────────────────────────────────────────────┘
                            │
                            ▼
       GitHub Releases:  aurora-marketplace/v2026.08.27-a3f2c1
                          ├── index.json           (the composed catalogue)
                          ├── index.json.sig       (detached signature)
                          └── index.json.sha256    (bare-eye verification)
                            │
                            ▼
  ┌───────────────────────────────────────────────────────────────┐
  │  Aurora dashboard on the box                                  │
  │                                                               │
  │   MarketplaceCatalogService                                   │
  │     • Periodic fetch (daily) of /releases/latest              │
  │     • Signature verify against pinned public key              │
  │     • Persist to data/marketplace/index.json                  │
  │     • Persist metadata: fetched_at, index_version, sig_ok     │
  │                                                               │
  │   PackagesService                                             │
  │     • Reads packages/ (installed apps) as today               │
  │     • Merges the marketplace index for the "available" list   │
  │     • Installed apps continue to have on-disk manifests       │
  │                                                               │
  │   OverviewView → "1 new app added to the marketplace"         │
  │     • Notifies on new index_version                           │
  │     • Opt-in accept; declining pins on the previous version   │
  └───────────────────────────────────────────────────────────────┘
```

## Shape of the per-app manifest

The current `packages/roundcube/manifest.yml` is a good starting point —
it already describes the app well. The marketplace-hosted version needs
these additions:

- **`slug`** — canonical name (same as today's `name`), used as a URL
  key and to key the local cache.
- **`digest`** — the container image pinned by digest, not tag. This is
  the single most important contract in the whole system: it turns a
  "latest catalogue" into a promise that the operator's next install
  gets exactly the same bytes on every box that installs today.
- **`schema_version`** — matches the schema this manifest was written
  against. Aurora refuses to render a manifest whose schema is newer
  than it knows how to parse (see § open questions).
- **`compose_template`** — see § open questions.

The current `README.md`, `caddy.snippet`, and `.env.example` shapes
carry over more or less unchanged; the marketplace manifest either
embeds them or references them by URL.

## What Aurora does with the fetched index

**Read paths (no install triggered):**
- Marketplace page renders from the cached index.
- Search / filter work against the cached index.
- Each app's detail page reads its per-app manifest from the cached
  index.

**Write paths (require explicit user consent):**
- Accepting a new index version updates
  `data/marketplace/index.json` — nothing else.
- Installing a new app from the index materialises the package into
  `packages/<slug>/` on disk (manifest, compose, env template, README),
  and only then does the existing install flow kick in.
- Upgrading an installed app to a newer manifest is an explicit action
  on the app's detail page — not a side effect of updating the index.

## Security posture

The catalogue is a lower-trust surface than the images it points at,
but it is still an input to install decisions, so the plan carries
these mitigations:

1. **Signed artifact.** The publish workflow signs the index blob with a
   marketplace release key kept in GitHub Actions secrets. Aurora
   verifies the signature against a public key pinned into the
   dashboard build. Rotating the key is a dashboard release.
   *Alternative to weigh:* sigstore-style keyless signing with a Fulcio
   / Rekor pair. Simpler to run, harder to explain to homelabbers who
   have not heard of it.
2. **Image digest pinning.** Every image in the manifest is pinned by
   `@sha256:…`. A malicious index entry that swaps a digest cannot make
   Aurora pull a different image than the operator agreed to on the
   consent screen. Tag-only entries are rejected by the schema.
3. **Opt-in acceptance of new index versions.** No auto-upgrade of the
   catalogue, per Bruce's point 6.
4. **No auto-install, ever.** Per Bruce's point 7. A new catalogue entry
   is a new marketplace card, nothing more.
5. **Read-only cache path.** `data/marketplace/index.json` is
   dashboard-writeable only; the wizard and container never touch it.
6. **Fallback to the last cached index.** A box that lost internet
   yesterday still renders yesterday's marketplace. GitHub Releases
   being unavailable is not a marketplace outage.

## Open questions

### Where do the compose files come from?

Three shapes, in increasing order of engineering effort:

1. **Embedded in the manifest.** The full compose YAML is a string
   field. Self-contained; airgappable; the whole catalogue including
   compose is one artifact. Grows the index by ~2 KB per app (currently
   50 KB total for our ten packages, so ~150 KB at scale).

2. **Fetched by URL from the manifest.** Manifest holds a
   `compose_url: https://…` field. Smaller index, but Aurora now needs
   to verify a second artifact and cache it separately.

3. **Generated from the manifest by Aurora.** The manifest carries a
   structured `services:` block and Aurora writes the compose file
   locally. Nicest UX (Aurora owns the shape), most engineering work,
   and it locks marketplace apps into whatever compose primitives
   Aurora happens to generate.

Recommend **1** for v1: it is the simplest and it airgaps cleanly.
Revisit if the catalogue grows past a MB or two.

### Signing: pinned key or sigstore?

Pinned public key baked into the dashboard build is the simplest
possible thing. It handles the 99% case. It requires a dashboard
release to rotate.

sigstore adds infra to run but removes the pinned-key rotation
problem. Probably not worth it for v1.

Recommend **pinned key** for v1.

### Trust on first use?

For an operator whose dashboard shipped before v1-of-the-marketplace,
we would have to hand out the pinned key some other way. Options:

- Ship the key baked into the next dashboard release. Simplest.
- TOFU on first fetch: display the key fingerprint on the marketplace
  page during first accept. Homelabbers can and do check fingerprints,
  but only if we make it easy.

Recommend **baked in for v1**, revisit if we ever want to publish more
than one marketplace.

### Schema versioning

The manifest schema will change. `schema_version` in each manifest,
plus a `min_dashboard_version` in the index blob, lets Aurora refuse
to load a catalogue it knows it will misparse. Refuses cleanly, with a
"your dashboard needs an update" banner, and falls back to the last
compatible cached index.

### Offline boxes

- Cache the last accepted index. Render from it. Never fetch on the
  hot path.
- Provide a `docs/` recipe for sideloading a signed index blob onto an
  airgapped box.
- Never gate marketplace rendering on internet.

### What about currently-installed apps?

They keep their in-repo `packages/<name>/` manifests. Aurora reads both
sources: on-disk for installed, cached index for marketplace. The two
never fight because installed apps are keyed by presence in `.state.yml`
and marketplace apps are keyed by presence in the index. An app can be
in both — and when it is, the on-disk manifest wins for what is
currently running (per Bruce's point 7).

## Phasing

**Phase 0 — Repo split.** Move `packages/*/manifest.yml`,
`caddy.snippet`, `.env.example`, `README.md` into a new
`marketplace/<slug>/` layout inside a new repo, keeping the current
in-repo copies in place. Schema and validator in CI, no artifact yet,
no dashboard changes.

**Phase 1 — Signed artifact.** CI publishes the composed, signed
`index.json` on every push. Aurora still reads from disk; the artifact
just exists.

**Phase 2 — Aurora consumes.** `MarketplaceCatalogService` fetches,
verifies, caches. Marketplace page reads from the cache. Overview
banner announces new versions. In-repo manifests remain for airgap
fallback and for installed-app metadata.

**Phase 3 — Consent UI.** The "new version of the marketplace" flow
lives here. Accept / defer / stay-on-current.

**Phase 4 — Third-party contributor path.** Docs, contributor
workflow, PR template on the new repo. Not a code change on Aurora
itself; a process change.

Phases 0–2 are the meaningful engineering. 3 is UI. 4 is docs.

## What this plan is NOT

- Not a plan to auto-update installed apps.
- Not a plan to let arbitrary internet-hosted manifests run on the box
  without signature verification.
- Not a plan to move installed apps' running state off-disk.
- Not a v0.3 deliverable. Scheduling TBD after the current auth-plan
  work lands.

## Related

- `docs/UNIFIED_AUTH_PLAN.md` — the "explicit consent, no auto-apply"
  philosophy comes from here.
- `docs/PACKAGE_CONTRACT.md` — the current per-package shape this plan
  extends.
