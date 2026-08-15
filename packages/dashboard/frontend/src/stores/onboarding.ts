import { defineStore } from 'pinia';
import { computed, ref, watch } from 'vue';
import {
  OnboardingApi,
  type DnsMode,
  type OnboardingDraft,
  type OnboardingEnv,
  type OnboardingStepId,
} from '@/api/onboarding';

// Wizard state model, v0.2:
//
// Sources of truth, in priority order:
//   1. Server  (GET /onboarding)  — durable, cross-device.
//   2. sessionStorage draft       — resilient to F5 mid-typing. Non-sensitive fields only.
//   3. In-memory (this store)     — transient; the generated admin password lives here
//                                    and NOWHERE else. Warn the user before refresh.
//
// currentStep is DERIVED from the URL by the OnboardingShell (see the
// syncFromRoute call there). We never sync it from the server response —
// the URL is the cursor. This is what stops the "sidebar → Welcome →
// Continue jumps to wherever you were before" drift bug: fetchStatus() used
// to overwrite currentStep, so the store and URL disagreed.

interface LocalAdmin {
  username: string;
  password: string;         // in-memory only. NEVER persisted.
  savedAcknowledged: boolean;
}

// 'packages' removed (2026-08-15): the interactive package picker step is
// gone. A first run installs the mandatory set (see
// MANDATORY_FIRST_RUN_PACKAGES in api/packages.ts) with nothing to choose;
// everything else is added afterwards from the Apps catalogue, which now
// has a real Install/Start/Disable/Uninstall control panel behind it. This
// list is the single source of truth for the step count shown throughout
// the wizard (see stepEyebrow below) — do not hardcode "Step N of M" text
// in an individual view.
export const STEPS: OnboardingStepId[] = [
  'welcome',
  'admin',
  'domain',
  'sso',
  'secrets',
  'dns',
  'tls',
  'review',
  'done',
];

export const STEP_LABELS: Record<OnboardingStepId, string> = {
  welcome: 'Welcome',
  admin: 'Admin account',
  domain: 'Hostname & domain',
  sso: 'Single sign-on',
  secrets: 'Secrets',
  dns: 'DNS story',
  tls: 'Trust the root CA',
  review: 'Review & install',
  done: 'Done',
};

/** Pure lookup: given a step, what's next? Null past the end. */
export function nextStepFrom(step: OnboardingStepId): OnboardingStepId | null {
  const i = STEPS.indexOf(step);
  return i >= 0 && i < STEPS.length - 1 ? STEPS[i + 1] : null;
}

/** Pure lookup: given a step, what's previous? Null before the start. */
export function prevStepFrom(step: OnboardingStepId): OnboardingStepId | null {
  const i = STEPS.indexOf(step);
  return i > 0 ? STEPS[i - 1] : null;
}

// sessionStorage keys for pre-submit form drafts. These survive F5 within
// the same tab and are cleared once the field has been PATCHed to the server.
const DRAFT_KEY = 'aurora.onboarding.draft';

interface Draft {
  domain?: string;
  dns_mode?: DnsMode;
}

function loadDraft(): Draft {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY);
    return raw ? (JSON.parse(raw) as Draft) : {};
  } catch {
    return {};
  }
}

function saveDraft(d: Draft): void {
  try {
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify(d));
  } catch {
    // storage disabled (private mode, quota) — degrade silently.
  }
}

function clearDraftKey(key: keyof Draft): void {
  const d = loadDraft();
  delete d[key];
  saveDraft(d);
}

export const useOnboardingStore = defineStore('onboarding', () => {
  // URL-driven cursor. OnboardingShell keeps this in sync with route.
  const currentStep = ref<OnboardingStepId>('welcome');

  // Track visited/passed steps for sidebar UI. Recomputed on hydrate from
  // the server's step so a resumed session shows accurate breadcrumbs.
  const completed = ref<Set<OnboardingStepId>>(new Set());

  // Admin credentials: in-memory only. Password is NEVER persisted.
  const admin = ref<LocalAdmin | null>(null);

  // Form fields. Hydrated from server, backed by sessionStorage draft.
  const draftInit = loadDraft();
  const domain = ref<string>(draftInit.domain ?? 'aurora.local');
  const dnsMode = ref<DnsMode | null>(draftInit.dns_mode ?? null);

  // Server-authoritative snapshot. Populated by hydrate(). Router guard
  // reads this to decide first-run redirects.
  const draft = ref<OnboardingDraft | null>(null);
  const env = ref<OnboardingEnv | null>(null);
  const hydrated = ref<boolean>(false);

  // Result of POST /onboarding/install — passed from Review to Done so the
  // Done screen can tell the operator what still needs to run on the host.
  const installResult = ref<import('@/api/onboarding').InstallResult | null>(null);

  const stepIndex = computed(() => STEPS.indexOf(currentStep.value));
  const progress = computed(() => (stepIndex.value / (STEPS.length - 1)) * 100);

  /**
   * "Step N of M" for the eyebrow at the top of every wizard view. Derived
   * from STEPS so the count agrees everywhere without five hardcoded
   * copies drifting out of sync when a step is added or removed.
   */
  const stepEyebrow = computed(() => `Step ${stepIndex.value + 1} of ${STEPS.length}`);

  /** Backwards-compat alias so the router guard can read the same shape. */
  const status = computed(() =>
    draft.value
      ? {
          complete: draft.value.complete,
          bootstrap_mode: draft.value.bootstrap_mode,
          step: draft.value.step,
        }
      : null,
  );

  // sessionStorage autosave: any change to domain/dns writes a draft so a
  // refresh mid-typing doesn't lose the input.
  watch(domain, (v) => saveDraft({ ...loadDraft(), domain: v }));
  watch(dnsMode, (v) => {
    const d = loadDraft();
    if (v == null) delete d.dns_mode;
    else d.dns_mode = v;
    saveDraft(d);
  });

  /**
   * One-shot hydration from the server. Populates domain / dns_mode /
   * admin.username / completed[] from server truth. `enabled_packages`
   * is read straight off `draft` by callers that need it (there is no
   * local mirror to keep in sync now that no picker writes to one) —
   * see draft.enabled_packages. Never touches currentStep — the URL
   * owns that.
   */
  async function hydrate(): Promise<OnboardingDraft> {
    const d = await OnboardingApi.get();
    draft.value = d;
    hydrated.value = true;

    // Field prefill: server wins if present, else keep sessionStorage draft.
    if (d.domain) domain.value = d.domain;
    if (d.dns_mode) dnsMode.value = d.dns_mode;

    // Admin: server knows the username but never the password. Preserve any
    // in-memory password (user just generated it and hasn't refreshed yet);
    // otherwise leave password empty so the Admin view can branch on
    // `bootstrap_mode` and render the read-only "already created" card.
    if (d.admin_username) {
      admin.value = {
        username: d.admin_username,
        password: admin.value?.password ?? '',
        savedAcknowledged: admin.value?.savedAcknowledged ?? true,
      };
    }

    // Reconstruct the completed set from the server's step cursor so the
    // sidebar shows accurate progress after a refresh.
    const i = STEPS.indexOf(d.step);
    if (i > 0) {
      completed.value = new Set(STEPS.slice(0, i));
    }

    return d;
  }

  async function fetchEnv(): Promise<OnboardingEnv> {
    env.value = await OnboardingApi.env();
    return env.value;
  }

  /** Sync currentStep + completed[] with the URL. Called by OnboardingShell. */
  function syncFromRoute(step: OnboardingStepId): void {
    if (!STEPS.includes(step)) return;
    currentStep.value = step;
  }

  function markCompleted(step: OnboardingStepId): void {
    completed.value.add(step);
  }

  function goTo(step: OnboardingStepId): void {
    currentStep.value = step;
  }

  /**
   * Advance one step from the *current URL step*. Marks the current step
   * completed. Callers still need to router.push() themselves so the URL
   * changes; that push then feeds back into syncFromRoute.
   */
  function next(): OnboardingStepId | null {
    const from = currentStep.value;
    const to = nextStepFrom(from);
    if (to != null) {
      markCompleted(from);
      currentStep.value = to;
      return to;
    }
    return null;
  }

  function back(): OnboardingStepId | null {
    const to = prevStepFrom(currentStep.value);
    if (to != null) {
      currentStep.value = to;
      return to;
    }
    return null;
  }

  /**
   * PATCH a subset of the draft to the server. On success, refreshes the
   * cached draft snapshot and clears the corresponding sessionStorage keys
   * (server now has authoritative truth for those fields).
   *
   * Callers that want a silent local-only advance (dev with no backend) can
   * catch and ignore — but the store still updates its local mirror before
   * throwing so the wizard flow keeps moving.
   *
   * `enabled_packages` has no dedicated local ref (there is no picker left
   * to keep in sync) — callers that need the current set read
   * `draft.value.enabled_packages`, which this refreshes from the server's
   * response below.
   */
  async function patchDraft(fields: {
    domain?: string;
    enabled_packages?: string[];
    dns_mode?: DnsMode;
    step?: OnboardingStepId;
  }): Promise<void> {
    // Optimistic local update — the sessionStorage watchers pick these up.
    if (fields.domain !== undefined) domain.value = fields.domain;
    if (fields.dns_mode !== undefined) dnsMode.value = fields.dns_mode;

    try {
      draft.value = await OnboardingApi.patch(fields);
      // Server has these values now — the sessionStorage draft was a
      // pre-submit safety net. Drop the keys we just persisted.
      if (fields.domain !== undefined) clearDraftKey('domain');
      if (fields.dns_mode !== undefined) clearDraftKey('dns_mode');
    } catch (e) {
      // Soft-fail so the wizard flow completes even if the backend is
      // temporarily unreachable. sessionStorage still holds the draft.
      // eslint-disable-next-line no-console
      console.warn('onboarding PATCH failed; keeping local draft', e);
    }
  }

  /** Called from OnboardingDone once the wizard commits. Wipes the draft. */
  function clearAllDrafts(): void {
    try {
      sessionStorage.removeItem(DRAFT_KEY);
    } catch { /* ignore */ }
  }

  /**
   * Reflect a successful (or already-complete) POST /onboarding/complete
   * locally. The router guard reads `status.complete` off `draft`, but
   * `draft` is only ever populated by `hydrate()`, which the guard calls
   * once per SPA lifetime. Without this, `draft.complete` stays stale
   * `false` for the rest of the session and navigating to '/' after a
   * successful launch would bounce the user straight back into the wizard.
   */
  function markOnboardingComplete(): void {
    if (draft.value) draft.value.complete = true;
  }

  return {
    // State
    currentStep,
    completed,
    admin,
    domain,
    dnsMode,
    draft,
    env,
    hydrated,
    installResult,
    // Computed
    stepIndex,
    progress,
    stepEyebrow,
    status,
    // Actions
    goTo,
    next,
    back,
    markCompleted,
    hydrate,
    fetchEnv,
    syncFromRoute,
    patchDraft,
    clearAllDrafts,
    markOnboardingComplete,
  };
});
