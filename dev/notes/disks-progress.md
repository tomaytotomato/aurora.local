# /disks backend — progress log

Branch `feat/backend-disks`. Working from the brief: host roles write
state, the dashboard reads it; two SnapRAID actions go through
CommandRunner against one allow-listed helper.

## 2026-08-14 — slice 1: domain + parser + controller (backend)

Read `openapi.yaml`'s `/disks/*` paths and schemas, `DISKS_PAGE_DESIGN.md`,
and the frontend mock handlers/fixtures (`frontend/src/mocks/handlers/disks.ts`,
`frontend/src/mocks/fixtures/disks.ts`) plus `frontend/src/api/disks.ts` for
the honesty-rule logic already encoded there (`diskAttention`, `selfTestFailed`
with the "without error" exclusion — this is the named regression).

Designed the state file format (see below), wrote:

- `domain/Disk.java`, `SmartAttribute.java`, `DiskSmart.java`, `PoolBranch.java`,
  `Pool.java`, `Parity.java` — records matching the openapi schemas field for
  field (role/health/lastSyncState are plain Strings with the wire enum
  values, not Java enums, so Jackson serialises them with no extra config).
- `services/DisksStateParser.java` — package-private, no Spring/filesystem
  dependency, takes a Jackson `JsonNode` and produces domain objects. This is
  where the anti-regression logic lives: `health()` reads only
  `smart_status.passed` (boolean) and raw sector counts (5 = reallocated,
  197 = pending); `lastSelfTestResult` is carried through as display text and
  never inspected by any pass/fail check.
- `services/DisksService.java` — Spring `@Service`. Reads
  `packages/dashboard/state/disks.json` and `.../parity.json` under
  `aurora.repo-path`, hands the parsed tree to the parser. Pool branches and
  parity disk ids are *derived* from the disks list (role == data / parity)
  rather than duplicated in the state files — one source of truth for which
  disks are in the pool. Missing or malformed files degrade to the parser's
  own defaults (empty list, `configured: false`, `lastSyncState: "never"`)
  rather than throwing.
- `controllers/DisksController.java` — the six endpoints. Sync/scrub POST
  through `JobService.submitCommand(PARITY_SYNC/PARITY_SCRUB, ...)` with a
  fixed argv (`/usr/local/bin/aurora-parity-action sync|scrub`) — same shape
  as `LaunchService`/`UpdatesService`'s existing job-backed actions.
- `SystemService.info()`: one-line addition, `capabilities.put("disks", true)`.

Compiles clean (`JAVA_HOME` needs the JDK25 Temurin install; the jenv shim on
this box defaults to 21, which fails `--release 25`).

### State file format chosen

`packages/dashboard/state/disks.json` (written by `host/roles/smartd` on a
timer):

```json
{
  "collectedAt": "2026-08-14T02:15:03Z",
  "disks": [
    {
      "id": "ata-WDC_...", "device": "/dev/sdb", "model": "...", "serial": "...",
      "sizeBytes": 8001563222016, "role": "data", "mountpoint": "/mnt/disk1",
      "filesystem": "ext4", "usedBytes": 5263670148096,
      "smart": {
        "supported": true,
        "smart_status": {"passed": true},
        "power_on_time": {"hours": 31204},
        "temperature": {"current": 38},
        "ata_smart_attributes": {"table": [{"id":5,"name":"Reallocated_Sector_Ct","value":200,"worst":200,"thresh":140,"raw":{"value":0,"string":"0"}}, ...]},
        "ata_smart_self_test_log": {"standard": {"table": [{"type":{"string":"Short offline"},"status":{"string":"Completed without error"},"lifetime_hours":31180,"computed_at":"2026-08-10T02:00:00Z"}]}}
      }
    }
  ],
  "pool": {"configured": true, "mountpoint": "/mnt/storage", "createPolicy": "mfs", "minFreeBytes": 21474836480}
}
```

Why this shape: the `smart` sub-object mirrors `smartctl -a -j`'s own field
names (`smart_status`, `ata_smart_attributes.table[]`, `power_on_time`,
`temperature`, `ata_smart_self_test_log`) rather than a pre-digested shape,
so the Java parser is exercised against something close to the real tool's
output, per the brief ("test your parser against real smartctl output
shapes"). `computed_at` on the self-test entry is synthetic — smartctl only
gives `lifetime_hours` (hours-since-poweron), no wall-clock date — the
collector script derives an approximate timestamp from
`now - (current_power_on_hours - test_lifetime_hours)` hours, assuming
continuous power (reasonable for a box that doesn't sleep its disks).

`packages/dashboard/state/parity.json` (written by `host/roles/snapraid`'s
runner + the new on-demand helper):

```json
{
  "collectedAt": "...", "lastSyncAt": "...", "lastSyncState": "ok",
  "lastScrubAt": "...", "pendingChanges": 1842, "deletedSinceSync": 17,
  "deletionThreshold": 200, "stalenessWarnDays": 3
}
```

`Parity.configured` and `parityDiskIds` are *not* read from this file — they
come from `disks.json`'s `role == "parity"` disks, because a box can be
configured for parity and simply never have synced yet (the `never` state),
which is different from parity not being configured at all, and deriving it
from disk roles gets both right without an extra flag to keep in sync.

`Pool.configured` **is** an explicit field in `disks.json`, unlike parity,
because `disks.json` is written unconditionally by `smartd` (which defaults
to enabled) regardless of whether mergerfs is — so its presence doesn't
imply pool configuration the way `parity.json`'s presence implies snapraid
is enabled.

## 2026-08-14 — slice 2: Ansible (smartd + snapraid)

`host/roles/smartd`: added `aurora-disk-state.sh.j2` (the collector),
`aurora-disk-state.service.j2` + `.timer.j2` (every 15 minutes), and the
tasks/handlers/defaults to deploy and enable them, gated on
`smartd_disk_state_enabled` (default true). The collector's `smart`
sub-object mirrors `smartctl -a -j`'s own field names. Disk role
(data/parity/system/unassigned) comes from matching each mount against
`mergerfs_branches` / `snapraid_parity_files` — read as plain Ansible
variables, never written to, so this stays inside the "own smartd +
snapraid only" boundary. `disk_pool_create_policy` /
`disk_pool_min_free_bytes` are new defaults here that mirror
`mergerfs_options` by hand (documented as such) rather than regex-parsing
that options string, which felt like the wrong kind of clever for a
homelab tool.

One real bug caught while smoke-testing: the collector originally gated
"did we get usable SMART data" on smartctl's own exit code. smartctl's
exit code is a bitmask of health/attribute warnings, not a success flag —
a perfectly readable drive with any history of SMART issues returns
non-zero. That would have quietly marked plenty of real drives
"unsupported". Fixed to judge success from the JSON content
(`has("smart_status")`) only. Same family of mistake as the named
regression, one layer down the stack from where the ticket pointed.

`host/roles/snapraid`: extended `snapraid-runner.sh.j2` to write
`parity.json` after every diff/sync/scrub outcome (ok/failed/aborted,
pending/deleted counts parsed from `snapraid diff`'s own summary),
preserving `lastScrubAt` across a sync-only run. Added
`aurora-parity-action.sh.j2`, the one script `CommandRunner` invokes for
the two dashboard actions: `sync` re-execs the existing guarded runner
unchanged (no duplicated deletion-threshold logic); `scrub` runs
`snapraid scrub` directly and patches `lastScrubAt` into the same file.

Verification used: `ansible-playbook --syntax-check`, a scratch playbook
rendering every new/changed template with representative vars (checks the
Jinja filters — `to_json`, `dirname`, `lower` — actually resolve), `bash
-n` on every rendered script, and hand-built fake `smartctl`/`lsblk`/`df`/
`snapraid` binaries on `PATH` to run the real jq pipelines end-to-end
(block-device detection itself can't be exercised outside a real host —
no `/dev/sdX` to open — so that one line (`[[ -b "$dev" ]]`) is trusted by
reading, not by running).

## 2026-08-14 — slice 3: tests + full suite

`DisksStateParserTest` (21 cases) and `DisksControllerIntegrationTest`
(17 cases, `AuroraIntegrationTest`) — see the commit messages for what
each covers. One real bug caught here too: `DisksService` originally
constructor-injected a Spring `ObjectMapper` bean that doesn't exist in
this app (nothing else in the codebase uses one — Jackson is on the
classpath as a library, never registered as a bean), which failed
context startup with `UnsatisfiedDependencyException`. Fixed to
instantiate its own, matching how the test classes already do this.

Full suite: `mvn test` → **581/581 passing** (543 before this branch +
21 + 17). `OpenApiConformanceTest`'s "not yet implemented" list no
longer contains any `/disks/*` path.

### What was not, and could not be, tested here
- Real `smartctl`/`lsblk`/`df`/`snapraid` binaries against real disks —
  this sandbox has neither the binaries nor block devices. The jq
  pipelines were smoke-tested standalone against hand-built fixture JSON
  (see slice 2); the shell glue around them (`[[ -b "$dev" ]]`,
  `/dev/disk/by-id` resolution, `df` on a real mountpoint) is reviewed by
  reading, not exercised.
- The systemd timer actually firing on a schedule, or `daemon_reload`
  actually picking up new unit files — `ansible-playbook --syntax-check`
  and template rendering only prove the YAML/Jinja is well-formed, not
  that systemd accepts the unit files.
- `smartctl -j` against a real NVMe drive. The collector's `smart_json`
  jq filter is ATA-shaped (`ata_smart_attributes`, `ata_smart_self_test_log`);
  an NVMe drive's real smartctl JSON uses different top-level keys
  (`nvme_smart_health_information_log`) that this filter doesn't
  recognise, so an NVMe disk will come back `supported: false` even
  though it does have SMART-equivalent data. Same honest-fallback
  behaviour as a USB enclosure, but for the wrong reason — worth a
  follow-up if any Aurora box actually runs NVMe data disks.
- The `dev/testbed` Lima VM was explicitly off-limits (another agent has
  it), so nothing here ran inside an actual container against the actual
  compose stack.

### What a reviewer should check hardest
1. `DisksStateParser.health()` — this is the whole point of the ticket.
   Confirm it never reads `lastSelfTestResult`/self-test text for
   anything other than display.
2. The `[[ -b "$dev" ]]` gate and `/dev/disk/by-id` resolution in
   `aurora-disk-state.sh.j2` — untested by anything other than review,
   per above.
3. Whether deriving `Pool`/`Parity` identity from `disks.json`'s `role`
   field (rather than storing `parityDiskIds`/branches directly) is the
   right call long-term — it removes a duplication but means a
   `disks.json` collection failure quietly also blanks pool/parity
   identity even if `parity.json` itself is fine.
4. The NVMe gap above.
