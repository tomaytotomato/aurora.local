package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.services.AdguardSessionBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdguardSessionController}. Two shapes worth pinning:
 *
 * <ol>
 *   <li>Happy path: the broker returns a cookie, controller emits
 *       {@code 204 No Content} + {@code X-Adguard-Cookie: agh_session=...}.
 *       Caddy's forward_auth then copies that header onto the outgoing
 *       request. Getting the header name wrong or the status wrong
 *       silently drops the cookie and AdGuard falls back to its own
 *       login screen, which is the exact regression Option 2 exists to
 *       prevent.</li>
 *   <li>Broker returns empty (login failed, container not restarted):
 *       the controller emits 503. Caddy's forward_auth treats 5xx as
 *       "deny", so the operator sees a proxy error page rather than
 *       falling through to AdGuard's native login.</li>
 * </ol>
 */
class AdguardSessionControllerTests {

  private AdguardSessionBroker broker;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    broker = Mockito.mock(AdguardSessionBroker.class);
    mvc = MockMvcBuilders.standaloneSetup(new AdguardSessionController(broker)).build();
  }

  @Test
  void happy_path_returns_204_and_the_cookie_in_the_X_Adguard_Cookie_header() throws Exception {
    Mockito.when(broker.currentSessionCookie())
        .thenReturn(Optional.of("agh_session=a7812f566c693e60c4d5b9482fa73014"));

    mvc.perform(get("/api/apps/adguard/session-cookie"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("X-Adguard-Cookie",
            "agh_session=a7812f566c693e60c4d5b9482fa73014"))
        // Never cache: if Aurora refreshes the cookie because the old
        // one expired, Caddy has to see the new value immediately.
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void broker_empty_returns_503_so_caddy_denies_rather_than_falling_through() throws Exception {
    Mockito.when(broker.currentSessionCookie()).thenReturn(Optional.empty());

    // 5xx tells Caddy's forward_auth to deny the outer request.
    // If we returned 204 with an empty header instead, Caddy would
    // pass the request through to AdGuard with no Cookie header, and
    // AdGuard's native login screen would appear — the exact
    // behaviour Option 2 was built to remove.
    mvc.perform(get("/api/apps/adguard/session-cookie"))
        .andExpect(status().isServiceUnavailable());
  }
}
