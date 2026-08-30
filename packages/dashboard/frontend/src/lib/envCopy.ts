// Turning `.env` mechanics into something a person can read.
//
// Package env vars reach the UI straight from `.env.example`: the key as
// written, and the comment lines above it, verbatim. On a real package that
// produced rows like
//
//   VPN_SERVICE_PROVIDER   ---- gluetun: provider selection --------------
//                          -------------- One of: protonvpn, mullvad, ...
//
// — section-divider art from a file the reader has never seen, next to a
// SHOUTING_SNAKE_CASE identifier. Both helpers below are pure so vitest can
// pin them without mounting anything; both are used by the pre-install
// preview and the installed Config tab, so those two surfaces cannot drift
// apart in how they name the same field.

/** `WIREGUARD_PRIVATE_KEY` → `Wireguard private key`. */
export function humanEnvLabel(key: string): string {
  const words = key.toLowerCase().split('_').filter(Boolean);
  if (words.length === 0) return key;
  const [first, ...rest] = words;
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' ');
}

/**
 * Comment text from `.env.example`, cleaned up for display, or null when
 * nothing readable survives.
 *
 * - Drops ASCII divider runs (`----`, `====`, `####`, `***`), including the
 *   ones wrapped around a label: `---- gluetun: provider selection ----`
 *   becomes `gluetun: provider selection`.
 * - Collapses the whitespace/newlines that survive comment stripping.
 * - Keeps the first sentence only: the rest is reference material for
 *   whoever is editing the file, and a table row is not the place for it.
 */
export function cleanEnvHelp(comment: string | null | undefined): string | null {
  if (!comment) return null;

  const cleaned = comment
    // Divider runs anywhere in the text.
    .replace(/[-=#*_]{3,}/g, ' ')
    // Comment markers at the start of any line, not just the first: a
    // multi-line `.env` comment arrives with every line still marked.
    .replace(/(^|\n)[\s#]+/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();

  if (!cleaned) return null;

  // First sentence, but only if it leaves something substantial; a
  // three-word fragment followed by the real explanation is worse than
  // both. 120 characters is roughly a table row at our type size.
  const firstStop = cleaned.search(/[.!?](\s|$)/);
  const candidate = firstStop > 0 ? cleaned.slice(0, firstStop + 1) : cleaned;
  const text = candidate.length >= 20 || candidate.length === cleaned.length
    ? candidate
    : cleaned;

  return text.length > 160 ? text.slice(0, 157).trimEnd() + '…' : text;
}
