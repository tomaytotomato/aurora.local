# Testbed

A throwaway Debian 12 VM that runs the real install chain: `host/site.yml`
via Ansible, then the packages via docker compose, then a check that the
box actually serves something.

It exists because the development machine is a Mac. `bootstrap.sh`,
`host/site.yml` and everything under `scripts/` target Debian and cannot
run on darwin at all, so until this existed they were linted but never
executed. The first run found five bugs, listed at the bottom.

## Requirements

    brew install lima

Lima runs a real VM on Apple's Virtualization.framework. Docker Desktop
is not involved; the VM runs its own Docker daemon, which is the same
topology as a real Aurora box.

## Use

    ./dev/testbed/up.sh              # create if needed, sync, install, verify
    ./dev/testbed/up.sh sync         # refresh the repo copy only
    ./dev/testbed/up.sh install      # sync, then run the install chain
    ./dev/testbed/up.sh verify       # check containers and endpoints
    ./dev/testbed/up.sh shell        # interactive shell as bruce
    ./dev/testbed/up.sh destroy      # delete the VM

First creation downloads a Debian cloud image and takes a few minutes.
A full install from nothing takes roughly fifteen, most of it the
dashboard image build.

Once up, the VM's ports are forwarded to the Mac:

| Service   | In the VM | On the Mac |
|-----------|-----------|------------|
| dashboard | 8090      | 8090       |
| caddy     | 80        | 8080       |
| caddy TLS | 443       | 8443       |

80 and 443 are remapped because macOS will not let a non-root process
bind privileged ports.

## What it does not test

- **Disks.** `storage-mount`, `mergerfs` and `snapraid` are all
  conditional in `site.yml` and default to off, so they skip. Lima can
  attach additional virtual disks, which is what would unblock the
  SnapRAID work, but that is not wired up here yet.
- **mDNS.** `avahi` installs and runs, but multicast does not cross the
  VM boundary, so `aurora.local` will not resolve from the Mac.
- **Real SMART data.** There are no physical drives.

## Choices worth knowing

**Debian, not Ubuntu.** `bootstrap.sh` is apt-based and `site.yml`
describes itself as Debian-family. Ubuntu diverges on netplan,
snap-packaged Docker and AppArmor defaults, which would make the testbed
lie about things that matter.

**The repo is copied, not bind-mounted.** The Mac's home directory is
mounted read-only and `up.sh` rsyncs out of it into the VM.
`bootstrap.sh` writes `inventory.ini`, `group_vars/all.yml`, per-package
`.env` files and `.state.yml` into the repo it is handed; that repo
should be a copy, so a test run cannot dirty the working tree.
`node_modules`, `target` and `dist` are excluded, which takes the sync
from 207MB to 17MB.

**The user is `bruce`.** `group_vars/all.yml` pins `home_user: bruce` and
`home_local_root: /home/bruce/aurora.local`. Lima's own user is named
after the macOS account (`bruce.taylor`), and the dot would not match, so
the provisioning step creates the real thing.

## What the first run found

Five bugs, none of which had anything to do with the testbed itself:

1. **`bootstrap.sh install` had never worked.** The host bootstrap ran
   the play with `--limit localhost`, but `_write_configs` writes an
   inventory whose only host is named after the hostname, and `site.yml`
   targets the `home_servers` group. The limit intersected to nothing,
   ansible refused to run, and `set -e` killed the install before any
   package came up.
2. **The docker apt repo was wrong on ARM.** The role mapped `x86_64` to
   `amd64` and passed everything else through, so an arm64 host asked for
   `arch=aarch64`, which matches no packages. Debian calls it `arm64`.
3. **Docker group membership was not picked up.** The role adds the user
   to the `docker` group, but the running shell's group set was fixed at
   login, so the very next step could not open `docker.sock`.
4. **The dashboard image could never build.** Its `build.context` was
   `.`, and compose resolves relative paths against the first `-f` file's
   directory, not each file's own. With `core` sorting first the context
   pointed at `packages/core` and the build got an empty directory. The
   `../../data` volume paths elsewhere are level-invariant and were fine.
5. **The image was always built for amd64.** `ARG TARGETARCH=amd64` gives
   a predefined build arg a default, which shadows the value BuildKit
   injects. On arm64 that fetched an amd64 `yq` binary that would not
   execute.

Numbers 2 and 5 only bite on ARM hosts, so they would not have shown up
on the Optiplex. Numbers 1, 3 and 4 would have hit any machine.
