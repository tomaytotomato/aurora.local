package com.tomaytotomato.aurora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

/**
 * v0.1 security model.
 *
 * <p>Rules:
 * <ul>
 *   <li>Static SPA assets are public.</li>
 *   <li>/api/auth/login is public (POST only).</li>
 *   <li>/api/onboarding/** is public IFF no admin user exists yet
 *       (guarded at the controller layer via {@link OnboardingService#isBootstrapMode()}).</li>
 *   <li>Everything else under /api/** requires an authenticated session.</li>
 * </ul>
 *
 * <p>CSRF is disabled for /api/**. Rationale: v0.1 SPA lives at the same origin
 * as the API, uses session cookies with SameSite=Lax, and no cross-site form
 * posts exist. We accept the risk for the skeleton; M2 will bring CSRF tokens
 * back for mutating endpoints once the SPA has a token fetch step.
 */
@Configuration
public class SecurityConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/**")
        .csrf(csrf -> csrf.disable())
        .securityContext(sc -> sc.requireExplicitSave(false))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
            .requestMatchers("/api/onboarding/**").permitAll() // OnboardingController re-checks bootstrap mode
            .requestMatchers("/api/health").permitAll()
            .anyRequest().authenticated()
        )
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            .accessDeniedHandler((AccessDeniedHandler) (req, res, denied) ->
                res.setStatus(HttpStatus.FORBIDDEN.value()))
        )
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain staticFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());
    return http.build();
  }

  /**
   * Aurora hashes passwords with argon2id via argon2-jvm directly (see
   * {@link com.tomaytotomato.aurora.services.AuthService}). Spring Security's
   * built-in PasswordEncoder chain is intentionally not wired here.
   */
}
