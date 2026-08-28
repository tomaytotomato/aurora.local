import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { webcrypto } from 'node:crypto';

import TlsRootCard from './TlsRootCard.vue';

// Real Caddy-issued root captured from a running box. Kept as raw
// PEM so the test exercises the same pemToDer + parseValidity path
// production does — a stubbed digest wouldn't have caught the
// PEM-vs-DER fingerprint bug this component's history is built on.
const REAL_PEM = `-----BEGIN CERTIFICATE-----
MIIBozCCAUmgAwIBAgIQJr0o3RKqD2P8VrSoBYbdszAKBggqhkjOPQQDAjAwMS4w
LAYDVQQDEyVDYWRkeSBMb2NhbCBBdXRob3JpdHkgLSAyMDI2IEVDQyBSb290MB4X
DTI2MDgyNTE2MDcwOFoXDTM2MDcwMzE2MDcwOFowMDEuMCwGA1UEAxMlQ2FkZHkg
TG9jYWwgQXV0aG9yaXR5IC0gMjAyNiBFQ0MgUm9vdDBZMBMGByqGSM49AgEGCCqG
SM49AwEHA0IABPyuYzhhZvPUQvXjGrlGyrwZ+zNQyZ7ciMuOX9NleOJdOKMI/3o7
Z0oa1gGiZi/hyO7+qcSAHoG5dLlpr8HhbBmjRTBDMA4GA1UdDwEB/wQEAwIBBjAS
BgNVHRMBAf8ECDAGAQH/AgEBMB0GA1UdDgQWBBT2P6FXot65dcvsKfSDIml0k1PR
gTAKBggqhkjOPQQDAgNIADBFAiEA2nMZp09akajo7EQ5zusVypSuLD+xhRcbdV61
vRpz/1wCIFVBB6AbeYHFYCROjQCT4Y2jtp+hb1tEG0pCip84zkqW
-----END CERTIFICATE-----
`;

// SHA-256 fingerprint of the DER-encoded form of REAL_PEM above,
// captured with `openssl x509 -noout -fingerprint -sha256`. Hard-coded
// so a bug that fingerprinted the PEM text (or the ArrayBuffer with
// wrapping) instead of the DER body fails loudly.
const REAL_FINGERPRINT =
  '17:0B:4B:49:20:59:B5:3B:32:0B:8A:25:10:53:85:88:F8:65:C4:6B:7C:33:AC:51:72:81:3C:4D:13:34:41:58';

beforeEach(() => {
  // jsdom doesn't ship crypto.subtle by default; Node's webcrypto is
  // API-compatible.
  vi.stubGlobal('crypto', webcrypto);
});

afterEach(() => {
  // The insecure-context case stubs crypto to {}. Without this, that stub
  // survives into whatever runs next in the same worker and the fingerprint
  // test fails intermittently — which it did, exactly once, before this.
  vi.unstubAllGlobals();
});

function stubFetchWithPem(pem: string): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      const bytes = new TextEncoder().encode(pem);
      return {
        ok: true,
        arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
      } as unknown as Response;
    }),
  );
}

describe('TlsRootCard', () => {
  it('renders the download link at the same API path as onboarding', async () => {
    stubFetchWithPem(REAL_PEM);
    const w = mount(TlsRootCard);
    await flushAll();

    const dl = w.get('[data-test="tls-root-download"]');
    // Matches OnboardingApi.caddyRootCaUrl() so operators who
    // installed the root via the wizard and via Settings later end
    // up with the same file, no drift.
    expect(dl.attributes('href')).toBe('/api/system/caddy-root.crt');
    expect(dl.attributes('download')).toBe('caddy-root.crt');
  });

  it('computes the DER-based SHA-256 fingerprint that matches openssl', async () => {
    stubFetchWithPem(REAL_PEM);
    const w = mount(TlsRootCard);
    await flushAll();

    const fp = w.get('[data-test="tls-root-fingerprint"]').text();
    // Colon-separated uppercase hex, matching Firefox's cert-info
    // panel and `openssl x509 -fingerprint -sha256`. If someone
    // switches to lowercase, or fingerprints the PEM wrapper by
    // mistake, this line rats them out.
    expect(fp).toBe(REAL_FINGERPRINT);
  });

  it('over plain http, offers the download and explains the missing fingerprint instead of erroring', async () => {
    // Browsers do not expose crypto.subtle on an insecure origin, which is
    // exactly where a new box is reached: http://aurora.local. The card
    // used to render "Aurora couldn't read the TLS root certificate just
    // now" — a red failure on the one card whose job is ending browser
    // warnings, while the certificate itself downloaded fine.
    vi.stubGlobal('crypto', {});
    stubFetchWithPem(REAL_PEM);
    const w = mount(TlsRootCard);
    await flushAll();

    expect(w.find('[data-test="tls-root-error"]').exists()).toBe(false);
    expect(w.get('[data-test="tls-root-insecure"]').text()).toMatch(/ready to download/i);
    expect(w.get('[data-test="tls-root-download"]').attributes('href')).toBe('/api/system/caddy-root.crt');
  });

  it('shows an error state when the endpoint fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 503 } as unknown as Response)),
    );
    const w = mount(TlsRootCard);
    await flushAll();

    expect(w.get('[data-test="tls-root-error"]').text()).toMatch(/read the tls root certificate/i);
    // No fingerprint in an error state — better to say nothing than
    // to leave a stale value from a previous mount cycle.
    expect(w.find('[data-test="tls-root-fingerprint"]').exists()).toBe(false);
  });
});

// The card's mount flow has three sequential awaits (fetch →
// arrayBuffer → crypto.subtle.digest), and Vue schedules a render
// after each ref write. One flushPromises() unblocks the microtask
// queue once; we need enough passes to drain all three plus the
// final render, otherwise the assertions run while the card is
// still in its loading skeleton.
async function flushAll(): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await flushPromises();
  }
}
