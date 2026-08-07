// Human-friendly package names. Packages are keyed by slug (core, media,
// home-automation, ai); several views rendered the raw slug, so a user who
// picked "photos" and "media" saw `home-automation`, `ai`, `git` as
// monospace chips. Prefer the catalogue `title`; otherwise prettify the
// slug, with a small acronym map so ai/vpn/etc. don't title-case wrongly.

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
