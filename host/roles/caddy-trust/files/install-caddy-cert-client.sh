#!/usr/bin/env bash
# home.local / host/roles/caddy-trust/files/install-caddy-cert-client.sh
#
# Run this on a CLIENT machine (laptop, phone-via-share, etc.) to trust
# the home.local Caddy root CA so *.home.local URLs get a green padlock.
#
# Usage:
#   # Grab the cert from the server (one time):
#   scp user@<hostname>.local:/usr/local/share/ca-certificates/caddy-local.crt .
#   # ...or, if you haven't run the caddy-trust ansible role yet:
#   ssh user@<hostname>.local 'docker exec caddy cat /data/caddy/pki/authorities/local/root.crt' > caddy-local.crt
#
#   # Then run this helper:
#   ./install-caddy-cert-client.sh caddy-local.crt
#
# Per-OS notes (documented rather than automated to avoid destructive
# sudo operations on other people's laptops):
#
# macOS:
#   sudo security add-trusted-cert -d -r trustRoot \
#     -k /Library/Keychains/System.keychain caddy-local.crt
#
# Linux (Debian/Ubuntu):
#   sudo cp caddy-local.crt /usr/local/share/ca-certificates/
#   sudo update-ca-certificates
#
# Linux (Fedora/RHEL):
#   sudo cp caddy-local.crt /etc/pki/ca-trust/source/anchors/
#   sudo update-ca-trust
#
# iOS / iPadOS: AirDrop or email the .crt to yourself; tap to install
#   via Settings; then Settings > General > About > Certificate Trust
#   Settings > flip 'Caddy Local Authority' on.
#
# Android: Settings > Security > Encryption & credentials
#   > Install a certificate > CA certificate.
#
# Windows (PowerShell as admin):
#   Import-Certificate -FilePath caddy-local.crt `
#     -CertStoreLocation Cert:\LocalMachine\Root

set -euo pipefail

CRT="${1:-caddy-local.crt}"
[[ -f "$CRT" ]] || { echo "no such file: $CRT" >&2; exit 1; }

os="$(uname -s)"
case "$os" in
  Darwin)
    sudo security add-trusted-cert -d -r trustRoot \
      -k /Library/Keychains/System.keychain "$CRT"
    ;;
  Linux)
    if command -v update-ca-certificates >/dev/null 2>&1; then
      sudo install -m 0644 "$CRT" /usr/local/share/ca-certificates/caddy-local.crt
      sudo update-ca-certificates
    elif command -v update-ca-trust >/dev/null 2>&1; then
      sudo install -m 0644 "$CRT" /etc/pki/ca-trust/source/anchors/caddy-local.crt
      sudo update-ca-trust
    else
      echo "unknown Linux trust store; do it by hand" >&2
      exit 1
    fi
    ;;
  *)
    echo "unsupported OS: $os. See top-of-file comments for manual steps." >&2
    exit 1
    ;;
esac

echo "installed $CRT into system trust store"
openssl x509 -in "$CRT" -noout -subject -dates || true
