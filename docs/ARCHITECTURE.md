# Architecture

Four diagrams: layered stack, bootstrap sequence, request flow, and the
control plane — which is the one to read first if you are asking "what
actually drives what".

The diagrams live as standalone `.mmd` files under
[`docs/diagrams/`](diagrams/), one per section, so they can be opened in a
Mermaid editor and rendered on their own rather than being trapped in this
file. Each section below explains its diagram and links to it; see
[`docs/diagrams/README.md`](diagrams/README.md) for how to view them.

## 1. Layered view

Where everything sits, from the phone in your hand down to the disk:
client devices, then the edge that fronts them (AdGuard for DNS, Caddy for
TLS, Authelia for auth, Aurora at the apex), then the packages themselves
as compose bundles, then Docker, the Ansible-managed host, and the
hardware.

> **Diagram:** [`diagrams/01-layered-view.mmd`](diagrams/01-layered-view.mmd)

## 2. Bootstrap sequence

What happens between `curl … | bash` and containers running, in order:
prereqs, clone and re-exec, the questions (or `ENABLE_PACKAGES` when
headless), `.state.yml` written, Ansible for the host, then `up.sh` for
the apps — seeding `.env` files, rendering fragments, and finally one
`docker compose up`.

> **Diagram:** [`diagrams/02-bootstrap-sequence.mmd`](diagrams/02-bootstrap-sequence.mmd)

## 3. Request flow (client hitting a service)

A phone asking for `sonarr.aurora.local`: DNS to AdGuard, HTTPS to Caddy,
optionally through Authelia, then to the container by service name — and
onward to the data it persists and the backup that reads it.

> **Diagram:** [`diagrams/03-request-flow.mmd`](diagrams/03-request-flow.mmd)

Step 5 is conditional on the package's own manifest: `sso.protect` is what
makes `CaddySnippetService` emit `import authelia` into that package's
vhost. A package without it is reachable on the LAN with no login, which
is a deliberate choice per package, not an oversight.

Nothing reaches an app directly. Caddy is the only thing bound to 80 and
443; every other container is reachable only by service name on
`aurora_net`, which is why a package's compose file never publishes its
own web port.

## 4. Control plane — what drives what

The layered view above shows where things sit. This shows how the box gets
built and changed, and it is the diagram people get wrong: the two halves
(Ansible for the host, bash + compose for the apps) are not siblings —
`bootstrap.sh` drives both, in order — and the dashboard is not above the
apps, it *is* one of them.

> **Diagram:** [`diagrams/04-control-plane.mmd`](diagrams/04-control-plane.mmd)

Three properties worth stating in words, because they are the ones that
cause bugs:

**`up.sh` converges; it does not "start".** It ends with
`state_set_enabled "${pkgs[@]}"` and passes `--remove-orphans`, so
`up.sh media` means "make this box be core+media" — it rewrites
`.state.yml` and reaps every other package's containers. `restart.sh` and
`upgrade.sh` exist precisely so that acting on one package cannot do that.

**The loop is real and it bites.** The dashboard runs in a container that
`up.sh` manages, and it manages packages by running `up.sh`. Recreating
the container that is running the script kills the script mid-invocation;
that was a genuine bug, and both scripts now carry a guard keyed on
`AURORA_LAUNCHED_BY` / `AURORA_INVOKED_BY`.

**There is no database in the control plane.** Every input is a file in
the repo — manifests, `.state.yml`, `group_vars/all.yml`. The dashboard's
SQLite holds its own concerns (users, audit, settings, VPN peers), not
what the box is.

## 5. How the backend talks to a packaged service

**Standing decision: drive a packaged service through its CLI, not its
HTTP API.** Where a service ships both — Kopia, AdGuard, Authelia,
qBittorrent, Immich — the CLI is the interface Aurora uses, invoked in the
service's own container:

```
docker exec <container> <tool> <subcommand> --json
```

The dashboard backend already drives every external tool this way through
`CommandRunner`: `wg` (VpnService), `smartctl` and `snapraid`
(DisksService), `kopia` (BackupService), and `up.sh`/`down.sh`
(PackageLifecycleService, LaunchService). One seam, one place to fake in
tests (`FakeCommandRunner`), one place where timeouts, cancellation and
line streaming are already solved.

### Why not the HTTP API

- **Credentials.** A service's HTTP API needs its admin password, which
  lives in *that package's* `.env`. Reaching across package boundaries to
  read another package's secrets is a coupling worth refusing; the CLI
  inside the container is already authenticated by being there.
- **One failure vocabulary.** A CLI gives an exit code, stdout and stderr.
  Every consumer here already knows how to classify that
  (`JobFailureClassifier`), and it is the same story whether the tool is
  `wg` or `kopia`.
- **Testability.** `FakeCommandRunner` stubs a command by substring. An
  HTTP client needs a WireMock per service, and a second set of auth
  fixtures.
- **Version drift.** These projects change their HTTP APIs more freely
  than their CLI flags, and a broken CLI flag fails loudly at the exit
  code rather than silently returning a differently-shaped JSON body.

### When to break the rule

Reach for HTTP when the CLI genuinely cannot answer: streaming or
push-style data (progress events, live logs), an operation with no CLI
equivalent at all, or a service that ships no CLI. Say so in the service's
class javadoc when you do, and name what the CLI could not do — that
sentence is the point of this section.

### What this does not cover

Talking to *Docker* is `DockerService` (docker-java), not a shelled-out
`docker` CLI, because it is a long-lived API this backend depends on
structurally rather than a packaged app. Reading a file a host role wrote
(`DisksService` and the disk-state JSON) is a file read, not a CLI call.
