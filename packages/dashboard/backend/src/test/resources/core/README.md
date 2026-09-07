# Test-resource snapshot of `packages/core/authelia/configuration.yml`

Copied verbatim from the real file. Used by
`AutheliaConfigurationInvariantsTests` because a sandbox that mounts
only `packages/dashboard/backend/` cannot reach the sibling
`packages/core/` tree from a running JUnit test. (This used to cite
`scripts/verify-v03-overnight.sh` as the example; that script has been
deleted, superseded by CI's own frontend/backend/backend-contract jobs.)

**Keep in sync.** When you edit
`packages/core/authelia/configuration.yml`, mirror the change
here. The `AutheliaConfigurationInvariantsTests.snapshot_matches_source`
test also compares the two files when both are visible (i.e. during
local `mvn test` outside the docker sandbox) and screams if they
drift.

## This file is a Go template, not plain YAML

Authelia runs it through its own template filter
(`X_AUTHELIA_CONFIG_FILTERS=template`) at container start. Two
consequences for anyone editing it:

1. **Value-level refs** like `domain: '{{ env "DOMAIN" }}'` sit inside
   quoted scalars, so they parse as ordinary strings. Several
   invariants assert on their exact text — don't "tidy" them away.
2. **Control-flow lines** (`{{- if ... }}` / `{{- else }}` /
   `{{- end }}`) are structural and are *not* valid YAML. The test's
   `load()` strips them before parsing. If you add a new control-flow
   construct, make sure `TEMPLATE_CONTROL_LINE` there still matches it,
   or the invariants will start failing with a SnakeYAML parse error
   rather than a useful message.

The `notifier` block is the current reason for the conditional: the
`smtp` arm when Aurora has provisioned mail on the box's own Stalwart,
the `filesystem` arm when it has not. Having both live at once is a
fatal Authelia config error, which is why it is a template rather than
two static keys.
