package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.MarketplaceApp;
import com.tomaytotomato.aurora.domain.MarketplaceImage;
import com.tomaytotomato.aurora.domain.MarketplaceStatus;
import com.tomaytotomato.aurora.services.CurrentUserService;
import com.tomaytotomato.aurora.services.MarketplaceCatalogService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MarketplaceController} shape. Standalone MockMvc with a mocked
 * catalogue service — matches the pattern in MetricsControllerTests; the
 * trust rules themselves are pinned in
 * {@code MarketplaceCatalogServiceTests}.
 */
class MarketplaceControllerTests {

  private final MarketplaceCatalogService catalogue = Mockito.mock(MarketplaceCatalogService.class);
  private final CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new MarketplaceController(catalogue, currentUser)).build();

  private static MarketplaceApp app(String slug) {
    return new MarketplaceApp(slug, slug + " title", "desc", "media", null,
        null, null, null, null, null, null, null,
        List.of(new MarketplaceImage("img:tag", null)), true,
        null, null, null, null);
  }

  private static MarketplaceStatus mStatus(boolean update) {
    return new MarketplaceStatus(true, "v1", "2026-08-28T00:00:00Z", 3, true, "cache",
        "2026-08-28T00:00:00Z", null, update,
        update ? "v2" : null, update ? "2026-08-29T00:00:00Z" : null,
        update ? 4 : null, update ? 1 : null);
  }

  @Test
  void lists_catalogue_summaries() throws Exception {
    when(catalogue.apps()).thenReturn(List.of(app("jellyfin"), app("photos")));
    mvc.perform(get("/api/marketplace"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].slug").value("jellyfin"))
        .andExpect(jsonPath("$[1].slug").value("photos"));
  }

  @Test
  void returns_status_with_update_flag() throws Exception {
    when(catalogue.status()).thenReturn(mStatus(true));
    mvc.perform(get("/api/marketplace/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updateAvailable").value(true))
        .andExpect(jsonPath("$.availableVersion").value("v2"))
        .andExpect(jsonPath("$.availableNewAppCount").value(1));
  }

  @Test
  void returns_one_app_detail() throws Exception {
    when(catalogue.app("jellyfin")).thenReturn(Optional.of(app("jellyfin")));
    mvc.perform(get("/api/marketplace/jellyfin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slug").value("jellyfin"));
  }

  @Test
  void unknown_app_is_404() throws Exception {
    when(catalogue.app("nope")).thenReturn(Optional.empty());
    mvc.perform(get("/api/marketplace/nope")).andExpect(status().isNotFound());
  }

  @Test
  void refresh_returns_status() throws Exception {
    when(catalogue.refresh()).thenReturn(mStatus(false));
    mvc.perform(post("/api/marketplace/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeVersion").value("v1"));
  }

  @Test
  void accept_promotes_and_returns_status() throws Exception {
    when(currentUser.currentUserId()).thenReturn(Optional.of(7L));
    when(catalogue.accept(any())).thenReturn(mStatus(false));
    mvc.perform(post("/api/marketplace/accept"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updateAvailable").value(false));
  }

  @Test
  void accept_with_nothing_pending_is_409() throws Exception {
    when(currentUser.currentUserId()).thenReturn(Optional.empty());
    when(catalogue.accept(any())).thenThrow(new IllegalStateException("nothing to accept"));
    mvc.perform(post("/api/marketplace/accept")).andExpect(status().isConflict());
  }
}
