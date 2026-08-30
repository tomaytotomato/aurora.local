# syntax=docker/dockerfile:1.7
# packages/pi/pi-server / Dockerfile
#
# Small Node/TS service that turns Pi's SDK into an OpenAI-compatible
# HTTP door. LibreChat talks to this over aurora_net; nothing else does.
#
# The bundle is intentionally tiny: pi-coding-agent is a dependency (npm
# install pulls it), express is the only other real one, node --run
# handles TS via --experimental-strip-types so there is no build step.
FROM node:22-alpine

# bash — pi-coding-agent's own tools shell out to bash. git is
# excluded from the base install because Alpine v3.22's git package has
# been I/O-flaky in this environment; the SDK path does not need git in
# the container. If a future pi tool wants it, add on demand.
# ca-certificates for HTTPS to the model provider.
RUN apk add --no-cache bash ca-certificates

WORKDIR /app

# Deps first, cache-friendly.
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund --loglevel=error

COPY src ./src

# Where the operator mounts skills + auth. Both are optional.
#   /pi-data/skills → additional skill folders bind-mounted from
#     packages/pi/skills. Pi's DefaultResourceLoader picks them up
#     from the working directory .pi/agent/skills.
#   /pi-data/auth → the model provider auth store. Bruce's Copilot
#     token lives here.
ENV HOME=/pi-data \
    PI_SERVER_PORT=8080 \
    PI_MODEL_NAME=pi \
    NODE_ENV=production

EXPOSE 8080

# --experimental-strip-types lets Node 22 execute the TypeScript
# source directly without a compile step. Aurora's own dashboard uses
# vue-tsc for typecheck at CI time; pi-server ships without that hop
# because there is one file to reason about.
CMD ["node", "--experimental-strip-types", "src/index.ts"]
