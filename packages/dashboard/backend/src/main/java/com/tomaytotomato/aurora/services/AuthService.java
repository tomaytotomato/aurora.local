package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Password hashing + verification.
 *
 * <p>v0.1 uses BCrypt (Spring Security's pure-Java implementation — no JNI,
 * no native libs, works on musl/Alpine). The brief specifies argon2id but
 * argon2-jvm ships a JNA-loaded shared object built against glibc that
 * SIGSEGVs under musl. Migration to argon2id is queued for v0.2 either
 * via a pure-Java implementation (bouncycastle) or by moving the runtime
 * image to eclipse-temurin:25-jre-noble.
 */
@Service
public class AuthService {

  // BCrypt cost 12 ≈ 250 ms on a Core i5-6500T. Adjust up when the
  // metrics endpoint tells us login latency is dominant.
  private static final int BCRYPT_COST = 12;

  private final AdminUserRepo users;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);

  public AuthService(AdminUserRepo users) {
    this.users = users;
  }

  public String hash(char[] password) {
    try {
      return encoder.encode(new String(password));
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  public boolean verify(String hash, char[] password) {
    try {
      return encoder.matches(new String(password), hash);
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  public Optional<AdminUser> authenticate(String username, String password) {
    return users.findByUsername(username)
        .filter(u -> verify(u.passwordHash(), password.toCharArray()));
  }

  /** Look up the DB role for a username; empty when the user is gone. */
  public Optional<com.tomaytotomato.aurora.domain.Role> roleFor(String username) {
    return users.findByUsername(username).map(AdminUser::role);
  }

  /** Look up the DB tz for a username; empty when the user is gone. */
  public Optional<String> tzFor(String username) {
    return users.findByUsername(username).map(AdminUser::tz);
  }
}
