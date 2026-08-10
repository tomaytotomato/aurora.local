# Disks page — design spec

The host roles landed first: `mergerfs` unions the data disks into one
pool at `/mnt/storage`, `snapraid` computes parity across them onto a
dedicated parity disk, and `smartd` watches every drive's SMART
attributes. All three are configured in `group_vars`, all three report to
syslog, and none of them appear in the dashboard.

That is the wrong way round for the one thing that actually matters here:
**a disk gives you weeks of warning before it dies, and you only get that
warning if something is looking.**

## 0. What this page is for

1. **Is a disk about to fail?** Reallocated sectors climbing, a failed
   self-test, a drive running hot.
2. **Am I running out of room?** Pool capacity, and which branch is
   filling up.
3. **Is parity actually current?** SnapRAID is snapshot parity, not
   real-time RAID. A sync that has not run for three weeks means three
   weeks of new files are unprotected, and nothing on the box says so.

## 1. Where it lives

`/disks`, in the sidebar under Backup. Gated on
`SystemCapabilities.disks`, false until the backend can read smartctl and
the mergerfs/snapraid state. Same rule as everywhere else: hidden while
it cannot be honest.

## 2. Page states

| State | When |
|---|---|
| `no-pool` | one disk, no mergerfs. Still shows SMART for that disk |
| `healthy` | pool mounted, every disk passing, parity fresh |
| `attention` | a disk warning, a full branch, or parity stale |
| `failing` | a disk reporting failing SMART, or a parity sync that aborted |

`no-pool` is a first-class state, not an error. Plenty of Aurora boxes
are one 4 TB drive and that is a perfectly reasonable thing to be.

## 3. Layout

### 3.1 Header

Standard `on-photo` header. `Sync parity now` sits top right when parity
is configured; it creates a job and streams through `JobLogPanel`.

### 3.2 Tabs

`Overview` · `Drives` · `Parity`.

**Overview.** Pool capacity as the hero: used against total, plus the
per-branch bar underneath, because "the pool is 78% full" hides the fact
that one disk is at 99% and mergerfs's `minfreespace=20G` is about to
stop putting new files on it. Then a row of small cards: drive count and
worst health, parity freshness, and the largest single-disk risk (the
biggest disk whose loss parity could not cover, if any).

**Drives.** One row per physical disk: model, serial, size, role (data /
parity / system), mountpoint, used, temperature, power-on hours,
reallocated and pending sectors, last self-test and its result. Sorted
worst health first, because that is the row you came for.

Reallocated sectors get particular treatment: any non-zero value is shown
in a warning tone even when SMART overall still says PASSED, because
SMART overall status is famously optimistic and a drive reallocating
sectors is a drive on its way out.

**Parity.** When the last sync ran, when the last scrub ran, how many
files have changed since, and how many have been deleted since. That
deletion count is the important one: the `snapraid-runner` script aborts
the sync when deletions exceed `snapraid_delete_threshold` (200 by
default), which is a good guard rail and a terrible silent failure. If
the runner has aborted, this page says so in plain words, shows the
count against the threshold, and explains that this is deliberate.

## 4. Honesty rules

- Never show a pool percentage without the per-branch breakdown.
- Never call parity "protected" when the last sync failed or aborted.
- SMART "PASSED" with non-zero reallocated sectors reads as a warning,
  not as a pass.
- Temperature is shown without judgement unless it is above 55°C; drives
  run warm and a colour-coded thermometer on every row is noise.
- A drive with no SMART support (VM, USB enclosure) reads `unknown`, not
  `passed`.

## 5. API surface

| Method | Path | Purpose |
|---|---|---|
| GET | `/disks` | one row per physical disk |
| GET | `/disks/{id}/smart` | full attribute table for one disk |
| GET | `/disks/pool` | mergerfs pool and its branches |
| GET | `/disks/parity` | SnapRAID state |
| POST | `/disks/parity/sync` | run a sync → `JobRef` |
| POST | `/disks/parity/scrub` | verify parity → `JobRef` |

## 6. Out of scope

Formatting, partitioning, mounting, adding a disk to the pool, changing
the parity configuration. All of that is `group_vars` plus an Ansible
run, and it should stay that way: a UI that can repartition a disk is a
UI that can destroy a library at 11pm. This page reads, and it runs the
two SnapRAID operations that are safe to run at any time.
