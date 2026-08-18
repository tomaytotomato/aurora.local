# Test-resource snapshot of `packages/identity/authelia/configuration.yml`

Copied verbatim from the real file. Used by
`AutheliaConfigurationInvariantsTests` because a sandbox that mounts
only `packages/dashboard/backend/` cannot reach the sibling
`packages/identity/` tree from a running JUnit test. (This used to cite
`scripts/verify-v03-overnight.sh` as the example; that script has been
deleted, superseded by CI's own frontend/backend/backend-contract jobs.)

**Keep in sync.** When you edit
`packages/identity/authelia/configuration.yml`, mirror the change
here. The `AutheliaConfigurationInvariantsTests.snapshot_matches_source`
test also compares the two files when both are visible (i.e. during
local `mvn test` outside the docker sandbox) and screams if they
drift.
