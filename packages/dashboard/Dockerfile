# syntax=docker/dockerfile:1.7
# aurora.local / packages/dashboard / Dockerfile
#
# Three-stage build:
#   1. node:22-alpine        → Vue SPA build
#   2. maven:3.9-...25-alpine → Spring Boot fat jar (embeds SPA)
#   3. eclipse-temurin:25-jre-alpine → runtime
#
# Final image target: < 250 MB.

# ─────────────────────────────────────────────────────────────
# Stage 1 · Frontend (Vue 3.5 + Vite 8)
# ─────────────────────────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /app/frontend

# Install deps first for cache reuse. No lockfile yet (v0.1); use install.
COPY frontend/package.json ./
RUN npm install --no-audit --no-fund --loglevel=error --legacy-peer-deps

COPY frontend/ ./
# The MSW service worker lives in public/ so `npm run dev:mock` can serve
# it, which means Vite copies it into dist/ on every build. Shipping a
# request-interceptor worker in the production image is a footgun: any
# browser that ever registered it (a dev:mock session against the same
# origin) keeps serving mocked API responses over the real backend, and
# the symptom — "my change isn't showing up" — looks like a stale build
# rather than a stale worker. Prod never starts it (main.ts gates on
# VITE_USE_MOCKS), so drop the file rather than leave it fetchable.
RUN npm run build && rm -f dist/mockServiceWorker.js

# ─────────────────────────────────────────────────────────────
# Stage 2 · Backend (Spring Boot 4 + Java 25)
# ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25-alpine AS backend
WORKDIR /app/backend

# Deps first (cache layer). BuildKit will re-use if pom didn't change.
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline || true

COPY backend/src ./src

# Inject the built Vue SPA into Spring's static resources.
COPY --from=frontend /app/frontend/dist ./src/main/resources/static

RUN mvn -B -T1C package -Dmaven.test.skip=true

# ─────────────────────────────────────────────────────────────
# Stage 3 · Runtime
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Runtime dependencies for the /api/onboarding/launch endpoint:
#   - bash: scripts/up.sh starts with #!/usr/bin/env bash
#   - docker-cli + docker-cli-compose: up.sh shells out to `docker compose`
#   - avahi-tools: MdnsAliasService shells avahi-publish to register the
#     per-vhost aliases against the host avahi-daemon (see the D-Bus mounts
#     in compose.yml)
#   - bind-tools: dig, for mDNS resolution checks (@224.0.0.251 :5353)
#   - ca-certificates: TLS verification for the yq download below (and future
#     runtime https calls). Without it wget --https-only fails on Alpine.
#   - rsync: mirrors this box's media to a NAS on a schedule (storage mode
#     "keep a copy on the NAS"). Runs inside a one-shot container that has
#     both the local folder and the NAS volume attached, so the copy never
#     needs a privileged host process.
#   - samba-client: `smbclient -L` lists the shares a NAS offers, so the
#     owner picks a folder from a list instead of typing a share name they
#     have to go and look up. ~2 MB, and it is the only way to answer "what
#     is on that box" without implementing SMB2 by hand.
# Also install /var/log/aurora as a writable log destination for LaunchService.
RUN apk add --no-cache bash docker-cli docker-cli-compose avahi-tools bind-tools ca-certificates \
    samba-client rsync

# ── mikefarah yq (v4) ──────────────────────────────────────────────────────
# scripts/lib/manifest.sh + scripts/lib/state.sh (invoked by scripts/up.sh)
# hard-require either `yq` (mikefarah v4) or `python3 + PyYAML` to parse
# manifests and update `.state.yml`. The upstream Alpine repo has a `yq`
# package but it's kislyuk/yq (Python fork) which is API-incompatible with
# mikefarah v4 — the scripts explicitly gate on `yq --version` matching
# `v?4`. Ship the pinned upstream binary so `docker exec aurora up.sh …`
# from inside the container works for richer flows (media stack, etc.).
#
# Pin: v4.44.3 (2025-09, stable, matches manifest.sh gate).
# Arch: TARGETARCH resolves to amd64/arm64 under BuildKit; falls back to
# amd64 for legacy `docker build` (Optiplex target) via the ${:-} below.
#
# Declared WITHOUT a default on purpose. TARGETARCH is a predefined build
# arg that BuildKit injects; giving it a default shadows that value, so
# the build silently produced an amd64 image on every host, arm64
# included, and the resulting yq binary would not execute.
ARG TARGETARCH
ARG YQ_VERSION=v4.44.3
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
      amd64) yq_arch=amd64 ;; \
      arm64) yq_arch=arm64 ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    wget -q -O /usr/local/bin/yq \
      "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${yq_arch}"; \
    chmod 0755 /usr/local/bin/yq; \
    /usr/local/bin/yq --version | grep -qE 'version v?4'

# Non-root user; UID overridden at runtime by compose (user: "${AURORA_UID}:${DOCKER_GID}").
RUN addgroup -S aurora && adduser -S -G aurora -u 1000 aurora \
 && mkdir -p /data /repo /host/proc /var/log/aurora \
 && chown -R aurora:aurora /data /app /var/log/aurora

COPY --from=backend /app/backend/target/aurora.jar /app/aurora.jar

# Which build this actually is.
#
# A box could not tell you. The published :0.1.0 tag on GHCR was two days
# behind main while calling itself 0.1.0, a fresh install pulled it, and
# nothing in the UI or the API could say which commit was running — so
# "have you got the fix?" was unanswerable without comparing image digests
# by hand. These are plain build args so a local `docker compose build`
# stamps the truth too; CI passes the real values.
ARG AURORA_BUILD_REVISION=unknown
ARG AURORA_BUILD_DATE=unknown
ARG AURORA_BUILD_VERSION=dev
ENV AURORA_BUILD_REVISION=${AURORA_BUILD_REVISION} \
    AURORA_BUILD_DATE=${AURORA_BUILD_DATE} \
    AURORA_BUILD_VERSION=${AURORA_BUILD_VERSION}
LABEL org.opencontainers.image.revision=${AURORA_BUILD_REVISION} \
      org.opencontainers.image.created=${AURORA_BUILD_DATE} \
      org.opencontainers.image.version=${AURORA_BUILD_VERSION}

EXPOSE 8090

# JVM tuning: modest heap for a small admin app on a homelab box.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Djava.security.egd=file:/dev/urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8090/api/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/aurora.jar"]
