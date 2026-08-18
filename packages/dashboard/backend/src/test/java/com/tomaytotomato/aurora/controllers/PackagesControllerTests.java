package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.Package;
import com.tomaytotomato.aurora.domain.SsoBlock;
import com.tomaytotomato.aurora.services.PackagesService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/packages/{name}} must return the flat {@code PackageDetail}
 * shape openapi.yaml documents, not the old {@code {package, env_example}}
 * wrapper.
 *
 * <p>The wrapper was the root cause of a real-box bug: the dashboard's app
 * detail page (PackageDetail.vue) reads {@code detail.name},
 * {@code detail.enabled} and {@code detail.running} straight off the
 * response. Wrapped inside {@code package.*}, every one of those came back
 * {@code undefined} — so a running, enabled core package like identity
 * (Authelia) still showed the DISABLED badge, "Not currently running", and
 * an "Add app" button that makes no sense for a package that can't be
 * added or removed at all.
 */
class PackagesControllerTests {

  private static Package identity(boolean enabled, boolean running) {
    return new Package(
        "identity",
        "Identity (Authelia SSO + 2FA)",
        "Authelia provides single sign-on and two-factor authentication.",
        "identity",
        List.of("core"),
        List.of(),
        Map.of(),
        List.of(),
        Map.of(),
        List.of(),
        null,
        enabled,
        running,
        SsoBlock.DISABLED
    );
  }

  private static MockMvc mvc(PackagesService packages) {
    return MockMvcBuilders.standaloneSetup(
        new PackagesController(packages, Mockito.mock(com.tomaytotomato.aurora.services.PackageLifecycleService.class))
    ).build();
  }

  @Test
  void detail_is_flat_not_wrapped_in_package_key() throws Exception {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.detail("identity")).thenReturn(Optional.of(identity(true, true)));

    mvc(packages).perform(get("/api/packages/identity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.package").doesNotExist())
        .andExpect(jsonPath("$.name").value("identity"))
        .andExpect(jsonPath("$.category").value("identity"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.running").value(true));
  }

  @Test
  void running_enabled_core_package_reports_true_on_both_flags() throws Exception {
    // The exact real-box scenario from the bug report: identity installed
    // and Authelia genuinely healthy — enabled and running must both come
    // back true at the top level so the frontend's isCorePackage() lookup
    // (keyed on `name`) and its enabled/running badge both see real data.
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.detail("identity")).thenReturn(Optional.of(identity(true, true)));

    mvc(packages).perform(get("/api/packages/identity"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("identity"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.running").value(true));
  }

  @Test
  void unknown_package_is_404() throws Exception {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.detail("nope")).thenReturn(Optional.empty());

    mvc(packages).perform(get("/api/packages/nope"))
        .andExpect(status().isNotFound());
  }

  @Test
  void list_stays_a_flat_array() throws Exception {
    PackagesService packages = Mockito.mock(PackagesService.class);
    Mockito.when(packages.list()).thenReturn(List.of(identity(true, true)));

    mvc(packages).perform(get("/api/packages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("identity"));
  }
}
