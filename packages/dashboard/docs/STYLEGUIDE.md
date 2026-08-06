# Aurora dashboard styleguide

The opinionated bits. If a new page or card disagrees with something here,
change the page, not the guide (or change the guide deliberately, with a
reason).

## The aurora photo background

The photo is a constant across every page under `AppShell`, not a
per-route opt-in. It is Aurora's signature; showing it only on Overview
made the rest of the app feel like a different product. It is rendered
once in `AppShell.vue` via `<AuroraBackground scrim="strong" />`.

Consequences for a new page:
- The sidebar, top bar, and every `Card` are opaque surfaces (`bg-card`),
  so content sitting inside a card needs no special treatment.
- Text that sits **outside** a card, directly on the photo (a page
  header: eyebrow, `h1`, description paragraph), must live inside a
  wrapper with the `on-photo` class. That class recolours direct-child
  headings, eyebrows, and paragraphs to a legible translucent white in
  either theme. It is scoped to direct children on purpose, so it never
  bleeds into a nested card.

```html
<div class="mb-10 on-photo">
  <div class="eyebrow mb-2">Section</div>
  <h1>Page title</h1>
  <p>Supporting sentence.</p>
</div>
```

## Card header anatomy

One way to do it. The card's **title** is the most prominent text in its
header; the **subtitle** sits under it, smaller and muted.

```html
<h3 class="card-title mb-1">Apps</h3>
<p class="card-subtitle mb-3">4 enabled · 3 running</p>
```

Do **not** lead a card with the tiny uppercase `eyebrow` as its title.
That reads as a big subtitle under a small title (the exact bug this
guide was written to kill). The `eyebrow` is a *section kicker* used for
a divider label **inside** a card (for example "Recent changes" above a
list), never as the card's own name.

Sizes (`main.css`): `card-title` is the serif face at 1.25rem;
`card-subtitle` is 0.8125rem muted. Page titles stay `h1`; section
headings inside a page stay `h3`.

## Tabs and tab-like strips

One treatment, no exceptions. A tab strip that sits on the aurora photo
(any `AppShell` page, outside a card) uses the shared `Tabs` component
with `class="on-photo-tabs"`. That class (in `main.css`) recolours the
triggers, the active underline, and the bottom rule so they read on the
photo in both themes.

```html
<Tabs v-model="tab" class="on-photo-tabs" :tabs="[...]">
  <!-- panels are opaque Cards -->
</Tabs>
```

Rules:
- **Never** wrap a tab strip in an opaque box (`bg-card`, `rounded-t-lg`,
  `border-b-0`) to solve contrast. A boxed bar reads as a panel floating
  in mid-air, detached from the content cards beneath it. Use
  `on-photo-tabs` instead. This is a hard line; it is the exact bug this
  section exists to prevent.
- Tab **panels** are opaque `Card`s (or a `Table` inside one). Panel
  content carries its own surface, so it never needs `on-photo`.
- A **hand-rolled** filter strip (buttons + an underline `span` over a
  `border-b`, as on the Apps list) is the same shape as `Tabs` and takes
  the **same** `on-photo-tabs` class on its container. Prefer the shared
  `Tabs` component for anything new.
- Tabs that sit on a **light** surface instead of the photo (the
  onboarding wizard's glass content card) keep the default dark triggers
  and must **not** take `on-photo-tabs` — white triggers would vanish on
  the light card.

Reference: `VpnView.vue`, `PackageDetail.vue`, `PackagesList.vue`.

## Charts

Charts render as **SVG**, not canvas. A canvas 2D context cannot resolve
CSS custom properties, so `stroke="var(--color-accent)"` came out black
and vanished in dark mode. SVG is DOM and resolves the variables live, so
a themed chart follows light/dark for free. See `MetricChart.vue`: the
line uses `--color-accent`, the fill is a gradient of the same, guides
use `--color-border`. Any new chart follows suit; do not reach for a
canvas charting library for a homelab-sized dataset.

## Colour + tokens

Everything flows through the shadcn-semantic tokens in `main.css`
(`--color-background`, `-foreground`, `-card`, `-muted`, `-border`,
`-destructive`, plus `-warning` / `-info` / `-success`). The one
Aurora-specific brand pair is `--color-accent` (amber) for the primary
CTA and chart line. Never hard-code a hex in a component; add or reuse a
token.

## State vocabulary

Every data surface renders exactly one of: `loading` (skeletons),
`empty` (a designed empty state with a glyph and honest copy, never a
fabricated value), `error` (human copy plus a "Try again" button, never a
raw axios message), or the hydrated data. Mark them with
`data-state="loading|empty|error"` for tests. `SecurityPosture.vue` and
`VpnView.vue` are the reference implementations.

## Terminology

The user-facing word is **Apps**, not "packages". The route is `/apps`
(the old `/packages` paths redirect). The wire and the code still say
`package` / `/api/packages` — that is an internal term, not shown to a
person. If you surface a package name in the UI, prefer its `title`.

## Live status

Live surfaces (service status, container events, VPN status) use
Server-Sent Events with a poll fallback. The mock layer streams them via
`mocks/sse.ts` so every live surface animates with no backend. Named
events matter: `service-status`, `container-event`, `vpn-status`. Match
the consumer's expected event name exactly.
