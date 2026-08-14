package com.tomaytotomato.aurora.services;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.Base64;

/**
 * WireGuard keypair generation — pure JVM crypto, no {@code wg} process.
 *
 * <p>The obvious way to generate a WireGuard keypair is to shell out to
 * {@code wg genkey} (private key on stdout) and {@code wg pubkey} (reads
 * the private key on <em>stdin</em>, writes the public key on stdout).
 * {@link CommandRunner#run} takes an argv list and nothing else — there
 * is deliberately no stdin support, because every other command this
 * seam runs is a read, not a pipe. Two ways round that were considered
 * and rejected:
 *
 * <ul>
 *   <li>Add stdin support to {@code CommandRunner} for this one caller.
 *       Plausible, but it is new surface on a seam three other domains
 *       already depend on, for a feature that does not need a process
 *       at all (see below).</li>
 *   <li>Shell out to {@code bash -c "wg genkey | wg pubkey"}. This
 *       reintroduces exactly the risk {@link CommandRunner}'s javadoc
 *       warns about — constructing a shell command line — even though
 *       no operator-supplied value is spliced into this particular one.
 *       Not worth the precedent.</li>
 * </ul>
 *
 * <p>WireGuard keys are raw X25519 keys, base64-encoded. The JDK has
 * shipped a conformant X25519 implementation since 11 (JEP 324); asking
 * it directly for a keypair needs no external binary, no stdin, and is
 * trivially unit-testable. {@code CommandRunner} is still the seam for
 * everything that genuinely is a process call in this domain — reading
 * live peer/handshake state via {@code wg show <iface> dump} — this
 * class only replaces the two subcommands that have no argv-shaped
 * interface in the first place.
 */
public final class WireGuardKeys {

  private WireGuardKeys() {}

  /** A generated keypair, both halves base64-encoded, wire-ready. */
  public record KeyPair(String privateKeyBase64, String publicKeyBase64) {}

  /** Generate a fresh, random X25519 keypair. */
  public static KeyPair generate() {
    try {
      var kpg = KeyPairGenerator.getInstance("X25519");
      var kp = kpg.generateKeyPair();
      var priv = (XECPrivateKey) kp.getPrivate();
      var pub = (XECPublicKey) kp.getPublic();

      byte[] privBytes = priv.getScalar().orElseThrow(() ->
          new IllegalStateException("this JVM did not expose the raw X25519 private scalar"));
      byte[] pubBytes = uCoordinateToLittleEndian(pub.getU());

      return new KeyPair(encode(privBytes), encode(pubBytes));
    } catch (NoSuchAlgorithmException e) {
      // Every JDK since 11 ships X25519; this is a "this build is broken",
      // not a "the operator did something wrong" condition.
      throw new IllegalStateException("X25519 is not available on this JVM", e);
    }
  }

  /**
   * {@link XECPublicKey#getU()} hands back the curve point's u-coordinate
   * as a plain {@link BigInteger}, which is big-endian and may be padded
   * with a leading sign byte. WireGuard (and X25519 generally) encodes
   * points as 32 raw little-endian bytes, so this walks the BigInteger's
   * byte array from its least-significant end into a fixed 32-byte
   * little-endian buffer, which handles both the padding and the
   * endianness in one pass.
   */
  private static byte[] uCoordinateToLittleEndian(BigInteger u) {
    byte[] bigEndian = u.toByteArray();
    byte[] littleEndian = new byte[32];
    for (int i = 0; i < littleEndian.length && i < bigEndian.length; i++) {
      littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
    }
    return littleEndian;
  }

  private static String encode(byte[] raw) {
    return Base64.getEncoder().encodeToString(raw);
  }
}
