// Custom stack fixtures, plus the mock's compose "validator".
//
// The validator here is a deliberately shallow regex pass, not a YAML
// parser — the frontend has no yaml dependency and does not want one.
// The real backend will parse properly. What matters for developing the
// screen is that the *shape* of the report is right: two tiers, with
// consequences rather than syntax.
//
// The sample stack ships with every advisory tripped, because that is
// what a compose file copied off a forum post actually looks like, and
// it is the state the screen exists to talk about.

import type { CustomStack, StackIssue, StackValidation } from '@/api/custom';

/** Ports Aurora's own packages already hold. */
const TAKEN_PORTS = new Set([80, 443, 53, 3000, 3011, 8080, 8096, 8989, 2283, 51515, 51820, 8090]);

/** Container names that belong to Aurora and are not negotiable. */
const RESERVED_NAMES = new Set(['caddy', 'authelia', 'gluetun', 'kopia', 'aurora-dashboard']);

export const SAMPLE_COMPOSE = `services:
  calibre-web:
    image: linuxserver/calibre-web:latest
    container_name: calibre-web
    environment:
      - PUID=1000
      - PGID=1000
    volumes:
      - ./config:/config
      - /mnt/storage/books:/books
    ports:
      - "8083:8083"
`;

export function initialStacks(): CustomStack[] {
  return [
    {
      id: 'stack-calibre',
      name: 'calibre-web',
      state: 'running',
      composeYaml: SAMPLE_COMPOSE,
      createdAt: '2026-07-14T19:20:00Z',
      lastDeployedAt: '2026-07-14T19:22:00Z',
      lastJobId: null,
      containers: ['calibre-web'],
    },
  ];
}

function issue(kind: StackIssue['kind'], message: string, service: string | null = null): StackIssue {
  return { kind, message, service };
}

/**
 * Shallow structural read of a compose file. Good enough to drive the UI
 * honestly; not good enough to be the real thing, which is why the
 * backend will do this properly.
 */
export function validateCompose(yaml: string): StackValidation {
  const errors: StackIssue[] = [];
  const warnings: StackIssue[] = [];

  const text = yaml ?? '';
  if (!text.trim()) {
    return {
      valid: false,
      errors: [issue('parse-error', "There's nothing here to run.")],
      warnings: [],
      services: [],
      ports: [],
      images: [],
      volumes: [],
    };
  }

  // Tabs are the classic YAML paste failure and the error message people
  // get from a real parser is famously unhelpful.
  if (/^\t/m.test(text)) {
    errors.push(issue('parse-error', 'This uses tabs for indentation. YAML only accepts spaces.'));
  }

  const serviceBlock = text.split(/^services:\s*$/m)[1] ?? '';
  const services = [...serviceBlock.matchAll(/^ {2}([a-zA-Z0-9][\w.-]*):\s*$/gm)].map((m) => m[1]);
  if (!services.length) {
    errors.push(issue('no-services', 'No services found. A compose file needs at least one thing to run.'));
  }

  const images = [...text.matchAll(/^\s*image:\s*["']?([^\s"']+)/gm)].map((m) => m[1]);
  const volumes = [...text.matchAll(/^\s*-\s*([^\s:]+:[^\s:]+(?::(?:ro|rw))?)\s*$/gm)]
    .map((m) => m[1])
    .filter((v) => v.includes('/'));

  const ports = [...text.matchAll(/^\s*-\s*["']?(\d+):(\d+)/gm)].map((m) => Number(m[1]));

  for (const port of ports) {
    if (TAKEN_PORTS.has(port)) {
      errors.push(
        issue('port-conflict', `Port ${port} is already in use by one of Aurora's own apps.`),
      );
    }
    if (port < 1024) {
      errors.push(
        issue(
          'privileged-port',
          `Port ${port} is a privileged port. In a homelab compose file that is almost always a mistake.`,
        ),
      );
    }
  }

  const names = [...text.matchAll(/^\s*container_name:\s*["']?([^\s"']+)/gm)].map((m) => m[1]);
  for (const name of names) {
    if (RESERVED_NAMES.has(name)) {
      errors.push(issue('name-conflict', `"${name}" is one of Aurora's own containers.`));
    }
  }

  // ── Advisory ────────────────────────────────────────────────────────
  for (const image of images) {
    if (!image.includes(':') || image.endsWith(':latest')) {
      warnings.push(
        issue(
          'unpinned-image',
          `${image} isn't pinned to a version. It will change under you on the next pull, and you won't know why it broke.`,
        ),
      );
    }
  }

  if (/privileged:\s*true/.test(text)) {
    warnings.push(
      issue('privileged', 'A service runs privileged, which means it can do anything the host can.'),
    );
  }

  if (/docker\.sock/.test(text)) {
    warnings.push(
      issue(
        'docker-socket',
        'A service mounts the Docker socket. That container can start other containers as root, which means it is root.',
      ),
    );
  }

  if (/network_mode:\s*["']?host/.test(text)) {
    warnings.push(
      issue('host-network', 'A service uses host networking, so it ignores Docker network isolation entirely.'),
    );
  }

  if (services.length && !/^\s*restart:/m.test(text)) {
    warnings.push(
      issue('no-restart-policy', "No restart policy, so this won't come back after a reboot."),
    );
  }

  if (services.length && !/(mem_limit|memory:)/.test(text)) {
    warnings.push(
      issue(
        'uncapped',
        'No memory limit. On a box with no swap, one runaway process here takes everything else down with it.',
      ),
    );
  }

  return {
    valid: errors.length === 0,
    errors,
    warnings,
    services,
    ports,
    images,
    volumes,
  };
}
