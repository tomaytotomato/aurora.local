import { describe, expect, it } from 'vitest';

import { canDeploy, dangerousWarnings, describeStack, stackTone } from '@/api/custom';

import { SAMPLE_COMPOSE, validateCompose } from './fixtures/custom';

/**
 * The mock's compose reader is a shallow regex pass rather than a YAML
 * parser — the frontend has no yaml dependency and does not want one,
 * and the real backend will parse properly. These pin the behaviour that
 * drives the screen: two tiers, consequences rather than syntax, and the
 * dangerous handful surfaced separately.
 */

function kinds(issues: { kind: string }[]): string[] {
  return issues.map((i) => i.kind).sort();
}

describe('validateCompose', () => {
  it('refuses an empty file rather than reporting a clean bill of health', () => {
    const v = validateCompose('');
    expect(v.valid).toBe(false);
    expect(canDeploy(v)).toBe(false);
  });

  it('catches tabs, which is the classic paste failure with a useless native error', () => {
    const v = validateCompose('services:\n\tthing:\n\t\timage: nginx:1.27\n');
    expect(kinds(v.errors)).toContain('parse-error');
  });

  it('reads services, images, ports and volumes out of an ordinary file', () => {
    const v = validateCompose(SAMPLE_COMPOSE);
    expect(v.services).toEqual(['calibre-web']);
    expect(v.images).toEqual(['linuxserver/calibre-web:latest']);
    expect(v.ports).toEqual([8083]);
    expect(v.volumes.length).toBeGreaterThan(0);
  });

  it('blocks a port one of Aurora’s own apps already holds', () => {
    const v = validateCompose('services:\n  thing:\n    image: nginx:1.27\n    ports:\n      - "8096:80"\n');
    expect(kinds(v.errors)).toContain('port-conflict');
    expect(canDeploy(v)).toBe(false);
  });

  it('blocks a privileged port, which in a homelab file is nearly always a slip', () => {
    const v = validateCompose('services:\n  thing:\n    image: nginx:1.27\n    ports:\n      - "81:80"\n');
    expect(kinds(v.errors)).toContain('privileged-port');
  });

  it('blocks a container name belonging to Aurora', () => {
    const v = validateCompose('services:\n  thing:\n    image: nginx:1.27\n    container_name: caddy\n');
    expect(kinds(v.errors)).toContain('name-conflict');
  });

  it('blocks a file with no services at all', () => {
    expect(kinds(validateCompose('version: "3"\n').errors)).toContain('no-services');
  });

  it('warns about a floating tag without refusing it', () => {
    const v = validateCompose(SAMPLE_COMPOSE);
    expect(kinds(v.warnings)).toContain('unpinned-image');
    // Advisory: it still deploys.
    expect(canDeploy(v)).toBe(true);
  });

  it('warns about the three that can do real damage, and marks them as such', () => {
    const yaml = [
      'services:',
      '  thing:',
      '    image: nginx:1.27',
      '    privileged: true',
      '    network_mode: host',
      '    restart: unless-stopped',
      '    mem_limit: 512m',
      '    volumes:',
      '      - /var/run/docker.sock:/var/run/docker.sock',
      '',
    ].join('\n');
    const v = validateCompose(yaml);
    expect(kinds(v.warnings)).toEqual(['docker-socket', 'host-network', 'privileged']);
    expect(kinds(dangerousWarnings(v))).toEqual(['docker-socket', 'host-network', 'privileged']);
    expect(canDeploy(v)).toBe(true);
  });

  it('warns about no restart policy and no memory cap, which a forum paste never has', () => {
    const v = validateCompose(SAMPLE_COMPOSE);
    expect(kinds(v.warnings)).toContain('no-restart-policy');
    expect(kinds(v.warnings)).toContain('uncapped');
  });

  it('says nothing when a file is genuinely tidy', () => {
    const yaml = [
      'services:',
      '  thing:',
      '    image: nginx:1.27.3',
      '    restart: unless-stopped',
      '    mem_limit: 256m',
      '    ports:',
      '      - "8099:80"',
      '',
    ].join('\n');
    const v = validateCompose(yaml);
    expect(v.errors).toEqual([]);
    expect(v.warnings).toEqual([]);
    expect(canDeploy(v)).toBe(true);
  });
});

describe('describeStack', () => {
  it('counts rather than listing, so a nine-service file does not fill the dialog', () => {
    expect(describeStack(validateCompose(SAMPLE_COMPOSE))).toBe('1 service, 1 port, 2 volumes');
  });
});

describe('stackTone', () => {
  it('only calls a running stack good, and a draft neither good nor bad', () => {
    expect(stackTone('running')).toBe('ok');
    expect(stackTone('failed')).toBe('err');
    expect(stackTone('stopped')).toBe('warn');
    expect(stackTone('draft')).toBe('neutral');
  });
});
