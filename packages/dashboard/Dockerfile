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
RUN npm run build

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
#   - avahi-tools: iter-3 P1c mdns-audit.sh (avahi-browse + avahi-resolve)
#   - bind-tools: iter-3 P1c mdns-audit.sh (dig +short @224.0.0.251 :5353)
# Also install /var/log/aurora as a writable log destination for LaunchService.
RUN apk add --no-cache bash docker-cli docker-cli-compose avahi-tools bind-tools

# Non-root user; UID overridden at runtime by compose (user: "${AURORA_UID}:${DOCKER_GID}").
RUN addgroup -S aurora && adduser -S -G aurora -u 1000 aurora \
 && mkdir -p /data /repo /host/proc /var/log/aurora \
 && chown -R aurora:aurora /data /app /var/log/aurora

COPY --from=backend /app/backend/target/aurora.jar /app/aurora.jar

EXPOSE 8090

# JVM tuning: modest heap for a small admin app on a homelab box.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Djava.security.egd=file:/dev/urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8090/api/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/aurora.jar"]
