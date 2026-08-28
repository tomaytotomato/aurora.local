import { describe, it, expect } from 'vitest';
import { containerEventText, auditActionText } from './eventCopy';

describe('containerEventText', () => {
  it('reads as a sentence about the service, not a docker action name', () => {
    expect(containerEventText('start', 'adguard')).toBe('adguard started');
    expect(containerEventText('health:healthy', 'stalwart')).toBe('stalwart is healthy');
    expect(containerEventText('health:unhealthy', 'jellyfin')).toBe('jellyfin stopped responding');
  });

  it('keeps an unknown action visible rather than dropping the event', () => {
    expect(containerEventText('pause', 'caddy')).toBe('caddy: pause');
  });
});

describe('auditActionText', () => {
  it('translates the keys a fresh box actually produces', () => {
    expect(auditActionText('mdns.alias.publish')).toBe('Published an address on the network');
    expect(auditActionText('onboarding.complete')).toBe('Finished first-run setup');
    expect(auditActionText('stalwart.secrets.bootstrap')).toBe('Set up the mail server');
  });

  it('carries the subject through', () => {
    expect(auditActionText('job.finish:enable:jellyfin')).toContain('Finished a task');
  });

  it('makes an unmapped key readable instead of hiding it', () => {
    expect(auditActionText('disks.parity.sync')).toBe('Disks parity sync');
  });
});
