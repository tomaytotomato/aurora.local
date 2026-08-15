// Human-friendly labels for package and category slugs. Both are keyed the
// same way (core, media, home-automation, ai); several views rendered the
// raw slug, so a user who picked "photos" and "media" saw `home-automation`,
// `ai`, `git` as monospace chips, and the catalogue's category eyebrow
// showed `home-automation` and `ai` verbatim. Prefer the catalogue `title`
// for packages; otherwise prettify the slug, with a small acronym map so
// ai/vpn/etc. don't title-case wrongly.

const ACRONYMS: Record<string, string> = {
  ai: 'AI',
  vpn: 'VPN',
  tls: 'TLS',
  dns: 'DNS',
  dlna: 'DLNA',
  smb: 'SMB',
};

/** Turn a slug into a readable label: `home-automation` → `Home Automation`. */
export function prettyPackageName(slug: string): string {
  return slug
    .split('-')
    .map((w) => ACRONYMS[w.toLowerCase()] ?? w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/** Prefer an explicit catalogue title, falling back to the prettified slug. */
export function packageLabel(pkg: { name: string; title?: string | null }): string {
  const t = pkg.title?.trim();
  return t && t.length > 0 ? t : prettyPackageName(pkg.name);
}

/**
 * Same slug-to-label prettifier as {@link prettyPackageName}, named for its
 * other call site: a package `category` (e.g. `home-automation`, `ai`) is
 * the same kind of slug as a package name, so there is no reason for a
 * second implementation — this alias just documents intent where a
 * category, not a package, is being labelled.
 */
export const categoryLabel = prettyPackageName;
