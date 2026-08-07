// Pure helpers behind PackageDetail's Config tab. Kept free of Vue so
// vitest can pin the branches without mounting the component — same
// approach as http-error-copy.ts.

import type { EnvVarSpec } from '@/api/packages';

export type EnvFormValues = Record<string, string>;

/** Seed an editable form from the wire values, one entry per known spec.
 * Falls back to the spec's own `value` (a manifest default) if the env
 * endpoint didn't return that key — a brand-new package can hit this. */
export function buildEnvForm(specs: EnvVarSpec[], values: EnvFormValues): EnvFormValues {
  const form: EnvFormValues = {};
  for (const spec of specs) {
    form[spec.key] = values[spec.key] ?? spec.value ?? '';
  }
  return form;
}

/** True once any field differs from the last known-good server value. */
export function isEnvFormDirty(form: EnvFormValues, baseline: EnvFormValues): boolean {
  return Object.keys(form).some((k) => form[k] !== baseline[k]);
}

/** Required fields left blank, keyed by field so the template can point
 * the error at the right input. Empty/whitespace-only counts as blank. */
export function validateEnvForm(specs: EnvVarSpec[], form: EnvFormValues): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const spec of specs) {
    if (spec.required && !(form[spec.key] ?? '').trim()) {
      errors[spec.key] = 'This value is required.';
    }
  }
  return errors;
}
