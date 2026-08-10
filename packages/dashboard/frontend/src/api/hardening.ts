// The three pieces of hardening Aurora has decided on but not finished:
// digest-pinned images, encrypted .env files, and a proxied Docker
// socket. All three are recorded as decisions in the repo's plan; none
// of them has ever been visible on the box.
//
// This is state, not control. Turning any of it on is an Ansible run or
// a script, not a button — pinning rewrites compose files, sops needs a
// key, and putting a proxy in front of the socket changes how the
// dashboard talks to Docker. What the dashboard can honestly do is say
// where each one stands and what remains.
//
// Deliberately no score. A single number out of ten invites tuning the
// number, and the competitive analysis already flagged Aurora's old
// fabricated security score as a thing to remove rather than repeat.

import { http } from './client';

export interface PinningState {
  /** Images referenced across every enabled package. */
  total: number;
  /** Referenced by digest rather than a floating tag. */
  pinned: number;
  /** Image references still on a tag, worst offenders first. */
  unpinned: string[];
  /** Whether pins.env exists at all. */
  pinsFileExists: boolean;
  generatedAt: string | null;
}

export interface SecretsState {
  /** True once every .env is sops-encrypted. */
  encrypted: boolean;
  method: 'sops-age' | null;
  envFiles: number;
  encryptedFiles: number;
}

export interface SocketState {
  /** True when the dashboard talks to a least-privilege proxy, not the raw socket. */
  proxied: boolean;
  /** Containers with the raw socket bind-mounted. */
  exposedContainers: string[];
  /** True when at least one of those has it read-write. */
  writable: boolean;
}

export interface HardeningState {
  pinning: PinningState;
  secrets: SecretsState;
  dockerSocket: SocketState;
}

export type HardeningStatus = 'done' | 'partial' | 'todo';

export interface HardeningItem {
  id: 'pinning' | 'secrets' | 'docker-socket';
  title: string;
  status: HardeningStatus;
  /** Where it stands, in one sentence, with real numbers. */
  detail: string;
  /** Why it matters. Stated once rather than assumed. */
  rationale: string;
}

export function pinningPct(p: PinningState): number | null {
  if (p.total <= 0) return null;
  return Math.round((p.pinned / p.total) * 100);
}

/** Badge tone for a hardening status. */
export function hardeningTone(status: HardeningStatus): 'ok' | 'warn' | 'err' {
  if (status === 'done') return 'ok';
  if (status === 'partial') return 'warn';
  return 'err';
}

/**
 * The three items, in the order they are worth doing. Pinning first
 * because it is the one with a real blast radius that nobody notices
 * until an image changes underneath them.
 */
export function hardeningItems(state: HardeningState): HardeningItem[] {
  const { pinning, secrets, dockerSocket } = state;
  const pct = pinningPct(pinning);

  const pinningStatus: HardeningStatus =
    !pinning.pinsFileExists || pinning.pinned === 0
      ? 'todo'
      : pinning.pinned === pinning.total
        ? 'done'
        : 'partial';

  const secretsStatus: HardeningStatus =
    secrets.encryptedFiles === 0 ? 'todo' : secrets.encrypted ? 'done' : 'partial';

  const socketStatus: HardeningStatus = dockerSocket.proxied
    ? 'done'
    : dockerSocket.writable
      ? 'todo'
      : 'partial';

  return [
    {
      id: 'pinning',
      title: 'Images pinned to a digest',
      status: pinningStatus,
      detail: !pinning.pinsFileExists
        ? 'No pins.env on this box, so every image floats on its tag.'
        : `${pinning.pinned} of ${pinning.total} image references are pinned${pct === null ? '' : ` (${pct}%)`}.`,
      rationale:
        'A floating tag means the next rebuild can pull a different image, and the first you hear about it is something breaking for no apparent reason.',
    },
    {
      id: 'secrets',
      title: 'Secrets encrypted at rest',
      status: secretsStatus,
      detail:
        secrets.encryptedFiles === 0
          ? `${secrets.envFiles} .env files, none encrypted.`
          : `${secrets.encryptedFiles} of ${secrets.envFiles} .env files encrypted${secrets.method ? ` with ${secrets.method}` : ''}.`,
      rationale:
        'Passwords and API keys currently sit in plain text next to the compose files, which means any backup of the repo is a backup of every secret on the box.',
    },
    {
      id: 'docker-socket',
      title: 'Docker socket behind a proxy',
      status: socketStatus,
      detail: dockerSocket.proxied
        ? 'The dashboard talks to a least-privilege proxy.'
        : `${dockerSocket.exposedContainers.length} container${dockerSocket.exposedContainers.length === 1 ? '' : 's'} mount the socket directly${dockerSocket.writable ? ', at least one read-write' : ', read-only'}.`,
      rationale:
        'Anything that can reach the Docker socket can start a container as root with the host filesystem mounted. That is not a privilege escalation so much as an open door.',
    },
  ];
}

/** Items still outstanding, for the summary line. */
export function outstanding(items: HardeningItem[]): HardeningItem[] {
  return items.filter((i) => i.status !== 'done');
}

export const HardeningApi = {
  async state(): Promise<HardeningState> {
    const { data } = await http.get<HardeningState>('/security/hardening');
    return data;
  },
};
