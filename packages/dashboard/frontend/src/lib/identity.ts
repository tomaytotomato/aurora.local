// Aurora identity rendering — the single source of truth for "what should
// the header say the box is called?".
//
// Design rules (locked in iter-3 B2 fix, 2026-08-02):
//
//   1. Fall back to "aurora.local" only when BOTH hostname and domain are
//      absent — this is the product default and matches OnboardingDomain's
//      initial value.
//   2. When only one half is present, render an em-dash for the missing
//      half so nothing shows a bare trailing dot or the literal string
//      "undefined". This keeps the header from ever reading as broken.
//   3. **DEDUP RULE**: when `domain` already starts with `hostname.`
//      (case-insensitive), the dashboard vhost IS `domain` — the user
//      picked `aurora.local` as their domain AND kept `aurora` as their
//      hostname, so the fully-qualified dashboard URL is just `aurora.local`,
//      not `aurora.aurora.local`. This is the common case for the default
//      install path.
//   4. Otherwise concatenate as `${hostname}.${domain}` — user with
//      hostname=`aurora` + domain=`home.local` reads as `aurora.home.local`.
//
// Service subdomains (`sonarr.aurora.local`) are always built as
// `<pkg>.<domain>` regardless of hostname; only the dashboard apex has to
// deal with the dedup case, because the dashboard IS the apex.

/**
 * Compute the dashboard identity string for a given hostname/domain pair.
 * See file-level comment for the four rules.
 */
export function renderIdentity(
  hostname: string | null | undefined,
  domain: string | null | undefined,
): string {
  const h = normalise(hostname);
  const d = normalise(domain);
  if (!h && !d) return 'aurora.local';
  if (!h) return `\u2014.${d}`;
  if (!d) return `${h}.\u2014`;
  // Dedup rule: hostname is already the leading label of the domain.
  if (d.toLowerCase().startsWith(h.toLowerCase() + '.')) return d;
  return `${h}.${d}`;
}

function normalise(v: string | null | undefined): string | null {
  if (v === null || v === undefined) return null;
  const trimmed = String(v).trim();
  return trimmed.length === 0 ? null : trimmed;
}
