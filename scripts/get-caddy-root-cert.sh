#!/usr/bin/env bash
# aurora.local / scripts/get-caddy-root-cert.sh
#
# Extract Caddy's internal CA root cert to a file so LAN devices
# can trust *.aurora.local certificates without warnings.
#
# On the box:
#   ./scripts/get-caddy-root-cert.sh
#   # writes ~/caddy-root.crt
#
# On a Mac client:
#   scp bruce@aurora.local:~/caddy-root.crt ~/Downloads/
#   # then double-click, add to Keychain, Trust = Always Trust
#
# On iOS/iPadOS: email the .crt to yourself, tap it, Settings will
# offer to install. Then Settings > General > About > Certificate
# Trust Settings > flip 'Caddy Local Authority' to on.
#
# On Android: Settings > Security > Encryption & credentials
#   > Install a certificate > CA certificate.
#
# On Windows: double-click, Install Certificate > Local Machine
#   > Place in 'Trusted Root Certification Authorities'.

set -euo pipefail
OUT="${1:-$HOME/caddy-root.crt}"

docker exec caddy cat /data/caddy/pki/authorities/local/root.crt > "$OUT"
chmod 644 "$OUT"

echo "wrote $OUT"
openssl x509 -in "$OUT" -noout -subject -dates
