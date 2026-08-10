import { describe, expect, it } from 'vitest';

import {
  egressLabel,
  egressTone,
  protectionSummary,
  totalBlocked,
  tunnelConsequences,
  unprotectedVhosts,
  untunnelConsequences,
  type PackageNetwork,
  type VhostProtection,
} from './network';

function network(over: Partial<PackageNetwork> = {}): PackageNetwork {
  return {
    package: 'media',
    mode: 'direct',
    gateway: 'gluetun',
    locked: false,
    lockedReason: null,
    containers: ['sonarr'],
    publishedPorts: [8989],
    egressIp: '81.132.44.19',
    egressCountry: 'GB',
    gatewayHealthy: true,
    ...over,
  };
}

function vhost(over: Partial<VhostProtection> & { vhost: string }): VhostProtection {
  return {
    package: 'photos',
    publiclyResolvable: true,
    authelia: false,
    rateLimit: { enabled: false, requestsPerMinute: 60 },
    geoBlock: { enabled: false, allowCountries: [] },
    botDetection: false,
    blocked24h: 0,
    lastBlockedAt: null,
    ...over,
  };
}

describe('tunnelConsequences', () => {
  it('leads with the restart, because that is the bit that bites first', () => {
    expect(tunnelConsequences(network())[0]).toBe('This app restarts.');
  });

  it('counts the containers when there is more than one', () => {
    const lines = tunnelConsequences(network({ containers: ['prowlarr', 'sonarr', 'qbittorrent'] }));
    expect(lines[0]).toBe('3 containers restart.');
  });

  it('warns that published ports move onto the gateway', () => {
    // A namespaced app cannot publish its own ports; this is the step
    // people miss when doing it by hand.
    const lines = tunnelConsequences(network({ publishedPorts: [8080, 8989] }));
    expect(lines.join(' ')).toContain('8080, 8989');
    expect(lines.join(' ')).toContain('gateway');
  });

  it('says nothing about ports when the app publishes none', () => {
    const lines = tunnelConsequences(network({ publishedPorts: [] }));
    expect(lines.join(' ')).not.toContain('port');
  });

  it('says the app loses its address on the Aurora network', () => {
    expect(tunnelConsequences(network()).join(' ')).toContain('no longer reach it by name');
  });

  it('is explicit that the kill switch means no network at all, and that it is the point', () => {
    const last = tunnelConsequences(network()).at(-1) ?? '';
    expect(last).toContain('no network at all');
    expect(last).toContain('the point');
  });
});

describe('untunnelConsequences', () => {
  it('says the traffic goes back to the home connection, in as many words', () => {
    expect(untunnelConsequences(network()).join(' ')).toContain('your own IP address');
  });

  it('still leads with the restart', () => {
    expect(untunnelConsequences(network())[0]).toBe('This app restarts.');
  });
});

describe('egressTone / egressLabel', () => {
  it('leaves a direct app neutral rather than calling it good or bad', () => {
    expect(egressTone(network())).toBe('neutral');
    expect(egressLabel(network())).toBe('Direct');
  });

  it('calls a working tunnel good', () => {
    const n = network({ mode: 'vpn' });
    expect(egressTone(n)).toBe('ok');
    expect(egressLabel(n)).toBe('Through the VPN');
  });

  it('warns when an app is tunnelled through a gateway that is down', () => {
    // Not an error: nothing is leaking. But the app is offline.
    const n = network({ mode: 'vpn', gatewayHealthy: false });
    expect(egressTone(n)).toBe('warn');
    expect(egressLabel(n)).toBe('VPN down');
  });
});

describe('unprotectedVhosts', () => {
  it('finds an internet-facing address with no sign-in and nothing in front of it', () => {
    const rows = [vhost({ vhost: 'photos.aurora.local' })];
    expect(unprotectedVhosts(rows).map((v) => v.vhost)).toEqual(['photos.aurora.local']);
  });

  it('leaves LAN-only names alone, since rate-limiting your own laptop is pure downside', () => {
    expect(unprotectedVhosts([vhost({ vhost: 'grafana.aurora.local', publiclyResolvable: false })])).toEqual([]);
  });

  it('counts sign-in as protection', () => {
    expect(unprotectedVhosts([vhost({ vhost: 'notes.aurora.local', authelia: true })])).toEqual([]);
  });

  it('counts any one of the three edge controls as protection', () => {
    const rate = vhost({ vhost: 'a', rateLimit: { enabled: true, requestsPerMinute: 60 } });
    const geo = vhost({ vhost: 'b', geoBlock: { enabled: true, allowCountries: ['GB'] } });
    const bots = vhost({ vhost: 'c', botDetection: true });
    expect(unprotectedVhosts([rate, geo, bots])).toEqual([]);
  });
});

describe('protectionSummary', () => {
  it('is honest when there is nothing there', () => {
    expect(protectionSummary(vhost({ vhost: 'a' }))).toBe('Nothing in front of it');
  });

  it('lists what is actually on', () => {
    const v = vhost({
      vhost: 'a',
      rateLimit: { enabled: true, requestsPerMinute: 120 },
      geoBlock: { enabled: true, allowCountries: ['GB', 'IE'] },
      botDetection: true,
      authelia: true,
    });
    expect(protectionSummary(v)).toBe('120/min · GB, IE only · bot filtering · sign-in required');
  });

  it('spells out that geo-blocking with an empty list blocks everyone', () => {
    const v = vhost({ vhost: 'a', geoBlock: { enabled: true, allowCountries: [] } });
    expect(protectionSummary(v)).toContain('everywhere blocked');
  });
});

describe('totalBlocked', () => {
  it('adds up across every vhost', () => {
    const rows = [vhost({ vhost: 'a', blocked24h: 1_284 }), vhost({ vhost: 'b', blocked24h: 412 })];
    expect(totalBlocked(rows)).toBe(1_696);
  });

  it('is zero for an empty list', () => {
    expect(totalBlocked([])).toBe(0);
  });
});
