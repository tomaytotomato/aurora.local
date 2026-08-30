import { describe, it, expect, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';

import TrustRootInstructions from './TrustRootInstructions.vue';
import TlsRootCard from './TlsRootCard.vue';
import OnboardingTls from '@/views/onboarding/OnboardingTls.vue';

/**
 * These instructions get followed literally by someone who is already
 * confused about why their browser is warning them. Two of them used to
 * end with the warning still there.
 */
describe('TrustRootInstructions', () => {
  const html = () => mount(TrustRootInstructions).html();

  it('tells Linux users about the browser store, not just the system one', () => {
    // update-ca-certificates fills the system store, which Chrome,
    // Chromium and Edge do not read — they keep their own NSS database.
    // The old text stopped at the first command and left the browser
    // warning with no hint why.
    expect(html()).toContain('update-ca-certificates');
    expect(html()).toContain('.pki/nssdb');
  });

  it('gives Android its own path instead of the iOS one', () => {
    // These shared an entry titled "iOS / Android" that described only
    // Settings → General → About → Certificate Trust, which does not
    // exist on Android.
    const h = html();
    expect(h).toContain('Encryption');
    expect(h).toContain('CA certificate');
    expect(h).toContain('Certificate Trust Settings');
  });

  it('covers Firefox, which trusts nothing the OS trusts', () => {
    expect(html()).toContain('Firefox');
  });

  it('is the single source for both surfaces that show it', async () => {
    // Two copies with "keep these in sync" comments on them had already
    // drifted: the wizard told Linux users to wait for a step-by-step in
    // Settings, and Settings then showed something else entirely.
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503 } as unknown as Response)));

    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/onboarding/tls', component: OnboardingTls }],
    });
    await router.push('/onboarding/tls');
    await router.isReady();

    const wizard = mount(OnboardingTls, { global: { plugins: [pinia, router] } });
    const settings = mount(TlsRootCard);
    await flushPromises();

    const marker = '[data-test="trust-root-instructions"]';
    expect(wizard.find(marker).exists()).toBe(true);
    expect(settings.find(marker).exists()).toBe(true);
    vi.unstubAllGlobals();
  });
});
