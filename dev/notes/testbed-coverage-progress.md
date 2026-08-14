# Testbed package coverage — progress log

Working from `dev/testbed/README.md`. VM `aurora` already has `core` and
`dashboard` proven from the previous session (five bugs fixed, documented
in the README). This log tracks broadening coverage to the rest of the
19 packages under `packages/`.

Method: `AURORA_TESTBED_PACKAGES` is always the *cumulative* set of
packages proven so far, not just the new ones. `scripts/up.sh` runs
`docker compose ... up -d --remove-orphans` across exactly the compose
files it's given, so a partial list would tear down anything already
proven as an "orphan". Rounds only grow the set.

VM specs (`dev/testbed/lima.yaml`): 4 CPU, 6GiB RAM, 40GB disk, arm64
(Apple Silicon via vz). Noted up front because `ai`'s manifest asks for
8192MB min RAM alone — this VM cannot satisfy that regardless of image
pull time, so `ai` is a hardware-fit finding, not just a "didn't get to
it" gap.

## 2026-08-14

### Round 0 (inherited, not re-verified by me)
- `core`, `dashboard` — proven in the prior session per the README.

### Round 1 — cheap and central: privacy, storage, identity
Status: starting now.
