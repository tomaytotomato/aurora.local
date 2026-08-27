import { describe, expect, it } from 'vitest';
import { DEFAULT_REDIRECT, isSafeRedirect, safeRedirect } from './safeRedirect';

/**
 * Post-login redirect vetting.
 *
 * The bug these pin: the auth guard parks the intended destination in
 * `/login?from=…`, but LoginView pushed '/' unconditionally, so signing
 * in from a deep link always dumped you on the dashboard home.
 *
 * The bug they prevent: `from` comes off the query string, so fixing the
 * first bug by pushing it verbatim would turn the login page into an
 * open redirect — a real aurora.local sign-in that hands the user to an
 * attacker's page afterwards.
 */
describe('safeRedirect', () => {
  it('honours the relative path the auth guard parked in ?from=', () => {
    // The exact case from the report: /login?from=/apps/roundcube.
    expect(safeRedirect('/apps/roundcube')).toBe('/apps/roundcube');
  });

  it('keeps query and hash on the resumed destination', () => {
    expect(safeRedirect('/apps/catalogue?tab=installed#media')).toBe(
      '/apps/catalogue?tab=installed#media',
    );
  });

  it('falls back to the dashboard when ?from= is absent', () => {
    expect(safeRedirect(undefined)).toBe(DEFAULT_REDIRECT);
    expect(safeRedirect(null)).toBe(DEFAULT_REDIRECT);
    expect(safeRedirect('')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects an absolute URL to another origin', () => {
    expect(safeRedirect('https://evil.tld/harvest')).toBe(DEFAULT_REDIRECT);
    expect(safeRedirect('http://evil.tld')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects a protocol-relative URL', () => {
    // '//evil.tld' inherits the current scheme and leaves the origin —
    // the single most-missed open-redirect vector.
    expect(safeRedirect('//evil.tld/harvest')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects the backslash form of protocol-relative', () => {
    // Browsers normalise '\' to '/' in URLs, so '/\evil.tld' escapes the
    // origin even though a naive startsWith('/') check passes it.
    expect(safeRedirect('/\\evil.tld')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects a javascript: payload', () => {
    expect(safeRedirect('javascript:alert(document.cookie)')).toBe(DEFAULT_REDIRECT);
    expect(safeRedirect('JavaScript:alert(1)')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects a data: payload', () => {
    expect(safeRedirect('data:text/html,<script>alert(1)</script>')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects values carrying control characters', () => {
    // Smuggling a scheme past a check that only looks at the first byte.
    expect(safeRedirect('/apps\n/../../evil')).toBe(DEFAULT_REDIRECT);
    expect(safeRedirect('java\tscript:alert(1)')).toBe(DEFAULT_REDIRECT);
  });

  it('rejects a bare path with no leading slash', () => {
    expect(safeRedirect('apps/roundcube')).toBe(DEFAULT_REDIRECT);
  });

  it('does not loop back to the login page', () => {
    expect(safeRedirect('/login')).toBe(DEFAULT_REDIRECT);
    // Including the shape the guard itself would produce if /login were
    // ever the "intended destination".
    expect(safeRedirect('/login?from=/apps/roundcube')).toBe(DEFAULT_REDIRECT);
  });

  it('takes the first usable entry when ?from= is repeated', () => {
    // vue-router hands back an array for '?from=a&from=b'; stringifying
    // it would navigate to the nonsense path '/apps,/other'.
    expect(safeRedirect(['/apps/roundcube', '/other'])).toBe('/apps/roundcube');
    expect(safeRedirect([null, '/apps/notes'])).toBe('/apps/notes');
    expect(safeRedirect([])).toBe(DEFAULT_REDIRECT);
  });

  it('rejects a value padded with whitespace', () => {
    expect(safeRedirect('  /apps/roundcube  ')).toBe(DEFAULT_REDIRECT);
  });
});

describe('isSafeRedirect', () => {
  it('accepts a plain relative path', () => {
    expect(isSafeRedirect('/apps/roundcube')).toBe(true);
    expect(isSafeRedirect('/')).toBe(true);
  });

  it('rejects the off-origin and self-loop forms', () => {
    expect(isSafeRedirect('https://evil.tld')).toBe(false);
    expect(isSafeRedirect('//evil.tld')).toBe(false);
    expect(isSafeRedirect('/login')).toBe(false);
    expect(isSafeRedirect('')).toBe(false);
  });

  // Unicode line terminators. Not exploitable through either current
  // sink (router.push treats them as ordinary path characters, and the
  // interceptor encodeURIComponent's the value), but they are JS line
  // terminators and would break out of a string literal if a future
  // caller ever interpolated a redirect target into inline script.
  // Rejected at the helper so no such caller can inherit the hole.
  it('rejects Unicode line separators', () => {
    expect(safeRedirect('/\u2028evil')).toBe('/');
    expect(safeRedirect('/\u2029evil')).toBe('/');
  });
});
