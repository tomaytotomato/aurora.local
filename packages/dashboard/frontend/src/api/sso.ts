// SSO enrollment + notifier surface. Named `sso` to line up with the
// backend controller ({@code SsoController}, mounted at {@code /api/sso}).
//
// Two callers today:
//   • Settings > Account       — reads status() for the "you have a passkey"
//                                pill and re-surfaces a pending enrollment
//                                link. (Follows M1/M3 in the auth plan.)
//   • /apps/core Authelia card — reads notifications() for the last N
//                                notifier entries (OTPs + revoke links)
//                                so the operator does not have to
//                                docker-exec their way into a container
//                                to read a code Authelia just sent them.
//
// Both live under the default authenticated() rule in SecurityConfig,
// because any pending enrollment link or OTP is a bearer capability:
// whoever opens it first binds an authenticator to the account.

import { http } from './client';

/** Second-factor enrollment snapshot. Mirrors {@code SsoEnrollmentService.EnrollmentStatus}. */
export interface SsoStatus {
  enrolled: boolean;
  factorCount: number;
  passkeyCount: number;
  /** Most recent registration URL, or null when none is pending. */
  pendingUrl: string | null;
  pendingAt: string | null;
  autheliaUp: boolean;
}

/**
 * One notification Authelia emitted to its filesystem notifier.
 *
 * Mirrors {@code SsoEnrollmentService.Notification}. Kept faithful to
 * the wire shape so a future backend addition (e.g. a categorised
 * `kind` field) does not require peeling optional fields off in the UI.
 */
export interface SsoNotification {
  /** Verbatim Authelia timestamp; the UI formats client-side. */
  date: string;
  /** {Name email} bracket, verbatim. */
  recipient: string;
  subject: string;
  /** One-time code when the entry contains one, else null. */
  otp: string | null;
  /** Every URL in the body, document order. */
  urls: string[];
  /** Message body with Date/Recipient/Subject headers stripped. */
  body: string;
}

export const SsoApi = {
  async status(): Promise<SsoStatus> {
    const { data } = await http.get<SsoStatus>('/sso/status');
    return data;
  },

  /**
   * Recent notifications, newest first.
   *
   * @param limit backend caps this at 20; the Authelia panel uses 5.
   */
  async notifications(limit: number = 5): Promise<SsoNotification[]> {
    const { data } = await http.get<SsoNotification[]>('/sso/notifications', {
      params: { limit },
    });
    return data;
  },
};
