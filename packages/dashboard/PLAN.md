# PLAN — Backend-free frontend development

Goal: develop every dashboard screen with no Docker, no Spring, no
Postgres. `npm run dev:mock` boots the full app against an in-browser
mock of the whole `/api` surface, including the SSE streams.

Decisions taken (2026-08-06):
- **Keep TypeScript.** Components stay near-annotation-free `<script
  setup>`; the typed `api/` layer and `vue-tsc` guard the contract.
- **Theme by tokens only.** Port the shadcn preset `ae1BwMC` colour +
  radius values into `src/assets/main.css`. No `shadcn-vue init`, no CLI
  re-scaffold — the hand-built `ui/` kit already consumes these tokens.
- **openapi.yaml hand-authored** from the typed `src/api/*` modules.

## Phase 1 — Contract: `openapi.yaml`

`packages/dashboard/openapi.yaml`, OpenAPI 3.1. Covers every `/api`
route across the ten domains: auth, onboarding, packages, services,
containers, metrics, security, audit, system, mdns. Cookie auth
documented. The three SSE endpoints
(`/onboarding/launch/{id}/stream`, `/containers/events/stream`,
`/services` polling) documented as `text/event-stream`. Schemas lifted
verbatim from the existing TS interfaces.

## Phase 2 — Mock layer (MSW)

- `msw` dev dependency + `public/mockServiceWorker.js` worker.
- `src/mocks/handlers/*` — one file per domain, every route covered.
- `src/mocks/fixtures/*` — believable data, hand-editable to drive any
  UI state (degraded package, failed start, empty state, error path).
- `src/mocks/browser.ts` — worker setup.
- SSE mocked via a streaming `Response` body so the live checklist and
  container-event feed animate without a backend.

## Phase 3 — Wiring

- `npm run dev:mock` sets `VITE_USE_MOCKS=1`.
- `main.ts` awaits worker start behind the flag before `app.mount`.
  Tree-shaken out of production builds.

## Phase 4 — Theme

- Fetch `ae1BwMC` token values.
- Replace the colour + radius values inside the existing `@theme` /
  `[data-theme="dark"]` blocks in `src/assets/main.css`. Structure
  unchanged.

## Phase 5 — Verify

- `npm run test:unit` stays green (additive changes only).
- Manual: `npm run dev:mock`, walk onboarding + dashboard, confirm the
  SSE-driven Done checklist ticks over on mocks alone.
