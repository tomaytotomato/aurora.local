# Aurora Dashboard — E2E

Playwright-driven end-to-end tests that exercise the onboarding wizard
and post-install dashboard against an **isolated** aurora instance.

## Isolation model

- Compose project name: `aurora-e2e` (never collides with the live
  `aurora-dashboard` project)
- Host port: `127.0.0.1:8091` (live instance stays on :8090 behind Caddy)
- Own bridge network: `aurora-e2e_net`
- Scratch repo copy: `/tmp/aurora-e2e-state/repo` (rsynced from the real
  repo each reset, then `.state.yml` overwritten with `fixtures/fresh-state.yml`)

The live `aurora` container is never stopped, restarted, or otherwise
touched by these scripts.

## Run

```sh
cd packages/dashboard/e2e
npm install
./scripts/reset-aurora-e2e.sh      # spin up isolated aurora on :8091
npx playwright test                # run the suite
./scripts/teardown.sh              # stop + wipe when done
```

`reset-aurora-e2e.sh` is idempotent — call it before each run to get a
fresh box.

## Notes

- If `npx playwright install --with-deps chromium` failed at scaffold
  time (typically because it needs `sudo apt-get`), install the missing
  host libraries manually. On Debian/Ubuntu:
  `sudo npx playwright install-deps chromium`. Without them Chromium
  may fail to launch with a shared-library error.
- The reset script builds the dashboard image from source (`up -d --build`),
  so it needs no prebuilt image and never touches GHCR — the published
  `ghcr.io/tomaytotomato/aurora` image the box pulls is deliberately not
  in the loop here, keeping e2e a pure source-build check.
