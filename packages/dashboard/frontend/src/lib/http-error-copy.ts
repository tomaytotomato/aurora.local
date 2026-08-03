// iter-37 (v0.3 followup): shared axios-status \u2192 user-facing copy
// helper. Every view was carrying its own copy of the same shape:
//
//   status 400 \u2192 "\u2026 malformed \u2026"
//   status 401/403 \u2192 "Sign in again to \u2026"
//   status 404 \u2192 "\u2026 not on this box any more"
//   otherwise \u2192 "Aurora couldn't \u2026"
//
// Kept as a pure function (no imports of Vue) so vitest can pin the
// branches without mounting a component. Callers pass the axios error
// unknown-cast and a small context object describing the domain
// noun / verb so the copy stays human.

/**
 * Narrow shape describing the noun + action for the failing call.
 * The helper picks copy that reads naturally when concatenated
 * into a template.
 */
export interface ErrorCopyContext {
  /** e.g. 'container logs', 'metrics', 'security scan'. */
  subject: string;
  /** e.g. 'load', 'update', 'restore' — used in the generic branch. */
  action: string;
  /** Override for 400. Defaults to a subject-agnostic 'malformed request' line. */
  badRequest?: string;
  /** Override for 404. Defaults to a subject-noun-based 'not found' line. */
  notFound?: string;
}

/**
 * Pull the numeric HTTP status out of an unknown axios rejection
 * without triggering the runtime tax of a full instanceof check.
 * Returns undefined when the shape doesn't match.
 */
export function httpStatusFromError(err: unknown): number | undefined {
  return (err as { response?: { status?: number } } | null | undefined)?.response?.status;
}

/**
 * Map an axios error to a human-friendly sentence. Contract:
 *
 *   \u2022 401 / 403 always yield a session-expired copy.
 *   \u2022 400 uses ctx.badRequest when provided, else a generic
 *     'malformed request' fallback.
 *   \u2022 404 uses ctx.notFound when provided, else 'That {subject}
 *     is not on this box any more.'
 *   \u2022 any other status (including no status) yields
 *     "Aurora couldn't {action} the {subject} just now."
 *
 * No trailing period on the returned string \u2014 callers can append
 * additional context if desired.
 */
export function humanCopyForStatus(status: number | undefined, ctx: ErrorCopyContext): string {
  if (status === 401 || status === 403) {
    return `You need to sign in again to ${ctx.action} ${ctx.subject}.`;
  }
  if (status === 400) {
    return ctx.badRequest ?? `Aurora couldn't understand that request.`;
  }
  if (status === 404) {
    return ctx.notFound ?? `That ${ctx.subject} is not on this box any more.`;
  }
  return `Aurora couldn't ${ctx.action} ${ctx.subject} just now.`;
}

/** Convenience: chain the two helpers. */
export function humanCopyForError(err: unknown, ctx: ErrorCopyContext): string {
  return humanCopyForStatus(httpStatusFromError(err), ctx);
}
