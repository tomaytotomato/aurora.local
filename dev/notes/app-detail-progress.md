# App detail page — progress log

Scope: PackageDetail.vue and what it renders. Four faults from a real box.

## Fault 1 — core packages report the wrong state (DONE, backend)

Root cause was backend, not frontend. `GET /api/packages/{name}`
(`PackagesController.get()`) was wrapping the response in
`{package: {...}, env_example: "..."}`, but openapi.yaml's `PackageDetail`
schema (and the frontend's `PackagesApi.get()`, and the msw mock handler
in `src/mocks/handlers/packages.ts`) all expect a **flat** object.

Consequence on the wire: `detail.value.name`, `.enabled`, `.running` were
all `undefined` on the detail page for every package. That cascades:

- `isCorePackage(detail.value)` does `CORE_PACKAGES.has(p.name)` — with
  `name` undefined, this is always `false`, so `isCore` was `false` for
  identity/storage/core too.
- `removable` (`!isCorePackage`) was therefore `true`, so the "Add app"
  button rendered for core packages.
- The badge and "Docker — Not currently running" text read off
  `detail.enabled` / `detail.running`, both `undefined` → falsy → the
  DISABLED/"not running" branch, regardless of real state.

No test covered `GET /api/packages/{name}`'s response shape (only path
existence via `OpenApiConformanceTest`, not body schema), so this drifted
silently.

Fix: `PackagesController.get()` now returns the `Package` domain object
directly (flat), matching what the frontend and its mocks always expected.
Dropped the unused `env_example` field — nothing on the frontend ever read
it; env values come from the separate `GET /packages/{name}/env` endpoint.

Added `PackagesControllerTests` (backend) asserting the response is flat
and that a running+enabled core package reports both flags `true` at the
top level.

## Fault 2 — action button invisible until first tab click

Investigating next.

## Fault 3 — page width jumps between tabs

Investigating next.

## Fault 4 — control panel (Install/Disable/Start/Uninstall + status light)

Investigating next.
