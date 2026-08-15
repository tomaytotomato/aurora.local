# Samba image swap — progress log

Goal: replace `dperson/samba` (no release, no version tag at all, in
`packages/storage`, one of three mandatory core packages) with
`dockurr/samba`, the image the owner picked after comparing three
candidates. This follows on from the research already logged in
`dev/notes/catalogue-git-samba-progress.md` (Task 2), which looked at
`servercontainers/samba` (bundles Avahi) and `crazymax/samba` (mounted
YAML config) and left the swap unimplemented pending a decision.
`dockurr/samba` was not one of the two candidates in that research —
the owner brought a third option to this task, already vetted (active,
version-tagged, no Avahi/wsdd/nmbd, env-var configured).

## Starting state

`packages/storage/compose.yml` defined exactly **one** Samba share:

```
command: >-
  -u "${SAMBA_USER:-bruce};${SAMBA_PASS:-changeme}"
  -s "media;/media;yes;no;no;${SAMBA_USER:-bruce}"
```

Reading the `-s "name;path;browsable;readonly;guest;users;..."` fields:
name=`media`, path=`/media`, browsable=yes, readonly=**no** (read-write),
guest=**no** (authentication required), users=`${SAMBA_USER:-bruce}`.

This is the crux the task asked to check early: since there is only one
share, the swap does **not** need the mounted `smb.conf`/`users.conf`
route dockurr/samba also supports — env vars cover it exactly. Had a
second `-s` line existed, this would have needed the mounted-config
shape instead (own note: `manifest.yml`'s `post_install_notes` claimed
shares were "read-only... served as guest", which contradicts the actual
compose flags above — read-write, authenticated. That was already wrong
before this change; corrected as part of mapping the config across.)

Ports were `139:139` (NetBIOS) and `445:445` (SMB). Env: `TZ`, `USERID`,
`GROUPID`. Volume: `/home/bruce/media:/media` (hard-coded, not using
`${MEDIA_ROOT}` like every other package — pre-existing, left as-is;
not part of this task).

## dockurr/samba env-var scheme (confirmed via the image's own README)

`NAME` (share name), `USER`, `PASS`, `UID`, `GID`, `RW` (`true`/`false`).
Mount is `/storage`, not `/media`. Port is `445` only — no `139`, because
this image runs `smbd` only (no `nmbd`, no `wsdd`, no Avahi; its own
package list is just `samba` + `libauth-samba`).

## Mapping applied (`packages/storage/compose.yml`)

| dperson field | value | dockurr equivalent |
|---|---|---|
| `-s` name | media | `NAME=media` |
| `-u` user / `-s` users | `${SAMBA_USER:-bruce}` | `USER=${SAMBA_USER:-bruce}` |
| `-u` pass | `${SAMBA_PASS:-changeme}` | `PASS=${SAMBA_PASS:-changeme}` |
| `USERID`/`GROUPID` | 1000/1000 | `UID=1000`/`GID=1000` |
| readonly=no | read-write | `RW=true` |
| guest=no | auth required | (no guest env exists on this image — auth is always required, matching the previous behaviour exactly) |
| ports | 139, 445 | 445 only (139 dropped, nothing listens on it) |
| volume | `/home/bruce/media:/media` | `/home/bruce/media:/storage` |

`packages/storage/manifest.yml`: dropped the now-unused port-139 entry
from `ports:`, and corrected `post_install_notes` to say what the share
actually does (read-write, authenticated) instead of the stale
"read-only, guest" text.

`.env.example` for `packages/storage` was not read or modified — the
sandbox denies Bash/Read access to any `.env*` file in this worktree
(confirmed by testing the same denial on `packages/core/.env.example`,
an unrelated package, so it's a blanket rule, not something specific to
storage). Not needed anyway: `compose.yml`'s own `${SAMBA_USER:-bruce}`
defaults already show the two variables it declares (`SAMBA_USER`,
`SAMBA_PASS`), and those are unchanged by this swap.

## Existing data on a box that already has the old image

The share path inside the container changes from `/media` to
`/storage`, but the **host-side** bind mount (`/home/bruce/media`) is
identical before and after. A box that already has files under
`/home/bruce/media` keeps them exactly where they are — `docker compose
up` on the new image just re-mounts the same host directory at a
different point inside the container, which is invisible to anything
outside the container. Nothing is deleted, moved, or reformatted.
`UID`/`GID` stay `1000`/`1000`, so file ownership on disk does not
change either. The one visible difference to a user: the client-side
share name shown when browsing `\\aurora.local\` is `media` in both
cases (the compose change deliberately keeps `NAME=media` to match), so
existing saved shortcuts (`smb://aurora.local/media`) keep working
without needing to be re-added.

## Pin recorded (`packages/storage/pins.env.example`)

```
IMAGE_SAMBA=dockurr/samba:4.23.10@sha256:6164872d767ead51d4a6ee9f6449b0a3577f875cefcc85525f9e184dbd5da16d
```

Confirmed `:latest` and `:4.23.10` resolve to the identical
manifest-list digest (`sha256:6164872d767ead51d4a6ee9f6449b0a3577f875cefcc85525f9e184dbd5da16d`),
so this is not a downgrade from what a fresh pull gets today. Note on
Docker Hub tag naming: the GitHub *git* tag is `v4.23.10-r0`, but the
Docker Hub *image* tag for the same build is `4.23.10` (no `v`, no
`-r0` suffix) — confirmed by fetching both the Docker Hub tags page and
the GitHub tags page and comparing dates (both "10 days before check").
Multi-arch confirmed via `docker buildx imagetools inspect`:
`linux/amd64`, `linux/arm64`, `linux/arm/v7`.

## Static checks run locally

- `python3 -m yamllint` with the CI's inline config (`line-length: 160`,
  etc.) against `packages/storage/manifest.yml` — clean.
- JSON Schema validation (`jsonschema` + `Draft7Validator` against
  `.github/schema/manifest.schema.json`) against every `packages/*/manifest.yml`
  — all OK, including the edited `storage` one.
- `docker compose -f packages/core/compose.yml -f packages/storage/compose.yml config -q`
  — exit 0.
- `docker compose -f packages/core/compose.yml -f packages/privacy/compose.yml
  -f packages/media/compose.yml -f packages/storage/compose.yml config -q`
  — exit 0 (the other CI-checked combination that includes `storage`).
- Resolved config for the `samba` service confirmed the exact
  translation intended (`NAME=media`, `USER=bruce`, `PASS=changeme`,
  `UID=1000`, `GID=1000`, `RW=true`, volume `/home/bruce/media:/storage`,
  port `445` only).
- No shell scripts touched, so shellcheck is unaffected.

Not yet done at this point in the log: the testbed proof (clean
install, mount a share, read/write a file, confirm host Avahi
untouched, check the other `storage` service — MiniDLNA — still works).
See the next entry below for that.

## Testbed proof

Clean rebuild: `./dev/testbed/up.sh destroy`, then
`AURORA_TESTBED_PACKAGES="core dashboard storage" ./dev/testbed/up.sh
all`. First attempt at this was run backgrounded and the session
stalled waiting on it, losing track of whether it finished — corrected
by re-running with active foreground polling instead (short `sleep`
plus a status check each time, so the session never goes fully idle).

Once the install chain finished, `docker ps` in the VM:

```
caddy  Up About a minute (healthy)  caddy:2-alpine
minidlna  Up About a minute (healthy)  vladgh/minidlna:latest
aurora  Up About a minute (healthy)  aurora-dashboard:0.1.0
samba  Up About a minute (healthy)  dockurr/samba:latest
```

All four containers reached `healthy`. `samba` took about a minute to
flip from `health: starting` to `healthy` — its built-in health check
is `smbclient -L` against itself, and its own log confirms there is
exactly **one** share:

```
$ docker inspect samba --format '{{json .State.Health}}' | python3 -m json.tool
{
    "Status": "healthy",
    "FailingStreak": 0,
    "Log": [
        {
            "Output": "\n\tSharename       Type      Comment\n\t---------       ----      -------\n\tmedia           Disk      Shared\n\tIPC$            IPC       IPC Service (samba)\nSMB1 disabled -- no workgroup available\n"
        }
    ]
}
```

This confirms the "one share" reading of the old `dperson/samba`
config directly against the running container, not just against the
compose file: `media` is the only `Disk` share exported (`IPC$` is
Samba's own internal service, not a data share). The env-var swap was
sufficient — no share was dropped, and no mounted `smb.conf` was
needed.

### Authenticate, write, read back — the real proof

`smbclient` was missing from the Debian testbed image
(`apt-get install -y smbclient`). Credentials came straight from the
package's own generated `.env` on the VM (`SAMBA_USER=bruce`,
`SAMBA_PASS=<bootstrap-generated secret>` — bootstrap.sh generates a
real password per install, not the `changeme` compose default).

Listing shares, authenticated:

```
$ smbclient -L //127.0.0.1/ -U bruce%<pass> -m SMB3
	Sharename       Type      Comment
	---------       ----      -------
	media           Disk      Shared
	IPC$            IPC       IPC Service (samba)
SMB1 disabled -- no workgroup available
```

Write a file, list it, read it back as a separate operation, diff the
two copies:

```
--- smbclient put ---
putting file /tmp/roundtrip-src.txt as \roundtrip-proof.txt (49.8 kb/s) (average 49.8 kb/s)
--- smbclient ls after put ---
  roundtrip-proof.txt                 A       51  Sat Aug 15 19:41:01 2026
		41072836 blocks of size 1024. 33826464 blocks available
--- smbclient get ---
getting file \roundtrip-proof.txt of size 51 as /tmp/roundtrip-dst.txt (510000.0 KiloBytes/sec) (average inf KiloBytes/sec)
--- downloaded file content ---
aurora samba swap round-trip proof - dockurr/samba
--- diff (empty output = identical) ---
MATCH: content is byte-identical
```

And confirmed the write landed on the real host-side bind mount, not
just inside the container:

```
$ sudo ls -la /home/bruce/media/
-rwxr--r-- 1 bruce lima    51 Aug 15 19:41 roundtrip-proof.txt
$ sudo cat /home/bruce/media/roundtrip-proof.txt
aurora samba swap round-trip proof - dockurr/samba
```

Authentication, write, and read are all proven against the real
container, not assumed from a directory listing.
