package com.tomaytotomato.aurora.controllers;

import com.tomaytotomato.aurora.domain.AdminUser;
import com.tomaytotomato.aurora.persistence.AdminUserRepo;
import com.tomaytotomato.aurora.services.AuthService;
import com.tomaytotomato.aurora.support.AuroraIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/users} against a real SQLite database and the real security
 * chain.
 *
 * <p>The interesting behaviour here is not CRUD. It is the three ways a
 * person can lock themselves out of their own box, and whether the API
 * stops them.
 */
@WithMockUser(username = "owner")
class UsersControllerIntegrationTest extends AuroraIntegrationTest {

  private static final String GOOD_PASSWORD = "correct-horse-battery";

  @Autowired
  AdminUserRepo users;

  @Autowired
  AuthService auth;

  @Autowired
  JdbcTemplate jdbc;

  private long ownerId;

  @BeforeEach
  void seedPeople() {
    jdbc.update("DELETE FROM admin_user");
    // The box's owner: signed in for every test via @WithMockUser.
    ownerId = users.create("owner", auth.hash(GOOD_PASSWORD.toCharArray()), "UTC",
        AdminUser.ROLE_ADMIN);
  }

  private long addUser(String username, String role) {
    return users.create(username, auth.hash(GOOD_PASSWORD.toCharArray()), "UTC", role);
  }

  private static String newUserJson(String username, String role, String password) {
    return """
        {"username":"%s","role":"%s","password":"%s"}
        """.formatted(username, role, password);
  }

  // ------------------------------------------------------------------

  @Nested
  @DisplayName("listing")
  class Listing {

    @Test
    void returns_everyone_with_the_fields_the_page_renders() throws Exception {
      addUser("housemate", AdminUser.ROLE_VIEWER);

      mvc.perform(get("/api/users"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].username").value("owner"))
          .andExpect(jsonPath("$[0].role").value("admin"))
          .andExpect(jsonPath("$[0].id").isString())
          .andExpect(jsonPath("$[1].username").value("housemate"))
          .andExpect(jsonPath("$[1].role").value("viewer"));
    }

    @Test
    void reports_never_signed_in_as_null_rather_than_a_made_up_date() throws Exception {
      mvc.perform(get("/api/users"))
          .andExpect(jsonPath("$[0].lastLoginAt").doesNotExist())
          .andExpect(jsonPath("$[0].passkeyEnrolled").value(false));
    }

    @Test
    void records_a_sign_in_so_the_page_stops_saying_never() throws Exception {
      assertThat(users.findById(ownerId).orElseThrow().lastLoginAt()).isNull();

      auth.authenticate("owner", GOOD_PASSWORD);

      assertThat(users.findById(ownerId).orElseThrow().lastLoginAt()).isNotNull();
      mvc.perform(get("/api/users"))
          .andExpect(jsonPath("$[0].lastLoginAt").isNotEmpty());
    }

    @Test
    void a_failed_sign_in_does_not_count_as_being_here() throws Exception {
      auth.authenticate("owner", "not-the-password");
      assertThat(users.findById(ownerId).orElseThrow().lastLoginAt()).isNull();
    }
  }

  @Nested
  @DisplayName("creating")
  class Creating {

    @Test
    void adds_a_person_and_returns_them() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "operator", GOOD_PASSWORD)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.username").value("housemate"))
          .andExpect(jsonPath("$.role").value("operator"))
          .andExpect(jsonPath("$.lastLoginAt").doesNotExist());

      assertThat(users.findByUsername("housemate")).isPresent();
    }

    @Test
    void stores_a_hash_rather_than_the_password() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "viewer", GOOD_PASSWORD)))
          .andExpect(status().isCreated());

      String stored = users.findByUsername("housemate").orElseThrow().passwordHash();
      assertThat(stored).doesNotContain(GOOD_PASSWORD);
      assertThat(auth.verify(stored, GOOD_PASSWORD.toCharArray())).isTrue();
    }

    @Test
    void never_returns_the_hash_over_the_wire() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "viewer", GOOD_PASSWORD)))
          .andExpect(jsonPath("$.passwordHash").doesNotExist());

      mvc.perform(get("/api/users"))
          .andExpect(jsonPath("$[*].passwordHash").doesNotExist());
    }

    @Test
    void refuses_a_username_that_is_taken() throws Exception {
      addUser("housemate", AdminUser.ROLE_VIEWER);

      // 409, not 400: the request was fine, the world already contains
      // this person. The frontend renders it inline on the field.
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "viewer", GOOD_PASSWORD)))
          .andExpect(status().isConflict());
    }

    @Test
    void refuses_a_password_the_security_rules_would_immediately_complain_about() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "viewer", "short")))
          .andExpect(status().isBadRequest());

      assertThat(users.findByUsername("housemate")).isEmpty();
    }

    @Test
    void refuses_a_username_that_would_not_survive_a_login_box() throws Exception {
      for (String bad : new String[] {"Uppercase", "has space", "x", "a".repeat(40), "sym!bol"}) {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(newUserJson(bad, "viewer", GOOD_PASSWORD)))
            .andExpect(status().isBadRequest());
      }
    }

    @Test
    void refuses_a_role_that_does_not_exist() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("housemate", "superuser", GOOD_PASSWORD)))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("the ways you could lock yourself out")
  class LockoutGuards {

    @Test
    void you_cannot_remove_your_own_account() throws Exception {
      mvc.perform(delete("/api/users/{id}", ownerId))
          .andExpect(status().isConflict());

      assertThat(users.findById(ownerId)).isPresent();
    }

    @Test
    void you_cannot_remove_the_last_admin() throws Exception {
      // Owner is signed in, so removing themselves is already blocked
      // above. This is the other half: a second admin removing the first
      // while they are the only two, then the guard biting when one is
      // left.
      long other = addUser("second-admin", AdminUser.ROLE_ADMIN);
      mvc.perform(delete("/api/users/{id}", other)).andExpect(status().isNoContent());

      // Only the owner remains, and they are the only admin.
      assertThat(users.countAdmins()).isEqualTo(1);
      mvc.perform(delete("/api/users/{id}", ownerId)).andExpect(status().isConflict());
    }

    @Test
    void you_cannot_demote_the_last_admin() throws Exception {
      // The scenario this exists for: an owner demotes themselves to
      // operator to see what a housemate sees, and locks every person out
      // of the box with no recovery short of editing SQLite by hand.
      mvc.perform(patch("/api/users/{id}", ownerId).contentType(MediaType.APPLICATION_JSON)
              .content("{\"role\":\"operator\"}"))
          .andExpect(status().isConflict());

      assertThat(users.findById(ownerId).orElseThrow().role()).isEqualTo("admin");
    }

    @Test
    void demoting_an_admin_is_fine_when_another_one_remains() throws Exception {
      long other = addUser("second-admin", AdminUser.ROLE_ADMIN);

      mvc.perform(patch("/api/users/{id}", other).contentType(MediaType.APPLICATION_JSON)
              .content("{\"role\":\"viewer\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.role").value("viewer"));
    }

    @Test
    void promoting_someone_is_always_allowed() throws Exception {
      long other = addUser("housemate", AdminUser.ROLE_VIEWER);

      mvc.perform(patch("/api/users/{id}", other).contentType(MediaType.APPLICATION_JSON)
              .content("{\"role\":\"admin\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.role").value("admin"));
      assertThat(users.countAdmins()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("who is allowed to manage people")
  @WithMockUser(username = "housemate")
  class Permissions {

    @BeforeEach
    void signInAsSomeoneElse() {
      addUser("housemate", AdminUser.ROLE_OPERATOR);
    }

    @Test
    void an_operator_can_see_who_has_access() throws Exception {
      // Not privileged information on a box you already have an account
      // on, and hiding it would make the page useless for an operator.
      mvc.perform(get("/api/users")).andExpect(status().isOk());
    }

    @Test
    void an_operator_cannot_add_anyone() throws Exception {
      mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
              .content(newUserJson("someone", "viewer", GOOD_PASSWORD)))
          .andExpect(status().isForbidden());
    }

    @Test
    void an_operator_cannot_change_a_role() throws Exception {
      mvc.perform(patch("/api/users/{id}", ownerId).contentType(MediaType.APPLICATION_JSON)
              .content("{\"role\":\"viewer\"}"))
          .andExpect(status().isForbidden());
    }

    @Test
    void an_operator_cannot_remove_anyone() throws Exception {
      mvc.perform(delete("/api/users/{id}", ownerId))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("missing people")
  class Missing {

    @Test
    void patching_someone_who_does_not_exist_is_a_404() throws Exception {
      mvc.perform(patch("/api/users/{id}", 9999).contentType(MediaType.APPLICATION_JSON)
              .content("{\"role\":\"viewer\"}"))
          .andExpect(status().isNotFound());
    }

    @Test
    void deleting_someone_who_does_not_exist_is_a_404() throws Exception {
      mvc.perform(delete("/api/users/{id}", 9999))
          .andExpect(status().isNotFound());
    }
  }
}
