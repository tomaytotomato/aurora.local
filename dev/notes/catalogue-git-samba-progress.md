# Remove Forgejo, research a Samba replacement — progress log

Two independent tasks sharing a branch (`chore/remove-forgejo`) because
both came from the same "abandoned pin" sweep in
`dev/notes/pinnable-versions-progress.md`: `git` (Forgejo) got removed
outright, `storage` (Samba) got researched but left alone.

## Task 1: remove `packages/git`

Decision already made by the owner before this work started: Forgejo
1.21 is dead (no `1.21.x` release since 2024-06-13, nine major versions
behind current stable v16.0.2), so the package is being pulled now and
a replacement chosen later, rather than patched in place.

### What was removed

- `packages/git/` in full: `compose.yml`, `manifest.yml`, `caddy.snippet`,
  `homepage.yml`, `README.md`, `.env.example`, `pins.env.example`.
- The `git` row from the package table in the root `README.md`.
- The `Git[git<br/>Forgejo]` node from the layered-view mermaid diagram
  in `docs/ARCHITECTURE.md` (and dropped the `+9 others` label to `+8`
  to match).
- Every prose mention of Forgejo in `docs/DASHBOARD_BRIEF.md` (three
  places: the SSO-across-the-box intro, the sign-out redirect list, the
  emergency-access credential list).
- `packages/identity/README.md` and `packages/identity/caddy.snippet`:
  Forgejo was one of three services (with Grafana, Paperless) called
  out as using Authelia's trusted-header auth; all three mentions
  updated to just Grafana + Paperless. Also dropped the `FORGEJO_ADMIN_*`
  bullet from identity's emergency-access list.
- Dashboard mock fixtures under
  `packages/dashboard/frontend/src/mocks/fixtures/`:
  - `packages.ts` — removed the whole `git` package summary/detail entry.
  - `proxy.ts` — removed the `forgejo` proxy target (`package: 'git'`).
  - `backup.ts` — the `src-git` backup source (package `git`, the one
    fixture demonstrating a *failed* snapshot) was re-pointed at
    `filebrowser` instead of deleted outright, so the "one source failed
    last night" demo case in the backup page still exists — just against
    a package that's actually still in the catalogue.
  - `hardening.ts` — dropped `forgejo-runner` from the docker-socket
    `exposedContainers` example, leaving `aurora-dashboard` (the one
    container that genuinely still mounts the socket).

### What was searched and deliberately left alone

- `docs/history/RALPH_TASK_D_AUTHELIA.md` and
  `docs/history/PHASE_D_HANDOVER.md` — these record what Phase D
  actually did (Forgejo was a real migration target at the time).
  Editing history to erase a since-removed package would misrepresent
  what happened, so they're untouched.
- `dev/notes/done-page-state-progress.md` — another agent's own
  progress note (audits the "container name doesn't match package
  name" trap, lists `git -> forgejo, forgejo-runner` as one example
  row). Not mine to edit, and it's a point-in-time audit note, not
  functional code.
- A handful of code-comment mentions of "Forgejo" in files under active
  work by other agents: `SsoBlock.java`,
  `AutheliaCaddySnippetInvariantsTests.java`, a backend test fixture
  `caddy.snippet` (under `src/test/resources/identity/`), and
  `OnboardingSso.vue`. All of these just list Forgejo alongside
  Grafana/Paperless as an example of trusted-header auth in prose —
  none of them are manifest references, dependency declarations, or
  anything that would fail validation. Left them so as not to step on
  identity/onboarding work happening in parallel on other branches.
- `HardeningControllerIntegrationTest.java` (backend) writes a synthetic
  `packages/git/compose.yml` fixture inside a temp test repo to exercise
  the docker-socket-exposure scanner — it's a literal string used as an
  arbitrary example package name, not a reference to the real
  `packages/git`, and the test doesn't depend on the directory existing
  on disk. Left alone.
- `hardening.spec.ts` (frontend unit test, not the fixtures file) has its
  own inline `'forgejo-runner'` string as an example container name; the
  test only asserts on computed `status`, never the container name
  itself. Harmless, left alone.
- `backup.spec.ts` uses `source({ id: 'git', ... })` via its own local
  `source()` factory — entirely independent of
  `mocks/fixtures/backup.ts`, `'git'` here is just an arbitrary test id
  string. Left alone.

Confirmed no other package's `manifest.yml` named `git` in `depends_on`
or `recommends` (grepped every manifest), so removing the package left
no dangling dependency for the install-plan resolver to trip over.

### What happens to a box that already has `git` installed

Nothing happens immediately — removing a package from the catalogue
doesn't touch a running box. Forgejo and its runner keep serving, and
`.state.yml` still lists `git` as enabled. The problem shows up the next
time `scripts/up.sh` or `scripts/down.sh` runs without pinning an
explicit package list (or is run with `git` explicitly): both scripts
check `packages/<pkg>/compose.yml` exists (and `up.sh` additionally runs
`manifest_resolve_deps`, which needs `manifest.yml`) *before* doing
anything else, and `die` immediately if it's missing — there's no
"skip a package it can't find" path. That means every other enabled
package on the box becomes unmanageable through either script until the
dangling `git` entry is cleared from `.state.yml`.

Awkwardly, the normal fix (`bootstrap.sh remove git`) itself shells out
to `scripts/down.sh git`, which needs the exact `packages/git/compose.yml`
this change deletes — so the one command built to remove a package
cleanly can't run once the package's files are already gone. Written up
in `docs/ROADMAP.md` under "Removed packages" with the two viable
orderings (stop it before pulling this change, or `docker rm -f` the
containers and hand-edit `.state.yml` after). No migration script was
written — there's no replacement chosen yet, so a `remove`/re-`add`
flow would be guessing at a shape. `data/git/` is left untouched either
way, ready to migrate once a replacement lands.

Added a "Removed packages" section to `docs/ROADMAP.md` recording the
reason (dead pin, nine majors behind, no security fix in two years) so
nobody re-adds Forgejo 1.21 without knowing why it was pulled, plus the
short list of things worth evaluating when a replacement is picked
(Gitea, a newer Forgejo pin, or hosting git off-box entirely).

## Verification run (Task 1)

- `packages/dashboard/frontend`: `npx vitest run --reporter=basic` —
  49 files, 507 tests, all green (no regression from the fixture
  edits).
- Backend Java: untouched by this work (no files under
  `packages/dashboard/backend/src` were changed), so the 725-test
  backend suite wasn't re-run.
