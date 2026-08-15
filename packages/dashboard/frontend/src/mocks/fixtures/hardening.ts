// Hardening state: the three decisions Aurora has made and not finished.
//
// The defaults here are the box as it actually stands today — pins.env
// exists but covers about half the images, nothing is sops-encrypted,
// and the dashboard still mounts docker.sock read-write. That last one
// is real: see packages/dashboard/compose.yml.
//
// EDIT ME: flip any of these to their finished state to see the done
// rendering.

import type { HardeningState } from '@/api/hardening';

export function initialHardening(): HardeningState {
  return {
    pinning: {
      total: 34,
      pinned: 18,
      unpinned: [
        'lscr.io/linuxserver/prowlarr:latest',
        'lscr.io/linuxserver/sonarr:latest',
        'lscr.io/linuxserver/radarr:latest',
        'dperson/samba:latest',
        'kopia/kopia:latest',
      ],
      pinsFileExists: true,
      generatedAt: '2026-07-02T21:10:00Z',
    },
    secrets: {
      encrypted: false,
      method: null,
      envFiles: 11,
      encryptedFiles: 0,
    },
    dockerSocket: {
      proxied: false,
      exposedContainers: ['aurora-dashboard'],
      writable: true,
    },
  };
}
