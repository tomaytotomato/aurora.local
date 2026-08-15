# v0.1 alpha runbook — fresh Debian box

Written the night before, for a session standing over a physical machine.
The point of a runbook is that you are not debugging at the same time as
installing, so where something is known to be unproven it says so rather
than pretending.

Status markers used below: **proven** means it ran end to end on the Lima
testbed from a destroyed VM; **untested** means the code exists but has
never executed anywhere; **absent** means there is no backend behind it.

## Before you start

- A Debian 12 box with a network cable in it and a static or reserved
  DHCP address. Note the address; you will need it.
- A sudo-capable user. The playbook pins `home_user: bruce` in
  `group_vars/all.yml`, so the account should be `bruce` unless you edit
  that first. A mismatch here fails late and confusingly.
- SSH access from the laptop, or a keyboard and monitor on the box.
- **Check the user's UID before you start:**

      id -u

  If that is not `1000`, edit `AURORA_UID` in `packages/dashboard/.env`
  to match before bringing the dashboard up. `DOCKER_GID` is detected
  automatically by `up.sh`; `AURORA_UID` is not, and is hardcoded to
  `1000` in `.env.example`. Get it wrong and every write the dashboard
  makes to the repo (`.state.yml`, per-package `.env` files, Caddy
  snippets) fails with a permission error that the wizard cannot explain.
  Debian's first user is normally 1000, so this usually passes, but it
  costs one command to be sure.
- The box is amd64. Two of the bugs fixed yesterday only bite on ARM, so
  they will not appear, but the fixes are harmless either way.

## 1. Get the repo onto the box

    curl -fsSL https://raw.githubusercontent.com/tomaytotomato/aurora.local/main/bootstrap.sh | bash

The one-liner self-clones to `~/aurora.local` and re-executes. If you
would rather see it first:

    git clone https://github.com/tomaytotomato/aurora.local.git
    cd aurora.local

**Use `main`.** Five bugs were fixed there yesterday, three of which
would stop the install dead on any machine. Anything older than
`23c0fbb` will not complete.

## 2. Run the install — **proven**

Interactive:

    ./bootstrap.sh

Or non-interactive, which is what the testbed exercises and therefore the
better-proven path:

    HOMELOCAL_NONINTERACTIVE=1 ENABLE_PACKAGES="core dashboard" \
      HOSTNAME=aurora DOMAIN=aurora.local HOME_USER=bruce \
      LAN_CIDR=192.168.0.0/24 ./bootstrap.sh install core dashboard

It will ask for the become password once (ansible `-K`).

What this does: installs Docker and Ansible if missing, runs
`host/site.yml` across the host roles, then brings the packages up with
compose. On the testbed it lands at `ok=43 changed=26 failed=0` and takes
about fifteen minutes from nothing, most of it building the dashboard
image.

### If it fails here

- **"no hosts to target"** — you are on an old checkout. Update to `main`.
- **"permission denied ... docker.sock"** — also an old checkout. The
  fix re-runs the compose step under `sg docker`; without it you would
  need to log out and back in mid-install.
- **"No package matching 'docker-ce'"** — an architecture mismatch in the
  apt repo. Should not occur on amd64.

## 3. Open the dashboard — **proven**

    http://<box-address>:8090/

Caddy is also on 80 and 443. `aurora.local` will only resolve if avahi's
mDNS is working on your network; if it does not, use the IP address and
do not spend the session on it.

## 4. Create the admin user — **untested**

The onboarding wizard covers this: `/onboarding/admin` creates the first
administrator, then domain, package selection, env, install and launch.
All of these have controllers.

Honest caveat: the wizard has never been driven end to end against a real
backend. The testbed proves bootstrap and the containers, not the
browser flow. Budget time for this to be where the session's surprises
are.

Role vocabulary is `admin`, `user`, `guest`. New rows default to `user`,
enforced by SQLite triggers rather than application validation, so a
caller that forgets to pass a role cannot accidentally create an
administrator. You cannot demote the last admin.

## 5. Install apps — **partly proven**

From the catalogue in the dashboard, or from the shell:

    ./bootstrap.sh add <package>

Proven on the testbed: `core`, `dashboard`. Coverage across the remaining
seventeen packages was being broadened overnight; check
`dev/notes/testbed-coverage-progress.md` on the branch for what was
actually proven versus assumed before relying on any given package.

## 6. Metrics check — **untested**

`/metrics/keys` and `/metrics/last24h` have a controller, and
`MetricsSamplerService` starts with the application (30s interval, 25h
retention), so a box that has been up for a few minutes should have
points to show. `ContainerStatsSampler` also runs (60s interval).

Note that per-package live resource usage (`memUsedMb`, `cpuPct`)
deliberately reports null: wiring the sampler per package is its own
piece of work. Null renders as the ceiling with no usage bar, which is
honest rather than broken.

## 7. VPN setup — **implemented, untested on hardware**

All 13 `/vpn/*` endpoints now have a backend and the capability flag is
on, so the page will appear. 578 backend tests pass, 35 of them new.
Nothing has run against a real WireGuard interface.

Keys are generated with the JDK's own X25519 generator rather than
`wg genkey`, because the command seam deliberately does not pipe stdin.

### The one thing to know before you demo this

**A peer's private key is shown exactly once, when you create the peer,
and is never stored.** So:

- Create the peer, and scan the QR code there and then on the phone.
- If you close that dialog, the config and QR are gone. `GET
  /vpn/peers/{id}/config` and `.../qrcode` return 409 with a message
  saying so. The only recovery is to delete the peer and add it again.

That is the safer reading of two things in tension: the design doc shows
persistent Download and QR actions per peer, but also says never to store
a peer's private key. Storing it (encrypted) is the alternative and is
what tools like wg-easy do. Worth deciding deliberately rather than
inheriting; it is not hard to change later.

### Assumptions baked in, worth checking on the day

- Peer DNS is always `1.1.1.1`. It is not wired to the Privacy package's
  AdGuard address, so VPN clients will not use your own DNS filtering.
- The split-tunnel LAN range is guessed as a `/24` off the detected LAN
  IP. If your home network is not a `/24`, fix this before relying on it.
- `reachable` always reports `null`; there is no external reachability
  probe, so the UI cannot tell you whether the endpoint is actually
  reachable from outside.

The design, already decided: egress split-tunnel via a reusable container
gateway that apps opt into by sharing its network namespace. Chosen over
host-level policy routing because netns sharing is the only approach safe
to toggle from a UI without rewriting host routes.

## What will not work tomorrow, whatever happens

- **Disks.** `storage-mount`, `mergerfs` and `snapraid` are conditional
  in `site.yml` and default to off. Turning them on means real block
  devices and a parity drive; that is a session of its own, not an
  afterthought to this one.
- **Backup, custom stacks, per-app protection.** No backend. Hidden
  behind capability flags.
- **Public TLS.** Caddy issues its own root CA for `.local`. Green
  padlocks need `./scripts/get-caddy-root-cert.sh` installing on each
  client device.

## Rollback

    ./bootstrap.sh remove <package>     # stop and disable one package
    ./scripts/down.sh                   # stop everything

The host roles are not undone by either. They are idempotent, so
re-running `site.yml` is safe, but removing fail2ban, ufw rules and the
hardened sshd config is a manual job. Worth knowing before you point this
at a machine you care about.
