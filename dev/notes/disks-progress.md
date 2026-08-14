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

### Still to do
- Ansible: extend `host/roles/smartd` with the collector script + timer,
  extend `host/roles/snapraid`'s runner + add the `aurora-parity-action`
  helper.
- Tests: `DisksStateParserTest` (plain unit, real smartctl-shaped fixtures),
  `DisksControllerIntegrationTest` (`AuroraIntegrationTest`, all six
  endpoints, the page states from the design doc, missing/stale file
  cases, FakeCommandRunner assertions on the two actions).
- Run the full backend suite; confirm `OpenApiConformanceTest` stays green.
