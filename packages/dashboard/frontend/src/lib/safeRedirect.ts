// Where to send someone after they sign in.
//
// The auth guard (router/index.ts) bounces an unauthenticated request to
// `/login?from=<intended path>` so the user resumes where they were
// headed instead of being dumped on the dashboard home. LoginView used
// to ignore that entirely and hard-code `router.push('/')`, so the
// round-trip silently lost the destination — clicking a link to
// /apps/roundcube and signing in landed you on /.
//
// `from` arrives on the query string, so it is attacker-controllable:
// anyone can mail a link to `https://aurora.local/login?from=<anything>`.
// Handing that value straight to `router.push` (or worse,
// `window.location`) is the classic open-redirect: the victim sees a
// legitimate aurora.local login, authenticates for real, and is then
// forwarded to an attacker's page that can pose as Aurora and phish
// whatever it likes. So this is an allow-list, not a deny-list — we
// accept one narrow shape and reject everything else, rather than trying
// to enumerate the hostile forms (which is how `//evil.tld` and
// `\/\/evil.tld` and `java\nscript:` bugs get shipped).
//
// The accepted shape is a same-origin *relative* path:
//   - must start with exactly one '/'  → keeps us on this origin
//   - must not start with '//' or '/\' → protocol-relative, sends the
//     browser to another host entirely
// That rule alone excludes absolute URLs (`https://evil.tld/...`) and
// scheme payloads (`javascript:...`), because neither begins with '/'.

/** Query values arrive as string | string[] | null from vue-router. */
type QueryValue = string | (string | null)[] | null | undefined;

/** Where we land when `from` is missing, unsafe, or would loop. */
export const DEFAULT_REDIRECT = '/';

/**
 * True when `value` is a path we are willing to navigate to after login.
 *
 * Exported for the router guard and for tests; callers that just want a
 * destination should use {@link safeRedirect}.
 */
export function isSafeRedirect(value: string): boolean {
  // Reject empty / whitespace-only.
  if (!value || value.trim() !== value || value.length === 0) return false;

  // Must be rooted at this origin.
  if (!value.startsWith('/')) return false;

  // Protocol-relative ('//evil.tld' → another origin). The backslash
  // variant is here because browsers normalise '\' to '/' in URLs, so
  // '/\evil.tld' is treated as protocol-relative by the parser even
  // though it does not look like it.
  if (value.startsWith('//') || value.startsWith('/\\')) return false;

  // Control characters (NUL, newline, tab) are stripped or normalised
  // inconsistently across browsers and are only ever present in an
  // attempt to smuggle a scheme past a naive check.
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(value)) return false;

  // Sending someone back to /login after they just logged in is a loop.
  // Covers the bare path and any query/hash suffix ('/login?from=…').
  const path = value.split(/[?#]/)[0];
  if (path === '/login') return false;

  return true;
}

/**
 * The post-login destination for a `from` query value.
 *
 * Returns {@link DEFAULT_REDIRECT} whenever `from` is absent, malformed,
 * unsafe, or would bounce back to the login page. Never throws — callers
 * are navigating and always need somewhere to go.
 */
export function safeRedirect(from: QueryValue): string {
  // vue-router yields an array for a repeated key ('?from=a&from=b').
  // Take the first usable entry rather than stringifying the array into
  // the nonsense path '/a,/b'.
  const raw = Array.isArray(from) ? from.find((v) => typeof v === 'string' && v.length > 0) : from;
  if (typeof raw !== 'string') return DEFAULT_REDIRECT;
  return isSafeRedirect(raw) ? raw : DEFAULT_REDIRECT;
}
