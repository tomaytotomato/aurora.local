# privacy

- **AdGuard Home** — LAN DNS with ad/tracker blocking. Point your
  router's DHCP DNS at this box's IP after first boot.
- **Gluetun** — provider-agnostic VPN sidecar (opt-in via `torrent`
  profile). Other packages can share its netns with
  `network_mode: "service:gluetun"`.

## First-run

1. Copy `.env.example` to `.env`; pick a VPN provider and paste creds.
2. `./scripts/up.sh privacy`
3. Visit `http://<lan-ip>:3000` for AdGuard's setup wizard.
4. `./scripts/seed-adguard.sh` seeds the `*.home.local` rewrites and a
   default admin user matching `HOMEPAGE_VAR_ADGUARD_USER/PASS`.
