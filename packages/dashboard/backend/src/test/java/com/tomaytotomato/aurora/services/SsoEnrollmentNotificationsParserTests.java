package com.tomaytotomato.aurora.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Parser tests for {@link SsoEnrollmentService#parseNotifications(String, int)}.
 *
 * <p>Every fixture here is a verbatim snippet Authelia's filesystem
 * notifier is known to produce; the parser exists specifically because
 * the wording has drifted across Authelia releases and we do not want a
 * template change to silently break the Authelia panel on {@code /apps/core}.
 */
class SsoEnrollmentNotificationsParserTests {

  /** A password-reset one-time code, verbatim from Authelia v4.39. */
  private static final String ONE_TIME_CODE_ENTRY = String.join("\n",
      "Date: 2026-08-27 14:04:47.408006237 +0100 BST m=+8934.592675333",
      "Recipient: {Bruce bruce@aurora.local}",
      "Subject: Confirm your identity",
      "A ONE-TIME CODE HAS BEEN GENERATED TO COMPLETE A REQUESTED ACTION",
      "",
      "Hi Bruce,",
      "",
      "This notification has been sent to you in order to verify your identity",
      "to change security details for your account at aurora.local.",
      "",
      "----------------------------------------",
      "",
      "PHV9ZVAV",
      "",
      "----------------------------------------",
      "",
      "To revoke the code, click the link below:",
      "",
      "https://auth.aurora.local/revoke/one-time-code?id=hnGPV65qSIKkbKJpB_aGdg",
      "",
      "----------------------------------------",
      "",
      "Powered by Authelia https://www.authelia.com",
      "",
      "");

  /** A WebAuthn enrollment link \u2014 no OTP, one actionable URL. */
  private static final String ENROLLMENT_ENTRY = String.join("\n",
      "Date: 2026-08-28 09:12:03.001 +0100 BST m=+9200.000",
      "Recipient: {Bruce bruce@aurora.local}",
      "Subject: Register your device",
      "",
      "Click the link below to complete registration:",
      "",
      "https://auth.aurora.local/webauthn/register?token=abc.",
      "",
      "");

  @Nested
  class Structure {
    @Test
    void empty_input_yields_empty_list() {
      assertThat(SsoEnrollmentService.parseNotifications("", 5)).isEmpty();
    }

    @Test
    void non_date_headed_junk_before_first_entry_is_ignored() {
      // Not a real-world case, but a defensive one: something writes a
      // banner into the file before any real entry, and we should not
      // pretend that banner is a notification.
      String junk = "some warning banner\n\n" + ONE_TIME_CODE_ENTRY;
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(junk, 5);
      assertThat(out).hasSize(1);
      assertThat(out.get(0).subject()).isEqualTo("Confirm your identity");
    }

    @Test
    void multiple_entries_come_back_newest_first() {
      // Simulate the append log: OTP first, then the enrollment link.
      // Newest-first means the enrollment entry lands at index 0.
      String log = ONE_TIME_CODE_ENTRY + "\n" + ENROLLMENT_ENTRY;
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(log, 5);
      assertThat(out).hasSize(2);
      assertThat(out.get(0).subject()).isEqualTo("Register your device");
      assertThat(out.get(1).subject()).isEqualTo("Confirm your identity");
    }

    @Test
    void limit_clamps_to_the_newest_n() {
      String log = ONE_TIME_CODE_ENTRY + "\n" + ENROLLMENT_ENTRY;
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(log, 1);
      assertThat(out).hasSize(1);
      assertThat(out.get(0).subject()).isEqualTo("Register your device");
    }

    @Test
    void limit_of_zero_yields_empty_list() {
      assertThat(SsoEnrollmentService.parseNotifications(ONE_TIME_CODE_ENTRY, 0))
          .isEmpty();
    }
  }

  @Nested
  class OneTimeCode {
    @Test
    void extracts_the_lone_uppercase_token_between_fences() {
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ONE_TIME_CODE_ENTRY, 5);
      assertThat(out).singleElement()
          .satisfies(n -> assertThat(n.otp()).isEqualTo("PHV9ZVAV"));
    }

    @Test
    void extracts_the_revoke_link_and_strips_trailing_prose_punctuation() {
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ONE_TIME_CODE_ENTRY, 5);
      assertThat(out.get(0).urls())
          .contains("https://auth.aurora.local/revoke/one-time-code?id=hnGPV65qSIKkbKJpB_aGdg");
    }

    @Test
    void preserves_recipient_bracket_verbatim() {
      // A future Authelia release could add fields to the recipient
      // struct; keeping it verbatim means we still show something
      // useful in that case instead of dropping a field we did not
      // know to parse.
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ONE_TIME_CODE_ENTRY, 5);
      assertThat(out.get(0).recipient()).isEqualTo("{Bruce bruce@aurora.local}");
    }

    @Test
    void body_strips_date_recipient_subject_headers() {
      // The raw-details toggle in the UI must not repeat the header
      // fields the structured view already shows.
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ONE_TIME_CODE_ENTRY, 5);
      String body = out.get(0).body();
      assertThat(body).doesNotContain("Date: ");
      assertThat(body).doesNotContain("Recipient: ");
      assertThat(body).doesNotContain("Subject: ");
      assertThat(body).contains("Hi Bruce,");
    }
  }

  @Nested
  class EnrollmentLink {
    @Test
    void no_otp_when_the_entry_is_a_bare_link() {
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ENROLLMENT_ENTRY, 5);
      assertThat(out).singleElement()
          .satisfies(n -> assertThat(n.otp()).isNull());
    }

    @Test
    void trailing_full_stop_is_not_part_of_the_url() {
      // The fixture ends the URL with "?token=abc." so this asserts the
      // same behaviour pendingRegistration() promises for its last-URL
      // scan: prose wrapping does not steal characters into the link.
      List<SsoEnrollmentService.Notification> out =
          SsoEnrollmentService.parseNotifications(ENROLLMENT_ENTRY, 5);
      assertThat(out.get(0).urls())
          .containsExactly("https://auth.aurora.local/webauthn/register?token=abc");
    }
  }
}
