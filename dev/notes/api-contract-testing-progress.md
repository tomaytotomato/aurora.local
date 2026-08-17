# API contract testing — progress log

Running log for `test/api-contract-conformance`. Append one entry per
commit-sized slice. If this work stops unexpectedly, this file plus the
git log on the branch is the full picture of what's done.

Baseline before any change: `mvn test` (Java 25) → 505 top-level test
methods reported by Surefire's own summary attributes, but that attribute
undercounts `@Nested` classes — the real count by counting `<testcase>`
tags directly is 729, 0 failures. Use the tag-count method, not the
Surefire XML root `tests=`/`failures=` attributes, for any total quoted
in this log; the root attributes are not reliable here.

## What already existed (read before writing anything)

- `OpenApiConformanceTest` (`backend/src/test/java/.../OpenApiConformanceTest.java`) —
  compares Spring's `RequestMappingHandlerMapping` against `openapi.yaml`'s
  `paths`. Fails if an implemented endpoint is undocumented; reports
  (does not fail) specified-but-unimplemented endpoints, since ~60 of
  those are deliberate. Says nothing about response or request **shape**.
- `scripts/check-openapi.py` + the `openapi-spec` CI job — structural
  lint on the spec itself ($ref targets exist, tags declared, every
  operation has a summary/responses). Does not touch the backend at all.
- `AuroraIntegrationTest` — the shared base for the "real Spring context,
  real SQLite, fakes only at the Docker/command boundary" integration
  suite. `resetTestWorld()` builds one `MockMvc` per test via
  `MockMvcBuilders.webAppContextSetup(...).apply(springSecurity()).build()`.
  This is the natural place to validate bodies: it already exercises most
  endpoints, and it is one method, so wiring something in here reaches
  every test that extends it for free.
- The `backend` CI job runs plain `./mvnw test` (registry-tagged tests
  excluded); anything added to the default test source set runs there
  with no extra CI wiring needed.

## Mechanism chosen

A `ResultMatcher` (`OpenApiConformance`), wired in via
`MockMvcBuilders...alwaysExpect(OpenApiConformance.conformsToSpec())` in
`AuroraIntegrationTest.resetTestWorld()`. This means every request and
response body any integration test already exercises gets checked
against `openapi.yaml`'s JSON Schema automatically — no test author has
to remember to call anything. That "automatic beats opt-in" property is
the point: an opt-in helper is exactly the shape of thing that would
have let `PackagesController.get()`'s wrapper ship unnoticed for months.

Library: `com.networknt:json-schema-validator` 1.5.9 (test scope).
Chosen over Atlassian's `swagger-request-validator` (now
`openapi-request-validator`) because that project's OpenAPI 3.1 support
is unconfirmed as of writing and it drags in its own Swagger parser on
top of the SnakeYAML parsing this repo already does; networknt is small,
actively maintained, and implements JSON Schema draft 2020-12 —
Aurora's spec is `openapi: 3.1.0`, which uses 2020-12 schema semantics
(`type: [X, 'null']` unions, not the 3.0 `nullable: true` flag) — so no
translation layer is needed between the spec and the validator.

`OpenApiSpec` (package-private support class) loads `openapi.yaml` once
per JVM with SnakeYAML, converts it to a Jackson tree, and rewrites every
`#/components/schemas/X` ref anywhere in the document to `#/$defs/X` so
any subschema pulled out of the tree stays independently resolvable.
Exposes `requestSchema`/`responseSchema` (path-template matched via
Spring's own `PathPatternParser`/`PathContainer`, so `{name}` vs `{id}`
naming differences don't matter) and `validationSchemaFor`, which builds
the actual JSON Schema document handed to the validator.

## Strictness decisions

- Required properties, types, enums, nullability: full 2020-12 checking,
  no changes needed — already correct out of the box.
- Extra properties the spec never documents: rejected, via
  `unevaluatedProperties: false` injected at the **root** of the body
  being validated, and (for a list response) at the shape of **each
  item**. Deliberately not `additionalProperties: false` and deliberately
  not recursive beyond that one level.
  - Why not `additionalProperties`: several response schemas (e.g.
    `PackageDetail`) are an `allOf` of a shared base (`PackageSummary`)
    plus extra fields. `additionalProperties: false` is evaluated
    per-branch in isolation, so each branch would reject the other
    branch's fields and the composition would never validate anything.
    `unevaluatedProperties` is evaluated once across the whole `allOf`,
    which is the one that actually composes.
  - Why not recursive: the same component schema (`PackageSummary`) is
    also used standalone (list endpoint items). Injecting the keyword
    into the shared definition itself would make it fire using only that
    definition's own local view when reused inside `PackageDetail`'s
    `allOf`, rejecting `PackageDetail`'s own extra fields as
    "unevaluated" — the exact composition trap one level removed. Adding
    it fresh, per use site, only at the outermost shape avoids the trap
    entirely; going deeper (e.g. into `PackageDetail.backup`) would
    reopen the same problem for any schema shared between a standalone
    and a nested use. Documented as a scope limit: nested objects still
    get full type/required/enum checking, just not the "nothing
    undocumented" check at depth.
- A schema with its own explicit `additionalProperties` (`true`, or a
  typed dictionary): left alone. Several endpoints deliberately return
  free-form maps (env vars, labels, per-container resource stats) and
  say so in the spec; this respects that instead of fighting it.
- Format assertions (`format: date-time` etc.): not enforced, annotation
  only. This check is about shape drift, not a general spec linter.
- A request body on a call that got rejected (non-2xx): not checked. A
  test that deliberately sends an invalid enum or a malformed file to
  prove the backend 400s is not describing drift — the body is invalid
  on purpose. Only an **accepted** request is compared to the spec's
  idea of a valid one.
- An undocumented status code, or a documented one with no schema (most
  4xx responses in this spec are description-only): skipped, not failed.
- An endpoint the backend implements but the spec never mentions: left
  to `OpenApiConformanceTest`, which already fails the build for that.

## Requests

Covered, via the same `ResultMatcher` — see "strictness decisions"
above for the 2xx-only scoping. Not a parallel mechanism; one class
handles both directions.

## Genuine contract violations found while building this

`openapi.yaml` is out of bounds for this piece of work (owned by a
separate, concurrent change) so none of these are fixed here — reporting
them is the deliverable for this section. Each is deliberately not
silently accepted: they're either stripped-and-reported at the field
level (`KNOWN_UNDOCUMENTED_REQUEST_FIELDS`) or logged loudly to stderr on
every run (`KNOWN_GAPS`), never just swallowed.

1. **`POST /onboarding/admin` sends `tz`, undocumented.** The backend
   reads it (`OnboardingService.createInitialAdmin`, sets the box's
   timezone) but the spec's requestBody only lists `username`/`password`.
   Small, single-field fix once someone can touch the spec.
2. **`PATCH /notifications/channels/{id}`'s requestBody is the full
   `ChannelDraft`** (`required: [kind, name, target, events]`), but the
   operation is a partial update ("Change a channel, or mute it") and the
   backend correctly accepts e.g. `{"enabled": false}` alone. Needs a
   proper partial-update schema, not a one-line fix.
3. **`POST /system/import`'s requestBody is `SettingsExport`**, the same
   schema as the export response, requiring `exportedAt, hostname,
   domain, profiles, dnsMode, settings`.
   `SettingsPortabilityController.importSettings` only ever reads
   `version`, `enabledPackages` and `domain` — everything else is
   genuinely optional in practice. The same reused-too-strict schema also
   types `enabledPackages` as `items: {type: string}`, but the controller
   deliberately tolerates junk in that array
   (`if (o instanceof String s && !s.isBlank())`) and a test pins exactly
   that (`ignores_junk_in_the_package_list_rather_than_writing_it`, body
   `["core", null, "", 42]`). Needs a dedicated, more honest
   `ImportRequest` schema throughout, not just the required list.
4. **`GET /disks/{id}/smart -> 200`'s `collectedAt` is a required plain
   `string`**, but a disk with no SMART support has nothing to collect
   and the backend correctly answers `collectedAt: null` rather than
   404ing the whole resource. Needs `type: [string, 'null']`.

## Log

### 2026-08-15 — mechanism landed, wired in, 7 known gaps logged not failed

`pom.xml`: added `com.networknt:json-schema-validator:1.5.9` (test
scope). `OpenApiSpec` + `OpenApiConformance` written under
`support/`. Wired into `AuroraIntegrationTest.resetTestWorld()` via
`alwaysExpect`.

First full run against the wired-in matcher surfaced 12 real failures,
none of them the mechanism being wrong: 5 were the `tz` gap (#1 above,
same root cause repeated across `OnboardingLaunchOrderingIntegrationTest`
methods), and the rest split across #2–#4 above plus negative-path tests
(deliberately-invalid enum values, a wrong-version import file) that only
failed because the first cut validated request bodies regardless of
response status. Added the 2xx-only scoping for requests, which cleared
the negative-path cases outright (they were never real drift), and added
the two exemption registries for the four genuine, structural gaps.
`mvn test`: 729 test methods (by tag count), 0 failures, 7 "known gap"
warnings on stderr (4 `/system/import`, 2 notifications `PATCH`, 1 disks
`smart`) — matches the four cataloged violations above exactly (the
`tz` gap fires 5 times across `OnboardingLaunchOrderingIntegrationTest`
but is stripped per-field rather than logged as a gap, so it does not
appear in that count).

Wrote `PackagesControllerIntegrationTest` — the endpoint from the
motivating bug report had **no** coverage running through
`AuroraIntegrationTest` at all; the only existing test
(`PackagesControllerTests`) is a standalone MockMvc with a mocked
`PackagesService`, which never reaches the new matcher. Compiles;
about to prove it catches the historical bug next, then confirm the
full suite is still green.

Not yet done: the acceptance-criterion demonstration (reintroduce the
`{package, env_example}` wrapper, show the new test fails, revert), and
the final write-up.

### 2026-08-15 — merged main + feat/package-lifecycle-endpoints

Coordinator flagged that `feat/package-lifecycle-endpoints` (install/
stop/uninstall on packages) landed since this branch started and was
not yet in `main`. Merged `origin/main` (clean, no conflicts — mostly
unrelated pins/docs work), then `origin/feat/package-lifecycle-
endpoints` directly (also clean — it branched from the same commit this
work did, so no shared history to reconcile). That branch adds its own
`PackagesLifecycleControllerIntegrationTest` extending
`AuroraIntegrationTest`, so it picks up `OpenApiConformance`
automatically with no changes needed on this side.

`mvn test` after both merges: 747 tests, 0 failures, 8 known-gap
warnings (unchanged set — the new lifecycle endpoints introduced no new
drift). Confirms the mechanism composes with concurrent work rather
than needing to be re-taught anything.

### 2026-08-15 — found a hole in my own known-gaps design, fixed it, then ran the acceptance demonstration

Went to reintroduce `PackagesController.get()`'s old `{package,
env_example}` wrapper to prove the mechanism catches it (the whole
point of this piece of work) and it **passed** — silently. Cause: the
first cut of the "known gap" registry (`KNOWN_GAPS`, a
`Set<String>` of `"RESPONSE GET /packages/{name} -> 200"` etc.) was
keyed by **operation**, not by the specific violation. It existed to
tolerate `Package` serialising five undocumented fields
(`recommends`/`profiles`/`requiredEnv`/`postInstallNotes`/`sso`); once
that operation was in the set, *any* problem on it — including the
wrapper — was logged and waved through. Exactly the failure mode the
brief warned about: "a validator that passes everything is worse than
none."

Replaced the whole-operation registry with field- and schema-precise
ones, each doing the smallest transform that neutralises exactly the
catalogued gap and nothing else:

- `KNOWN_UNDOCUMENTED_REQUEST_FIELDS` / `KNOWN_UNDOCUMENTED_RESPONSE_FIELDS`
  — strip a named field from a copy of the instance before validating
  (already had the request-side version for `tz`; added the
  response-side twin for the `Package` fields).
- `KNOWN_NULLABLE_RESPONSE_FIELDS` — replace a `null` in a named field
  with a placeholder of the same JSON type before validating (disks
  `collectedAt`), so a response that dropped the field outright still
  fails its `required` check.
- `KNOWN_RELAXED_REQUIRED` — new overload
  `OpenApiSpec.validationSchemaFor(rawSchema, Map<schemaName, fieldsToRelax>)`
  that deep-copies just the named `$defs` entries and strips the named
  fields from *that schema's own* `required` array before building the
  validation document (notifications `PATCH`, system `import`) — scoped
  to one named schema so it can't quietly loosen an unrelated one that
  happens to require a field with the same name.
- `KNOWN_JUNK_TOLERANT_ARRAY_FIELDS` — drop non-string entries from a
  named array field before validating, mirroring exactly what
  `SettingsPortabilityController.importSettings` itself does with them.
  Precision paid off immediately: turning on the relaxed-required
  version alone (without this) surfaced a *sixth* real, previously
  hidden violation — `ignores_junk_in_the_package_list_rather_than_writing_it`
  sends `["core", null, "", 42]` for `enabledPackages`
  (`items: {type: string}`), which the whole-operation registry had
  also been silently swallowing. Folded into finding #3 above rather
  than counted separately — same root cause (`SettingsExport` reused
  as a stricter-than-reality requestBody).

No entry now silences more than the one thing it names. `mvn test`:
747 tests, 0 failures — same as before, but for the right reason this
time. The known gaps no longer print a stderr warning (the previous
version did); precision made that less necessary; the javadoc on each
registry plus this log are the discoverability path instead.

**Acceptance-criterion demonstration.** Temporarily changed
`PackagesController.get()` back to
`Map.of("package", p, "env_example", "")`, ran
`PackagesControllerIntegrationTest` alone:

```
openapi.yaml conformance failure — response body for GET /packages/notes -> 200 does not match its documented schema:
  - $: required property 'name' not found
  - $: required property 'category' not found
  - $: required property 'description' not found
  - $: required property 'enabled' not found
  - $: required property 'running' not found
  - $: property 'env_example' is not evaluated and the schema does not allow unevaluated properties
  - $: property 'package' is not evaluated and the schema does not allow unevaluated properties
	at com.tomaytotomato.aurora.support.OpenApiConformance.validate(...)
	at com.tomaytotomato.aurora.support.OpenApiConformance.checkResponse(...)
	at com.tomaytotomato.aurora.support.OpenApiConformance.match(...)
	at org.springframework.test.web.servlet.MockMvc.applyDefaultResultActions(...)
```

The failure is thrown from `OpenApiConformance.validate`, reached via
`MockMvc.applyDefaultResultActions` — the automatic path, before the
test's own explicit `jsonPath` assertions even run. Reverted the
controller with `git checkout --`; `mvn test` back to 747/0.

Final state for this piece of work: mechanism landed, composes with
concurrent branches, demonstrably catches the motivating bug via the
automatic path (not the hand-written one), five (now six, same three
findings) genuine gaps catalogued precisely rather than silently
accepted or over-broadly suppressed. Write-up for the calling agent
next.

**Addendum (onboarding-session-guard piece of work):** a new
integration test exercising `GET /auth/me` inside `AuroraIntegrationTest`
(the first one to call that endpoint through the conformance-checked
`mvc`) turned up a seventh gap: `Session.role` (present on the record
since Phase D, well before `GET /auth/me` was itself added to the spec
in this file's own commit) is missing from the spec's `Session` schema.
Same gap on `GET /auth/session` — untested by name so far, but it is
the identical response record. Carved out narrowly as
`"GET /auth/me -> 200", Set.of("role")` in
`KNOWN_UNDOCUMENTED_RESPONSE_FIELDS`, same pattern as the `/packages/{name}`
entry above. `role` is genuinely read by the frontend (gates the
`/users` nav link and admin-only routes), so this wants a spec fix, not
a response trim.
