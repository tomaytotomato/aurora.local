# The Essence of aurora.local

This is the durable summary of what aurora is and why it is built the
way it is. Status reports, phase handovers, and the overnight logs move
in and out of date; this document should not. If you read one file
before touching the codebase, read this one.

## What it is

aurora turns a fresh Debian or Ubuntu box into a self-hosted home
server that a non-technical person can actually run. Reverse proxy,
dashboard, LAN DNS, media automation, file sharing, notes, photos, and
whatever else you enable: all in Docker, all under a single `aurora`
compose project, all reachable at `aurora.local` with a green padlock
and no `/etc/hosts` editing.

It is opinionated on purpose. One clear choice per job, a fixed
catalogue of apps, sensible defaults baked in. The point is not to be a
blank canvas like Portainer or a sprawling store like CasaOS; the point
is that the boring decisions are already made so the box just works.

## Who it is for

Two people, and holding both in mind explains almost every decision:

- **Sarah**, the operator. A nurse with a mini PC who has never opened a
  terminal. Success is measured against her: the experience should feel
  like setting up a Sonos, not administering Linux. If a step makes her
  reach for a command line, that step is a bug.
- **The owner** (that's me). Technical, comfortable with Docker and
  Ansible, owns the box and every rebuild. The plumbing can be as
  sophisticated as it needs to be, as long as none of it leaks up to
  Sarah.

## The doctrine

Two principles run through the whole project and are worth stating
plainly because they are easy to erode.

**Zero terminal.** Every CLI affordance in the user journey is treated
as a defect. The old `./scripts/up.sh` became an in-app button that
streams progress over SSE so the screen never sits silent. Failure copy
is plain English ("Port 53 is already in use on this box"), never a
stack trace and never a `docker` verb; the copy is scrubbed and
unit-tested for leaked jargon.

**Honest state over invented state.** The dashboard never fabricates
data to look finished. A security score of 78 with four made-up findings
was deleted in favour of an empty state that lists the checks still to
come. The container count is labelled to match exactly what it counts.
"Metrics land next release" is preferred to a faked chart. A number on
screen is either real or it is not shown.

## The layered architecture

aurora is built in layers, each one thinner than the app store crowd
but taken more seriously.

1. **OS** (Debian/Ubuntu). Bring your own; aurora does not want to own
   the disk or the kernel.
2. **Host** (Ansible, under `host/`). OS hardening as code: docker,
   ufw (default-deny, LAN-only), ssh-hardening, fail2ban,
   unattended-upgrades, swap, avahi/mDNS, storage mounts, and the Caddy
   trust root. This is where aurora already beats the consumer NAS
   distributions.
3. **Platform** (the `core` and `identity` packages). Caddy terminates
   TLS with an internal CA and reverse-proxies every service; Homepage
   gives a landing dashboard; Authelia provides single sign-on and 2FA
   by forward-auth, so you log in once and every downstream app trusts
   the session.
4. **Control plane** (the `dashboard` package: a Spring Boot backend and
   a Vue SPA served from one `aurora.jar`). This is the management brain.
   It owns the docker socket, the repo, the state file, and a small
   SQLite database; it does all the probing, launching, security
   scanning, metrics sampling, and SSE streaming. The SPA is a thin
   client on top of a typed API.
5. **App catalogue** (`packages/`). The apps themselves, each a
   self-contained stack.

## The central idea: the package contract

Everything composable about aurora comes from one convention. A package
is a directory under `packages/<name>/` containing:

- `compose.yml` — the stack itself
- `manifest.yml` — the single source of truth about it: category,
  dependencies, ports, resource requirements (`min_ram_mb`,
  `min_disk_gb`), start budget, and post-install notes
- `.env.example` and `README.md`
- optional `caddy.snippet` (a vhost fragment merged into Caddy),
  `homepage.yml` (dashboard tiles), and `seed.sh` (an idempotent
  post-up hook)

The installer and every operational script read those manifests to
resolve dependencies, drive the picker, warn about resources, and render
status. Adding an app is a copy of `_template/` and a `./bootstrap.sh
add <name>` away. Features are declared per package, not hard-coded, so
the blast radius of any one app stays small.

State lives in `.state.yml` (gitignored): the hostname, domain, and the
enabled package set. `add` and `remove` mutate it so `up`, `down`, and
`status` always know the true picture.

## How it is built

aurora is developed largely by an autonomous agent loop, nicknamed
**Ralph**. A `RALPH_TASK_*.md` spec defines a checklist; the loop grinds
it one item per commit, keeping a verification script green on every
step (backend tests, `vue-tsc --noEmit`, vitest, and a docker build
check, five gates that must all stay green). It works in an isolated git
worktree, never touches the live box or its secrets, pushes after each
commit, and writes a dated log plus a morning briefing for the human to
read over coffee. Product-judgement forks are not guessed; the loop
pauses and asks. The human owns every live rebuild.

This is why the repo carries so much narrative history: briefings,
overnight reports, phase handovers. Those are the loop reporting for
duty. They are archived under `docs/history/`; this document is what
they were all circling.

## The stack

- **Frontend:** Vue 3.5, Vite, shadcn-vue on Tailwind v4, TypeScript in
  strict mode. No hand-rolled UI primitives; the kit is owned but starts
  from a maintained, accessible source.
- **Backend:** Spring Boot 4, SQLite, docker-java. Serves the SPA from
  inside the jar. SSE is the house pattern for anything live.
- **Edge:** Caddy (internal CA, HTTPS, reverse proxy), discovered on the
  LAN via avahi/mDNS as `<service>.aurora.local`, gated by Authelia.
- **Reference box:** a Dell OptiPlex, i5-6500T, ~15.5 GB RAM, no GPU, at
  `aurora.local`.

## Design language

Warm-monochrome (shadcn `neutral` base), an aurora photograph behind a
bento-grid dashboard, a sun/moon dark-mode toggle, a health pill in the
top bar. Onboarding speaks in milestones ("Almost there", "No typing
required") and shows packages as inline chips. The header must always
read `aurora.local`: never the doubled `aurora.aurora.local`, never a
raw container hex. The empty-state and error-state copy contracts are
treated as inviolable across every UI migration.

## Where it sits in the world

The nearest neighbours are Yunohost (opinionated, integrated SSO and
backups, a packaging format) and Cosmos Cloud (proxy plus SSO plus a
security centre), not CasaOS or Umbrel. aurora is more transparent than
either: the whole system is a git repo with a package contract, not an
opaque app store. Its deliberate weakness is the flip side of layer 1:
by not owning the OS or the disks, it cannot do ZFS the way TrueNAS or
ZimaOS can, and its disk story is the part most in need of work.

## Current state (as of the last phase)

Onboarding, the dashboard, the shadcn migration, TLS with an internal
CA, and mDNS aliasing are all shipped. Single sign-on (Authelia, with
aurora as the user directory) is the phase in flight. The known gaps,
and the plan to close them, live in `PLAN.md`.
