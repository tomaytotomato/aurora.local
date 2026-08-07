import { describe, expect, it } from 'vitest';
import type { EnvVarSpec } from '@/api/packages';
import { buildEnvForm, isEnvFormDirty, validateEnvForm } from './envForm';

function spec(over: Partial<EnvVarSpec> & { key: string }): EnvVarSpec {
  return { secret: false, required: false, ...over };
}

describe('buildEnvForm', () => {
  it('seeds one form entry per spec from the wire values', () => {
    const specs = [spec({ key: 'PUID' }), spec({ key: 'PGID' })];
    const form = buildEnvForm(specs, { PUID: '1000', PGID: '1000' });
    expect(form).toEqual({ PUID: '1000', PGID: '1000' });
  });

  it('falls back to the spec default when the server omitted a key', () => {
    const specs = [spec({ key: 'PUID', value: '1000' })];
    expect(buildEnvForm(specs, {})).toEqual({ PUID: '1000' });
  });

  it('falls back to an empty string when neither the wire nor the spec has a value', () => {
    const specs = [spec({ key: 'CADDY_EMAIL' })];
    expect(buildEnvForm(specs, {})).toEqual({ CADDY_EMAIL: '' });
  });
});

describe('isEnvFormDirty', () => {
  it('is false when the form matches the baseline exactly', () => {
    expect(isEnvFormDirty({ A: '1' }, { A: '1' })).toBe(false);
  });

  it('is true when any field has changed', () => {
    expect(isEnvFormDirty({ A: '2' }, { A: '1' })).toBe(true);
  });

  it('is true when a field was newly typed against an unset baseline', () => {
    expect(isEnvFormDirty({ A: '1' }, { A: '' })).toBe(true);
  });
});

describe('validateEnvForm', () => {
  it('flags a required field left blank', () => {
    const specs = [spec({ key: 'SAMBA_PASSWORD', required: true })];
    const errors = validateEnvForm(specs, { SAMBA_PASSWORD: '' });
    expect(errors).toHaveProperty('SAMBA_PASSWORD');
  });

  it('flags a required field that is only whitespace', () => {
    const specs = [spec({ key: 'SAMBA_PASSWORD', required: true })];
    const errors = validateEnvForm(specs, { SAMBA_PASSWORD: '   ' });
    expect(errors).toHaveProperty('SAMBA_PASSWORD');
  });

  it('does not flag an optional field left blank', () => {
    const specs = [spec({ key: 'CADDY_EMAIL', required: false })];
    expect(validateEnvForm(specs, { CADDY_EMAIL: '' })).toEqual({});
  });

  it('does not flag a required field once filled in', () => {
    const specs = [spec({ key: 'SAMBA_PASSWORD', required: true })];
    expect(validateEnvForm(specs, { SAMBA_PASSWORD: 'hunter2' })).toEqual({});
  });
});
