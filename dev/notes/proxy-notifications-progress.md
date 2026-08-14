# Backend: proxy + notifications — progress log

Branch: `feat/backend-proxy-notifications`. Working one domain to completion
before starting the other, committing each meaningful slice.

## 2026-08-14 — starting

Read the spec (`packages/dashboard/openapi.yaml` paths `/proxy/*` and
`/notifications/*`), the frontend mocks (`frontend/src/mocks/handlers/proxy.ts`,
`notifications.ts` + fixtures), and the model controllers/services
(`UpdatesController`, `HardeningController`, `SettingsPortabilityController`,
`CaddySnippetService`, `MdnsAliasService`, `StatusProbeService`).

Key findings that shape the design:

- `CaddySnippetService` already owns `data/caddy/snippets/*.caddy` and
  **prunes any file it doesn't recognise** on every reconcile (on-ready +
  every 60s). A hand-added-route snippet dropped in that directory would
  be deleted within a minute unless `CaddySnippetService` is told about it.
  Plan: add one constant (`CUSTOM_ROUTES_FILENAME`) it must never prune,
  and have `ProxyService` write/delete that one file. Smallest possible
  coupling in one direction (Proxy → CaddySnippetService), documented.
- Caddy's Caddyfile already does `import /etc/caddy/snippets/*.caddy`
  with `--watch` — no Caddyfile change needed, matches the brief ("Caddy's
  existing --watch reload does the rest").
- "Managed" routes are not persisted anywhere — they're derived live by
  scanning each enabled package's `caddy.snippet` for vhost blocks +
  `reverse_proxy` targets, the same discovery style `MdnsAliasService`
  already uses for labels. Chose a whole-content regex
  (`VHOST_BLOCK` + `REVERSE_PROXY` search inside each match) over
  line-by-line brace counting — handles both the single-line and
  multi-line snippet styles already present in the fixtures.
- Hand-added routes get a small SQLite table (`proxy_route`) for the
  id/createdAt bookkeeping a file can't give cheaply — consistent with
  how `admin_user`/`settings`/`audit_event` already work. The *file* stays
  the source of truth for what Caddy actually serves; the DB row is
  Aurora's own bookkeeping, not a second source of truth for the vhost.
- Notifications: outbound HTTP via `java.net.http.HttpClient`, same
  pattern as `StatusProbeService` (package-private constructor overload
  for injecting a client in tests). Two new tables:
  `notification_channel`, `notification_delivery`.
- WireMock is not currently a dependency. Adding
  `org.wiremock:wiremock-standalone:3.9.1` (test scope) — already
  present in the local Maven cache, bundles JUnit 5 support
  (`WireMockExtension`), avoids Jackson-shading clashes with Spring Boot.

Plan: proxy domain first, fully committed, then notifications.

## 2026-08-14 — proxy domain, slice 1 (migration + scaffolding)

- `V4__proxy_routes.sql` — `proxy_route` table.
- `application.yml` — added V4 to `spring.sql.init.schema-locations`.

Next: `ProxyRouteRepo`, `DockerService.listContainerSummaries()`,
`CaddySnippetService` carve-out, `ProxyService`, `ProxyController`,
capability flag, tests.

## 2026-08-14 — proxy domain complete

Implemented all four `/proxy/*` endpoints: `GET /routes`, `POST /routes`,
`DELETE /routes/{id}`, `GET /targets`, `POST /preview`.

- `ProxyRouteRepo` — thin JdbcTemplate repo over the new `proxy_route`
  table (hand-added routes only).
- `DockerService.listContainerSummaries()` — new `ContainerSummary`
  record (name, ports, owning package) for `/proxy/targets`, reusing the
  same `config_files` label parsing `PackagesService.runningPackageNames()`
  already does.
- `CaddySnippetService.CUSTOM_ROUTES_FILENAME` — the one-filename carve-out
  in `pruneStale()` so a hand-added route's rendered snippet survives the
  60-second reconcile loop.
- `ProxyService` — managed-route discovery via a whole-content regex
  (`VHOST_BLOCK` + `REVERSE_PROXY`) over each enabled package's
  `caddy.snippet`; conflict detection (reserved / vhost-taken / mdns-alias
  / target-unreachable); create/delete for hand-added routes with the
  snippet file kept in sync.
- `SystemService` — flipped `capabilities.proxy = true`.
- Test harness: added `proxy_route` to `AuroraIntegrationTest.MUTABLE_TABLES`.

**Bug found and fixed along the way, not part of the brief but blocking
it**: `TestDockerConfig`'s `@Primary` mock `DockerClient` bean shared its
Spring bean *name* (`dockerClient`) with the real production bean in
`DockerClientConfig`. With `allow-bean-definition-overriding: true` (set
for tests), a same-named `@Bean` doesn't get disambiguated by `@Primary`
at all — the container just replaces whichever definition registered
first, and empirically the *real* `DockerClientImpl` was winning. Nothing
had caught this because no existing `AuroraIntegrationTest` subclass
exercised a docker-touching code path through the full context. Renamed
the test bean method to `testDockerClient()` so it's a genuine "two
candidates, one `@Primary`" case, which resolves deterministically
regardless of configuration-class processing order. Confirmed via a
purpose-built assertion (`Mockito.mockingDetails(...).isMock()`) before
and after the fix.

Test count: 543 → 570 (27 new: `ProxyControllerIntegrationTest` × 24,
3 new `DockerServiceTests` cases for `listContainerSummaries()`).
Full `mvn test` green.

Committing this slice now, then starting notifications.
