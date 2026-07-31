import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { OnboardingApi, type OnboardingStepId, type OnboardingStatus, type OnboardingEnv } from '@/api/onboarding';

// Local wizard state. The server is source of truth; this mirrors for UX snap.
// Keeps the middle steps navigable without a network round-trip.

interface LocalAdmin {
  username: string;
  password: string;
  savedAcknowledged: boolean;
}

export const STEPS: OnboardingStepId[] = [
  'welcome',
  'admin',
  'domain',
  'packages',
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
  packages: 'Packages',
  secrets: 'Secrets',
  dns: 'DNS story',
  tls: 'Trust the root CA',
  review: 'Review & install',
  done: 'Done',
};

export const useOnboardingStore = defineStore('onboarding', () => {
  const currentStep = ref<OnboardingStepId>('welcome');
  const completed = ref<Set<OnboardingStepId>>(new Set());
  const admin = ref<LocalAdmin | null>(null);
  const domain = ref<string>('aurora.local');
  const selectedPackages = ref<string[]>(['core', 'privacy', 'storage']);
  const dnsMode = ref<'adguard' | 'router' | 'mdns' | null>(null);

  // Server-authoritative status. Populated once via fetchStatus(); router
  // guard reads this to decide first-run redirects. Kept small so it can
  // be re-fetched cheaply on any transition.
  const status = ref<OnboardingStatus | null>(null);
  const env = ref<OnboardingEnv | null>(null);

  const stepIndex = computed(() => STEPS.indexOf(currentStep.value));
  const progress = computed(() => (stepIndex.value / (STEPS.length - 1)) * 100);

  async function fetchStatus(): Promise<OnboardingStatus> {
    status.value = await OnboardingApi.status();
    if (status.value.step && STEPS.includes(status.value.step)) {
      currentStep.value = status.value.step;
    }
    return status.value;
  }

  async function fetchEnv(): Promise<OnboardingEnv> {
    env.value = await OnboardingApi.env();
    return env.value;
  }

  function markCompleted(step: OnboardingStepId): void {
    completed.value.add(step);
  }

  function goTo(step: OnboardingStepId): void {
    currentStep.value = step;
  }

  function next(): OnboardingStepId | null {
    markCompleted(currentStep.value);
    const i = stepIndex.value;
    if (i < STEPS.length - 1) {
      currentStep.value = STEPS[i + 1];
      return currentStep.value;
    }
    return null;
  }

  function back(): OnboardingStepId | null {
    const i = stepIndex.value;
    if (i > 0) {
      currentStep.value = STEPS[i - 1];
      return currentStep.value;
    }
    return null;
  }

  function selectPreset(preset: 'safe' | 'media' | 'cloud'): void {
    if (preset === 'safe') {
      selectedPackages.value = ['core', 'privacy', 'storage'];
    } else if (preset === 'media') {
      selectedPackages.value = ['core', 'privacy', 'media', 'storage'];
    } else {
      selectedPackages.value = ['core', 'privacy', 'storage', 'photos', 'documents', 'notes', 'backup'];
    }
  }

  function togglePackage(name: string): void {
    const s = new Set(selectedPackages.value);
    if (s.has(name)) s.delete(name);
    else s.add(name);
    selectedPackages.value = [...s];
  }

  return {
    currentStep,
    completed,
    admin,
    domain,
    selectedPackages,
    dnsMode,
    status,
    env,
    stepIndex,
    progress,
    goTo,
    next,
    back,
    markCompleted,
    fetchStatus,
    fetchEnv,
    selectPreset,
    togglePackage,
  };
});
