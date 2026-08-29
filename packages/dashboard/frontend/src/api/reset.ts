// The in-dashboard "start over" endpoint (A8, closing A6).
//
// This is the button-shaped equivalent of `bash scripts/reset.sh`: the
// backend spawns a detached helper container that, a few seconds after
// this call returns, tears down every aurora container, deletes `data/`,
// and clears the per-package `.env` + `.state.yml`. The repo, the OS,
// docker itself and ufw are all left alone.
//
// Confirmation is compulsory. The body has to carry the literal word
// `RESET` — anything else is a 400 with a plain-English message the UI
// shows verbatim. The UI makes the user type it so a misclick cannot
// wipe the box.

import { http } from './client';

export interface ResetAccepted {
  /** Docker id of the helper container. Not shown to the operator; useful in bug reports. */
  helperId: string;
}

export const ResetApi = {
  /**
   * Kick off the wipe. Resolves as soon as the helper container has been
   * launched; the actual destruction happens seconds later, at which
   * point the dashboard itself vanishes. Callers should show a
   * "disconnecting…" screen before awaiting this.
   */
  async start(confirm: string): Promise<ResetAccepted> {
    const { data } = await http.post<ResetAccepted>('/reset', { confirm });
    return data;
  },
};
