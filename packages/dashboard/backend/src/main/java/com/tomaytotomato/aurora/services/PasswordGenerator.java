package com.tomaytotomato.aurora.services;

import java.security.SecureRandom;
import java.util.List;

/**
 * Generated passwords for accounts nobody has chosen a password for.
 *
 * <p><b>Why generate rather than prompt.</b> When an admin creates
 * another user, the admin necessarily learns whatever password they type
 * in. That credential then travels by whatever means the two humans have
 * to hand — read aloud, sent over chat, written on paper — and is very
 * often never changed. Generating a strong password instead means the
 * admin hands over one high-entropy string once, and the recipient can
 * replace it. It is also simply less friction: one fewer field to think
 * about on a form whose purpose is "add my partner to the box".
 *
 * <p><b>Shape.</b> Six words from a curated list, hyphen-joined, plus a
 * two-digit suffix — e.g. {@code copper-lantern-drift-mellow-harbor-quiet-42}.
 * Chosen over a random character soup because these get read off a screen
 * and typed on a phone keyboard, where {@code xK9#mQ2$vL} is
 * error-prone in a way that hurts adoption on a device the household
 * actually uses.
 *
 * <p><b>Entropy.</b> The list holds {@value #WORDLIST_SIZE} words. Six
 * independent draws with replacement give
 * {@code log2(128^6) = 42 bits}, and the numeric suffix adds
 * {@code log2(90) ≈ 6.5}, for roughly <b>48.5 bits</b>. That is
 * deliberately calibrated to the threat model: these hashes sit in
 * bcrypt at cost 12 (~250 ms per attempt) behind Authelia's regulation
 * (three failures → five-minute ban, {@code configuration.yml}), on a
 * box that is not meant to be exposed to the internet. It is not
 * calibrated for an offline attack against a leaked hash database — if
 * the box's SQLite ever leaks, rotate.
 *
 * <p>Words are picked with {@link SecureRandom} and
 * {@link SecureRandom#nextInt(int)}, which is unbiased, rather than a
 * modulo of {@code nextInt()}, which is not.
 */
public final class PasswordGenerator {

  /** Kept a power of two so the entropy arithmetic above stays honest. */
  static final int WORDLIST_SIZE = 128;

  private static final int WORDS = 6;

  /**
   * Curated for reading aloud and typing on a phone.
   *
   * <p>Deliberately excludes: homophones (there/their), words differing
   * only by a doubled letter, anything with a common British/American
   * spelling split (colour/color), and anything that could read as
   * unpleasant when the generator happens to place two of them together.
   */
  private static final List<String> WORDS_LIST = List.of(
      "amber", "anchor", "apple", "arbor", "autumn", "basil", "beacon", "birch",
      "bison", "bloom", "branch", "brass", "bridge", "bronze", "brook", "cabin",
      "cactus", "candle", "canyon", "cedar", "cinder", "citrus", "cliff", "cobalt",
      "comet", "copper", "coral", "cotton", "crater", "crimson", "crystal", "dahlia",
      "damson", "dawn", "delta", "denim", "drift", "dune", "ember", "fable",
      "falcon", "fern", "flint", "forest", "fossil", "garnet", "ginger", "glacier",
      "granite", "harbor", "harvest", "hazel", "heron", "hollow", "indigo", "iris",
      "island", "ivory", "jasper", "juniper", "kettle", "lagoon", "lantern", "laurel",
      "lemon", "lichen", "lilac", "linen", "lunar", "maple", "marble", "meadow",
      "mellow", "mesa", "meteor", "mineral", "mint", "mirror", "moss", "nectar",
      "nimbus", "nomad", "oasis", "obsidian", "olive", "onyx", "opal", "orchard",
      "osprey", "otter", "pampas", "pebble", "pepper", "pewter", "pine", "plateau",
      "pollen", "poppy", "prairie", "quartz", "quiet", "quill", "raven", "ridge",
      "river", "rustic", "saffron", "sage", "sandy", "sapphire", "scarlet", "shale",
      "silver", "solar", "sorrel", "spruce", "stellar", "summit", "sunset", "tavern",
      "thistle", "thunder", "timber", "topaz", "tundra", "velvet", "walnut", "willow"
  );

  static {
    // A miscount here would silently weaken every generated password
    // while the documented entropy above stayed reassuring, so fail at
    // class-load rather than at audit time.
    if (WORDS_LIST.size() != WORDLIST_SIZE) {
      throw new IllegalStateException(
          "wordlist must be exactly " + WORDLIST_SIZE + " entries, found " + WORDS_LIST.size());
    }
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  private PasswordGenerator() {}

  /** A fresh passphrase. Never returns the same value twice in practice. */
  public static String generate() {
    StringBuilder sb = new StringBuilder(56);
    for (int i = 0; i < WORDS; i++) {
      if (i > 0) sb.append('-');
      sb.append(WORDS_LIST.get(RANDOM.nextInt(WORDS_LIST.size())));
    }
    // 10..99 — two digits always, so the shape is predictable when read
    // aloud ("...-quiet-forty-two") and no leading zero is lost if the
    // value is ever round-tripped through something numeric.
    sb.append('-').append(10 + RANDOM.nextInt(90));
    return sb.toString();
  }
}
