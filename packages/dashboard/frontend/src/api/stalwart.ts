// Stalwart-specific dashboard surface. One endpoint today: reveal the
// recovery-admin credential for the mail-admin console. Grouped as its
// own module rather than merged into `core-services.ts` because that
// file is a static catalogue (labels, icons, open URLs) with no I/O,
// and mixing HTTP calls into it would drag Pinia + axios into every
// import site that just wants the CORE_SERVICES list.

import { http } from './client';

/**
 * Where the returned secret came from. Mirrors
 * StalwartAdminService.Source on the backend.
 */
export type StalwartAdminSecretSource = 'ENV' | 'DEFAULT';

/**
 * The Stalwart recovery-admin credential. Shape matches
 * StalwartAdminService.AdminCredential on the backend, kept faithful so
 * a future field addition (e.g. `rotatedAt`) does not require the
 * frontend to peel optional shapes.
 */
export interface StalwartAdminCredential {
  /** Fixed user side of the credential ("admin" today). */
  username: string;
  /** Plaintext password Stalwart is running with. */
  secret: string;
  /**
   * ENV = a real value in packages/core/.env.
   * DEFAULT = the compose fallback (aurora-change-me) because .env is
   *           missing/blank. UI shows a rotate-me warning in that case.
   */
  source: StalwartAdminSecretSource;
}

export const StalwartApi = {
  /**
   * Fetch the current recovery-admin credential. Admin-only on the
   * backend; a non-admin caller gets a 403 which the caller surfaces
   * inline (this is a same-page reveal, not a global toast).
   */
  async adminSecret(): Promise<StalwartAdminCredential> {
    const { data } = await http.get<StalwartAdminCredential>(
      '/services/stalwart/admin-secret',
      { toast: false },
    );
    return data;
  },
};
