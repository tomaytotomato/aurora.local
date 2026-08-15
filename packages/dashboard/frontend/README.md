# Aurora dashboard — frontend

Vue 3.5 · Vite 8 · TypeScript strict · Tailwind 4 · shadcn-vue conventions.

Aurora is the dashboard for [`aurora.local`](../../../README.md) — the
single landing page at the root domain, and the fuse box behind it.

## Layout

```
src/
  api/          typed clients — client, system, packages, auth, onboarding, events (SSE)
  stores/       Pinia — auth, system, packages, onboarding, events
  router/       vue-router, nested onboarding routes, auth guard
  components/
    ui/         Button, Input, Card, Alert, Badge, Progress, Tabs, Checkbox, Label
                (shadcn-vue-shaped hand-writes, Reka UI ready for headless primitives)
    layout/     AppShell, Sidebar, TopBar, OnboardingShell
  views/
    LoginView, DashboardHome, PackagesList, PackageDetail, SecurityPosture, SettingsView
    onboarding/ Welcome, Admin, Domain, Packages, Secrets, Dns, Tls, Review, Done
  i18n/         vue-i18n scaffold (en only for v0.1)
  lib/utils.ts  cn(), password generator, clipboard, humanBytes, relTime
  assets/main.css  Tailwind 4 @theme + tokens
```

## Design system

- Warm monochrome canvas `#FAF9F6`, ink `#1a1a1a`, one accent (amber `#B45309`).
- Inter for UI, Newsreader for editorial headings, JetBrains Mono for data.
- Cards: 1px `#E5E4E0` border, no shadow, subtle hover.
- Radius: 4/6/8/12px. No pill containers.
- Status pills: `.uppercase .tracking-wide .text-xs`, muted pastel backgrounds.

Refer to `.pi/agent/skills/minimalist-ui/SKILL.md` for the full guide.

## Development

```bash
npm install
npm run dev            # http://localhost:5173
```

Vite proxies `/api` and `/api/events` to `http://localhost:8090`. Boot the
Spring Boot backend before opening the UI, or the API views will show empty
states (onboarding still runs end-to-end with soft-fail on backend absence).

## Build

```bash
npm run build          # emits dist/
```

The backend Dockerfile copies `dist/` into `src/main/resources/static/` at
image build time; the final container ships one Spring Boot fat JAR.

## Onboarding v0.1

Nine steps, backend-persisted (soft-fails to local state when backend absent):

1. **Welcome** — reads `/api/system`, warns on non-Debian.
2. **Admin** — username + generated 20-char password + acknowledgement.
3. **Domain** — pick apex; default `aurora.local`.
4. **Packages** — bento grid, category tabs, presets.
5. **Secrets** — visual stub (form editor lands in M2).
6. **DNS** — AdGuard / router / mDNS with per-mode instructions.
7. **TLS** — download Caddy root CA + per-OS install steps.
8. **Review** — plan diff, streamed install logs via SSE.
9. **Done** — first-run URL for every enabled service.

## Not in v0.1

- WebAuthn passkey enrollment (stub only — v0.2).
- Live per-package logs stream (M3).
- Env-form editor (M2).
- Real security rules engine (M4).
- uPlot chart is wired but currently renders "no data" until backend metrics
  land — real chart rendering ships with the M3 metrics slice.

## Aesthetic guardrails

- Zero gradients.
- Zero drop shadows above `0 2px 8px rgba(0,0,0,0.04)`.
- One accent, used once per screen (primary CTA).
- Editorial serif headings, not display-sans.
- Cards are flat rectangles; the hierarchy comes from typography and spacing.
