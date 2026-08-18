# Serving the fields the app page already rendered — progress log

## The gap

`openapi.yaml` documented `readme`, `vhosts`, `envVars`, `backup`,
`sourceUrl` and `homepageUrl` on `PackageDetail`. `PackageDetail.vue`
read all six. The backend served none of them, and the MSW fixtures
supplied all of them — so the page that got reviewed on the 17th was a
page no real box could produce, and its Source and Docs buttons rendered
with nothing behind them.

## Where the premise was wrong

The plan called this "backend work, no design decisions outstanding".
Half right. Only `backup:` existed in any manifest (6 of 18);
`vhosts`, `source_url`, `homepage_url` and `readme` existed in **zero**,
so there was nothing to serialise. Each field needed a source deciding.

What they got, and why none of it is a manifest copy:

| Field | Source |
|---|---|
| `readme` | `packages/<name>/README.md`, verbatim (18/18 exist) |
| `vhosts` | `MdnsAliasService.discoverLabels`, domain appended |
| `envVars` | `.env.example` + manifest `required_env` |
| `backup` | manifest `backup:` block |
| `sourceUrl` / `homepageUrl` | new manifest fields, added to all 17 shipped packages |

`vhosts` reuses the mDNS discovery deliberately rather than parsing
`caddy.snippet` a second time. Those labels are what avahi actually
advertises, so deriving the page's list from anywhere else would let the
two disagree — a package could serve a hostname the page did not list.

## One record, two schemas

`Package` backs both `PackageSummary` and `PackageDetail`. The four
detail-only fields are null on the list path and populated by
`withDetail` on the detail path, with `@JsonInclude(NON_NULL)` on the
record so the list response omits them entirely.

That is not tidiness. `OpenApiConformance` fails any response carrying a
property its schema does not document, so serving those four everywhere
would have meant adding four more entries to its
`KNOWN_UNDOCUMENTED_RESPONSE_FIELDS` registry. That registry already
carries the five fields from the plan's item 5, and growing it to pay for
a feature is the one thing this change should not do. Omission also
handles `readme`, typed as a plain string, which would fail validation as
an explicit null.

A delegating 14-argument constructor was kept so adding six components
did not mean editing seven unrelated test files to pass six nulls each.

## Findings along the way

**`/packages/{name}/env` does not exist.** Specified at `openapi.yaml`
line 272 (GET and PUT), called by the frontend (`packages.env()`,
`packages.saveEnv()`), and `PackagesController`'s own javadoc asserts
that "env values come from the separate `/packages/{name}/env`
endpoint". There is no controller for it. One of the 13 unimplemented
endpoints, and a javadoc stating a fiction. Left alone here: that
endpoint serves *values* with secret masking, which is a different
concern from `envVars` serving *specs*. Flagged, not absorbed.

**`PackagesService.readEnvExample` had no caller** anywhere in main or
test — dead since the `env_example` wrapper was removed. It now has a
purpose.

**A manifest field the schema forbade.** `MdnsAliasService` has read
manifest `vhosts:` all along and the fake-repo fixture calls it "the
PREFERRED discovery path", but `manifest.schema.json` sets
`additionalProperties: false` and never listed it — so any real manifest
using the preferred path would have failed CI. Added to the schema.

**A comment that contradicted its own assertion.** Both
`MdnsAliasServiceTests.discoverLabels_notes_prefers_manifest_over_caddy_snippet`
and the fixture snippet claimed the manifest list *masks* the caddy
fallback and that `legacy` "does NOT appear" — three lines above an
assertion that it does. The code does a union, which is the behaviour you
want. Corrected the prose rather than the code.

## Secret classification is now defined twice

`looksLikeSecret` ports `NON_SECRET_PATTERN` and `SECRET_HINT_PATTERN`
from `scripts/rotate-secrets.sh`, which is the established answer to the
same question. Two copies of one rule, kept in step by hand. The
alternative was shelling out to bash to classify a string, which is
worse. Noted here because this is the same shape as the two launch
markers: the second definition looks harmless right up until they drift.

## Tests

13 new integration tests in `PackageDetailFieldsIntegrationTest`, written
against the spec before any of it worked — 9 failed on absent fields,
4 passed as the absent-case guards they also are. Plus a drift check that
every shipped manifest names an upstream source, because a new package
without one would silently ship a dead button and nothing else would
notice. `homepage_url` deliberately is not required: for a few packages
the repository is the documentation, and inventing a homepage to satisfy
a test would be worse than omitting the button.

Backend 790/790, frontend 523/523, typecheck clean, manifest schema and
yamllint green against the same validator CI runs.

## Not done

- `/packages/{name}/env` (above). The frontend's env editor still has no
  backend.
- `homepageTiles`, also on `PackageDetail`, is left unpopulated. Homepage
  was retired months ago; the field should probably come out of the spec
  rather than be served.
- Not yet seen on a real box. The testbed's dashboard image predates
  this change, so the fields are proven against the fake repo and the
  spec, not against a screen.
