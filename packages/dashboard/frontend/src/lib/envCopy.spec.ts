import { describe, it, expect } from 'vitest';
import { humanEnvLabel, cleanEnvHelp } from './envCopy';

describe('humanEnvLabel', () => {
  it('turns a SHOUTING_SNAKE key into a sentence-cased label', () => {
    expect(humanEnvLabel('WIREGUARD_PRIVATE_KEY')).toBe('Wireguard private key');
    expect(humanEnvLabel('TZ')).toBe('Tz');
  });

  it('leaves an empty key alone rather than returning nothing', () => {
    expect(humanEnvLabel('')).toBe('');
  });
});

describe('cleanEnvHelp', () => {
  it('strips the divider art .env.example uses for section headers', () => {
    const raw = '---- gluetun: provider selection ----------------------'
      + '-------------- One of: protonvpn, mullvad, surfshark.';
    expect(cleanEnvHelp(raw)).toBe('gluetun: provider selection One of: protonvpn, mullvad, surfshark.');
  });

  it('keeps the first sentence and drops the reference material after it', () => {
    const raw = 'LAN IP of this host — AdGuard binds :53 here so it does not '
      + 'fight with systemd-resolved. Set to your box\'s LAN address. 0.0.0.0 is the safe default.';
    expect(cleanEnvHelp(raw)).toBe(
      'LAN IP of this host — AdGuard binds :53 here so it does not fight with systemd-resolved.');
  });

  it('does not truncate to a useless fragment', () => {
    expect(cleanEnvHelp('Optional. Pin to a country or city.'))
      .toBe('Optional. Pin to a country or city.');
  });

  it('collapses newlines and stray comment markers', () => {
    expect(cleanEnvHelp('#  kill-switch is on by default\n#  leave these alone'))
      .toBe('kill-switch is on by default leave these alone');
  });

  it('returns null when there is nothing readable left', () => {
    expect(cleanEnvHelp('---------------')).toBeNull();
    expect(cleanEnvHelp('')).toBeNull();
    expect(cleanEnvHelp(null)).toBeNull();
    expect(cleanEnvHelp(undefined)).toBeNull();
  });

  it('caps a runaway comment rather than blowing out the row', () => {
    const long = 'A'.repeat(400);
    const out = cleanEnvHelp(long)!;
    expect(out.length).toBeLessThanOrEqual(160);
    expect(out.endsWith('…')).toBe(true);
  });
});
