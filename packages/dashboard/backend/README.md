# Aurora dashboard — backend (v0.1)

Spring Boot 4 / Java 25 admin plane for an `aurora.local` box.
Ships as a single fat jar (`aurora.jar`) that also serves the Vue SPA
from `src/main/resources/static/` at runtime.

## Build

The build assumes Java 25 + Maven 3.9+. On the target box we don't
install Java at all — the Dockerfile builds inside `maven:3.9-eclipse-temurin-25-alpine`
and ships an `eclipse-temurin:25-jre-alpine` runtime image.

Local dev (needs JDK 25):

```
./mvnw clean verify
./mvnw spring-boot:run
```

Runs on `http://localhost:8090`. If the SPA hasn't been built into
`src/main/resources/static/index.html`, the SPA fallback returns a
503 with a friendly note; the API still works.

## Configuration

All knobs are in `application.yml` and overridable via env:

| Key                  | Env                    | Default                            |
|----------------------|------------------------|------------------------------------|
| `spring.datasource.url` | `AURORA_DB_PATH`    | `/data/aurora.db`                  |
| `aurora.repo-path`   | `AURORA_REPO_PATH`     | `/repo`                            |
| `aurora.host-proc-path` | `AURORA_HOST_PROC`  | `/host/proc`                       |
| `aurora.docker.host` | `DOCKER_HOST`          | `unix:///var/run/docker.sock`      |
| `server.port`        | `SERVER_PORT`          | `8090`                             |

In the Docker image, `~/aurora.local` is bind-mounted read-write at
`/repo`, `/proc` is mounted read-only at `/host/proc`, and
`/var/run/docker.sock` is passed through. See
`packages/dashboard/compose.yml` in the parent repo.

## API surface (v0.1)

- `GET  /api/health`               — friendlier alternative to actuator
- `GET  /api/system`               — hostname, uptime, docker version, mem/disk
- `GET  /api/state`                — parsed `.state.yml`
- `GET  /api/packages`             — manifest + state + running cross-reference
- `GET  /api/packages/{name}`      — detail + `.env.example` body
- `GET  /api/containers`           — docker ps filtered to `project=aurora`
- `GET  /api/events`               — SSE stream of docker events
- `GET  /api/onboarding/status`
- `POST /api/onboarding/admin`     — create initial admin (bootstrap mode only)
- `POST /api/onboarding/domain`    — write `.state.yml` domain + core `.env`
- `POST /api/onboarding/packages`  — write `.state.yml` `enabled[]`
- `POST /api/onboarding/complete`
- `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`

Everything but `/api/health`, `/api/auth/login`, `/api/auth/logout`,
and `/api/onboarding/**` requires an authenticated session.
`/api/onboarding/**` is gated at the service layer — mutating routes
refuse after onboarding completes.

## Deliberately out of scope for v0.1

Punted with TODOs — implemented in later milestones:

- **WebAuthn** (§M5). Only password auth is wired.
- **`/api/security` posture engine** (§M4).
- **`/api/backups`, `/api/audit`, `/api/system/metrics` time series**.
- **Package enable/disable/upgrade via `bootstrap.sh add/remove`** — the
  `ScriptRunner` service ships in v0.2.
- **CSRF tokens for /api/**.** Disabled with a comment in
  `SecurityConfig`; SPA is same-origin session-cookie-only for v0.1.
- **Rate-limit login attempts.** No throttling in the skeleton.
- **Metric sampler beyond `app.uptime_ms`.** Single `@Scheduled` stub
  proves the pipeline; real samplers land in v0.3.

## Tests

Three fast smoke tests, no daemon required — `DockerClient` is
replaced with a Mockito stub in `TestDockerConfig`:

- `AuroraApplicationTests`  — Spring context loads.
- `PackagesServiceTests`    — manifest parsing against a fake repo.
- `HealthControllerTests`   — `/api/health` returns 200 + `db=true`.

Run with `./mvnw test` (needs JDK 25 on the host — or run in the
build container).
