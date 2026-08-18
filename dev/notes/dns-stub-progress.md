# Freeing port 53 for AdGuard — progress log

## The blocker

The `privacy` package cannot start on a stock Debian box. AdGuard
publishes `${LAN_IP:-0.0.0.0}:53`, and `.env.example` ships
`LAN_IP=0.0.0.0` deliberately, so it asks for every address. Debian's
`systemd-resolved` is already holding `127.0.0.53:53`, which sits inside
that range, and docker refuses the bind:

    failed to bind host port 0.0.0.0:53/tcp: address already in use

Of the fourteen packages never installed anywhere, this was the one that
could not be installed at all rather than merely untried.

## What was already right

`JobFailureClassifier` (line 57) matches `address already in use` and
turns it into "port 53 is already in use on this box", so the dashboard
was never going to show the raw docker error. The reporting half needed
nothing; the fix is entirely host-side.

`host_roles:` in every manifest looked like the natural hook and is not
one — it is `[]` in all nineteen and nothing reads it.

## The fix

New `host/roles/dns-stub`, wired into `site.yml` before `docker` so
image pulls happen with the resolver in its final state.

- Writes `/etc/systemd/resolved.conf.d/10-aurora-dns-stub.conf` with
  `DNSStubListener=no`.
- Repoints `/etc/resolv.conf` at `/run/systemd/resolve/resolv.conf`
  (resolved's real upstream list) **before** restarting resolved, so the
  host never sits in a window where it is querying a listener that has
  gone. Only fires when the host actually depends on the stub — either
  the Debian symlink to `stub-resolv.conf`, or a plain file containing
  `127.0.0.53`, which is how some cloud images ship it.
- Asserts afterwards that the stub listeners are gone *and* that the
  host can still resolve a name. Both assertions, not just the first:
  freeing the port while breaking host DNS would be a worse box than the
  one we started with.

Default on (`dns_stub_listener_disabled: true`), overridable in
`group_vars/all.yml`. Gating it on "is privacy enabled" was considered
and rejected: `bootstrap.sh`'s `_write_configs` never writes
`packages_enabled` into `group_vars/all.yml` (and in the interactive
flow it runs *before* the package picker), so the variable would be
undefined and the role would silently skip on exactly the path that is
broken today.

## Two stub listeners, not one

resolved runs two: `127.0.0.53` for ordinary lookups and `127.0.0.54`
for the proxy path. Both are inside `0.0.0.0:53`, so either one left
behind keeps AdGuard from starting. `DNSStubListener=no` does remove
both — confirmed on the testbed rather than assumed, because a fix that
cleared one of them would have looked correct in the playbook output and
still failed at `docker compose up`.

## A check that could never fail

First version of the `doctor.sh` guard matched the owning process name
(`users:(("systemd-resolve"...))`). `ss` only prints that column when
run as root, and `doctor.sh` deliberately **refuses** to run as root
(line 42). The check would have passed on every broken box forever.

Caught by running `ss` as `bruce` on the testbed and noticing the empty
process column, not by reading the code back. Both the script and the
role's assertion now match on the addresses `127.0.0.53`/`127.0.0.54`,
which needs no privilege and does not depend on a process name.

## Testbed verification

Debian 12 Lima VM, which had the fault live: both stub listeners on 53,
`adguard` unable to start.

1. `ansible-playbook -t dns` → both listeners released, `getent hosts
   deb.debian.org` still answers, `resolv.conf` already pointed at the
   non-stub file so the repoint correctly skipped.
2. Second run → `changed=0`. Idempotent.
3. `./scripts/up.sh core dashboard identity notes storage privacy` →
   `adguard` started, `0.0.0.0:53->53/tcp` and `->53/udp` published.
   First install of `privacy` anywhere.
4. Negative case, because a guard that cannot fail is decoration:
   removed the drop-in, restarted resolved, and `doctor.sh` exited 1
   with "systemd-resolved holds port 53; adguard cannot start", while
   `docker start adguard` reproduced the original bind error verbatim.
5. Re-applied the role, `adguard` back up, `./dev/testbed/up.sh verify`
   green (dashboard 8090 → 200, caddy 80 → 200).

## Rebuild from a destroyed VM

Redone from nothing on the 18th (`up.sh destroy`, then the full chain
with `core dashboard privacy`), because the run above was against a box
that had already been bootstrapped several times.

- `bootstrap.sh` regenerated `group_vars/all.yml` carrying
  `dns_stub_listener_disabled: true`.
- `dns-stub` fired inside the full `site.yml` run, both assertions
  green, and `adguard` went Created → Started first time, publishing
  `0.0.0.0:53` tcp and udp plus 3000. No intervention anywhere.
- `doctor.sh`: 0 failures.

The `meta: flush_handlers` also runs `avahi`'s pending restart, visible
in the log. Expected, harmless, and the reason the task is named
"Apply the resolved configuration now" rather than pretending it only
touches resolved.

## Proving the repoint branch

Lima's Debian image points `/etc/resolv.conf` at
`/run/systemd/resolve/resolv.conf` itself, so on both testbed runs the
repoint task skipped — the branch that matters most on a real Debian box
was the one branch never executed.

Forced it deliberately: stopped `adguard`, removed the drop-in,
restarted resolved to get the stub back, and symlinked
`/etc/resolv.conf` → `stub-resolv.conf`, which is the genuine stock
Debian shape (`nameserver 127.0.0.53`, resolution working through it).
Re-ran the role: the repoint task reported `changed`, both assertions
passed, and the host still resolved afterwards. Every branch of the role
has now been executed at least once.

## A container can be "Up" and publish nothing

Worth knowing before anyone else re-tests this by hand. Last night's
sequence — `docker stop adguard`, a `docker start` that failed on the
port conflict, then a `docker start` that succeeded — left the container
`Up` for eleven hours with `HostConfig.PortBindings` still listing 53 and
`NetworkSettings.Ports` **empty**. Nothing was listening on 53 at all.

`docker ps` reports that as `adguard   Up 11 hours` with a blank Ports
column, which reads as success at a glance. It fooled me for a day.
`docker compose up -d` recreates it correctly; `docker start` after a
failed bind does not. Check `docker port <name>` rather than the status
column.

## Left undone

- `packages/privacy/.env.example` still carries the old comment arguing
  that `0.0.0.0` is safe because AdGuard "doesn't fight with
  systemd-resolved on loopback" — the reasoning is now backwards (the
  value is right, the justification is not). The file is outside what I
  can edit; the replacement wording is in the handover.
- AdGuard binds but does not yet *answer*: its first-run wizard has
  never been completed on the testbed, so there is no
  `AdGuardHome.yaml` and `up.sh` warns about it. Binding was the
  blocker; serving is a separate, unproven step. Reachable at
  `http://localhost:3000/` from macOS through Lima's automatic port
  forwarding (302 to `/install.html`).
- The host still resolves through its upstream servers, not through
  AdGuard. Deliberate for now — pointing the box at its own container
  means a stopped AdGuard takes docker pulls down with it. Worth a
  decision before hardware.
