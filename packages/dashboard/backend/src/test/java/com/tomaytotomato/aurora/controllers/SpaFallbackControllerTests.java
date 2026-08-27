package com.tomaytotomato.aurora.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The SPA fallback catches any GET that Vue Router should own. Its regex
 * has to <em>not</em> catch requests for real static folders — otherwise
 * they get index.html returned as {@code text/html}, and no browser
 * renders {@code <img src="/icons/foo.svg">} as an image when the
 * response is HTML.
 *
 * Bruce hit this on 27 Aug 2026: {@code /icons/caddy.svg} returned
 * index.html because the negative lookahead did not name {@code icons/}
 * as an excluded prefix. The bug was latent from v0.1 — it was only
 * visible once a page rendered many bundled icons at once, which the
 * new Core service grid was the first to do. Pin the exclusion.
 */
class SpaFallbackControllerTests {

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new SpaFallbackController()).build();
  }

  @Test
  void root_is_handled_by_the_fallback() throws Exception {
    // Sanity: the fallback owns "/". In the full app it returns index.html;
    // in this standalone test the SPA bundle is not on the classpath, so
    // the 503 branch fires. Either way, the fallback owns the route -
    // that's what we care about here.
    mvc().perform(get("/"))
        .andExpect(mvcResult -> {
          int s = mvcResult.getResponse().getStatus();
          if (s != 200 && s != 503) {
            throw new AssertionError("expected 200 or 503, got " + s);
          }
        });
  }

  @Test
  void plain_route_is_handled_by_the_fallback() throws Exception {
    // Vue Router path - no extension, not a reserved prefix.
    mvc().perform(get("/apps/core"))
        .andExpect(mvcResult -> {
          int s = mvcResult.getResponse().getStatus();
          if (s != 200 && s != 503) {
            throw new AssertionError("expected 200 or 503, got " + s);
          }
        });
  }

  @Test
  void icons_path_is_not_swallowed_by_the_fallback() throws Exception {
    // The whole point of the fix: /icons/caddy.svg must not match
    // this controller. When no other handler is registered (the
    // standalone MockMvc setup does not include Spring Boot's
    // static-resource resolver) the fallback returning 404 here is
    // the correct behaviour — the classpath resolver would answer
    // this in the full app.
    mvc().perform(get("/icons/caddy.svg"))
        .andExpect(status().isNotFound());
  }

  @Test
  void assets_path_is_not_swallowed_by_the_fallback() throws Exception {
    // Same story for Vite's asset bundle path. Regression pin: any
    // future refactor that "simplifies" the regex must keep these
    // out of the SPA fallback's mouth.
    mvc().perform(get("/assets/index.js"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aurora_photos_path_is_not_swallowed_by_the_fallback() throws Exception {
    // The hero photos live under /aurora/*.jpg (already excluded).
    // Pin it, so nobody re-simplifies the regex and re-breaks this.
    mvc().perform(get("/aurora/1.jpg"))
        .andExpect(status().isNotFound());
  }

  @Test
  void api_and_actuator_are_not_swallowed_by_the_fallback() throws Exception {
    mvc().perform(get("/api/users")).andExpect(status().isNotFound());
    mvc().perform(get("/actuator/health")).andExpect(status().isNotFound());
  }
}
