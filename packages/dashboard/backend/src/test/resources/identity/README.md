# Test-resource snapshot of `packages/identity/authelia/configuration.yml`

Copied verbatim from the real file. Used by
`AutheliaConfigurationInvariantsTests` because the maven container
in `scripts/verify-v03-overnight.sh` only mounts
`packages/dashboard/backend/`, so the sibling `packages/identity/`
tree is not reachable from a running JUnit test.

**Keep in sync.** When you edit
`packages/identity/authelia/configuration.yml`, mirror the change
here. The `AutheliaConfigurationInvariantsTests.snapshot_matches_source`
test also compares the two files when both are visible (i.e. during
local `mvn test` outside the docker sandbox) and screams if they
drift.
