<script setup lang="ts">
/**
 * TLS root CA — re-download from Settings, not just onboarding.
 *
 * Caddy issues an internal root CA on first boot and every certificate
 * on {$DOMAIN} chains up to it. The onboarding wizard prompted the
 * operator to install that root once, then handed off. Two things
 * conspire to make one-shot install insufficient:
 *
 *   1. If Caddy's PKI ever gets recreated (fresh install on the same
 *      hostname, `docker compose down -v`, a bind-mount reset, a
 *      manual `rm -rf data/caddy/data/caddy/pki`), a new root is
 *      generated with the same CN as the old one but a different
 *      private key. Browsers pick the trusted root by subject DN,
 *      try to verify Caddy's new intermediate with the old key, and
 *      raise SEC_ERROR_BAD_SIGNATURE. This has bitten us at least
 *      once (2026-08-26, notes.aurora.local).
 *
 *   2. New client devices (phone, laptop, guest machine) need the
 *      root too. Sending someone back through /onboarding/tls to
 *      grab it is unfriendly and, on a completed box, forbidden.
 *
 * The card here fixes both. It exposes the same
 * GET /api/system/caddy-root.crt endpoint the wizard uses, plus the
 * root's SHA-256 fingerprint (so an operator wondering "is the cert
 * in my keychain the one this box is currently serving?" can eyeball
 * the answer without shelling into anything) and the same per-OS
 * hints the wizard shows.
 *
 * OnboardingTls.vue already promises "You can skip this and install
 * the root CA later from Settings → TLS" — this component is the
 * settings side of that promise.
 */
import { computed, onMounted, ref } from 'vue';

import { OnboardingApi } from '@/api/onboarding';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Skeleton } from '@/components/ui';
import TrustRootInstructions from '@/components/TrustRootInstructions.vue';

const loading = ref<boolean>(true);
const err = ref<string | null>(null);
/**
 * True when the page is served over plain http, where the browser does not
 * expose `crypto.subtle` at all.
 *
 * This is why the card that exists to *get you onto https* reported
 * "Aurora couldn't read the TLS root certificate just now" on a box being
 * reached at http://aurora.local — the certificate downloaded perfectly
 * well (curl proves it), the fingerprint hash was what could not be
 * computed. A missing nicety was being rendered as a red failure on the
 * one card whose job is fixing browser warnings.
 */
const insecureContext = ref<boolean>(false);
const fingerprint = ref<string | null>(null);
const notBefore = ref<string | null>(null);
const notAfter = ref<string | null>(null);

/**
 * Fetch the current root cert bytes and derive a human-readable
 * SHA-256 fingerprint. The endpoint is public (matches the wizard),
 * so an unauthenticated fingerprint check is fine — the cert body
 * itself is not a secret.
 */
async function loadRootMeta(): Promise<void> {
  loading.value = true;
  err.value = null;
  insecureContext.value = false;
  try {
    const resp = await fetch(OnboardingApi.caddyRootCaUrl(), {
      credentials: 'include',
      cache: 'no-store',
    });
    if (!resp.ok) {
      throw new Error(`server returned ${resp.status}`);
    }
    const pemText = new TextDecoder().decode(new Uint8Array(await resp.arrayBuffer()));
    // Browsers and openssl show the fingerprint of the DER-encoded
    // certificate, not the PEM wrapping. Base64-decode the body
    // between the markers first so this matches what appears in
    // Keychain / Firefox / `openssl x509 -fingerprint`.
    const der = pemToDer(pemText);
    if (!der) throw new Error('cert body did not parse as PEM');
    // Bare `crypto`, not `globalThis.crypto`: the digest call below
    // resolves the identifier through the scope chain, and a test (or a
    // polyfill) that swaps the global has to be seen by both or the two
    // disagree about whether hashing is possible.
    if (typeof crypto === 'undefined' || !crypto?.subtle) {
      // Over http the download still works, which is the whole point of
      // the card; only the fingerprint is unavailable. Say that, and say
      // it as information rather than as an error.
      insecureContext.value = true;
      fingerprint.value = null;
      return;
    }
    fingerprint.value = await sha256Fingerprint(der);

    // Best-effort: parse the DER to pull notBefore/notAfter dates so
    // an operator can see how fresh the root is. If parsing fails
    // we still show the fingerprint + download; the dates are a
    // nicety, not the point of the card.
    const dates = parseValidity(der);
    notBefore.value = dates?.notBefore ?? null;
    notAfter.value = dates?.notAfter ?? null;
  } catch (e: unknown) {
    err.value = humanCopyForError(e, {
      subject: 'the TLS root certificate',
      action: 'read',
    });
    fingerprint.value = null;
    notBefore.value = null;
    notAfter.value = null;
  } finally {
    loading.value = false;
  }
}

/**
 * Extract the DER bytes from a PEM string. Returns null when the
 * text isn't PEM (empty response, HTML error page, etc.) so the
 * caller can raise a friendly error rather than blowing up on
 * atob(). Handles LF and CRLF line endings and multiple certs
 * (takes the first).
 */
function pemToDer(pem: string): Uint8Array | null {
  const m = pem.match(/-----BEGIN CERTIFICATE-----([\s\S]+?)-----END CERTIFICATE-----/);
  if (!m) return null;
  const b64 = m[1].replace(/\s+/g, '');
  if (!b64) return null;
  try {
    return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

async function sha256Fingerprint(bytes: Uint8Array): Promise<string> {
  // Copy into a fresh ArrayBuffer so TypeScript's tightened BufferSource
  // typings accept it. crypto.subtle.digest is happy with either but
  // Uint8Array<ArrayBufferLike> stopped being assignable in TS 5.7+.
  const buf = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buf).set(bytes);
  const hash = await crypto.subtle.digest('SHA-256', buf);
  const hex = Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  // Colon-separated in upper-hex, matching the shape openssl and every
  // browser's certificate-info panel show — makes eyeballing whether
  // "the thing on the box" matches "the thing in my keychain" easy.
  return hex.toUpperCase().match(/.{2}/g)!.join(':');
}

/**
 * Grab notBefore + notAfter from a DER-encoded X.509 without pulling
 * in a full ASN.1 parser. We walk the outer SEQUENCE → tbsCertificate
 * SEQUENCE → skip version/serial/signature/issuer → find the two
 * UTCTime/GeneralizedTime entries in the validity SEQUENCE. Enough to
 * label the card; a real parser would be overkill here.
 */
function parseValidity(der: Uint8Array): { notBefore: string; notAfter: string } | null {
  try {
    // Outer SEQUENCE
    let i = readSequenceHeader(der, 0);
    // tbsCertificate SEQUENCE
    i = readSequenceHeader(der, i);
    // Optional [0] EXPLICIT version
    if (der[i] === 0xa0) {
      const [, next] = readTlvLength(der, i + 1);
      i = next;
    }
    // Serial (INTEGER)
    i = skipTlv(der, i);
    // Signature (SEQUENCE)
    i = skipTlv(der, i);
    // Issuer (SEQUENCE)
    i = skipTlv(der, i);
    // Validity (SEQUENCE) — enter it
    i = readSequenceHeader(der, i);
    const notBefore = readTime(der, i);
    i = skipTlv(der, i);
    const notAfter = readTime(der, i);
    return { notBefore, notAfter };
  } catch {
    return null;
  }
}

function readSequenceHeader(der: Uint8Array, i: number): number {
  if (der[i] !== 0x30) throw new Error('expected SEQUENCE');
  const [, next] = readTlvLength(der, i + 1);
  return next;
}

function readTlvLength(der: Uint8Array, i: number): [length: number, contentStart: number] {
  const first = der[i];
  if ((first & 0x80) === 0) return [first, i + 1];
  const n = first & 0x7f;
  let len = 0;
  for (let k = 0; k < n; k++) len = (len << 8) | der[i + 1 + k];
  return [len, i + 1 + n];
}

function skipTlv(der: Uint8Array, i: number): number {
  const [len, contentStart] = readTlvLength(der, i + 1);
  return contentStart + len;
}

function readTime(der: Uint8Array, i: number): string {
  const tag = der[i];
  const [len, contentStart] = readTlvLength(der, i + 1);
  const s = new TextDecoder().decode(der.slice(contentStart, contentStart + len));
  // UTCTime (0x17) is YYMMDDHHMMSSZ. Convert to a real ISO date so
  // the browser's locale formatter can render it.
  if (tag === 0x17) {
    const yy = parseInt(s.slice(0, 2), 10);
    const yyyy = yy >= 50 ? 1900 + yy : 2000 + yy;
    return isoFromParts(yyyy, +s.slice(2, 4), +s.slice(4, 6), +s.slice(6, 8), +s.slice(8, 10), +s.slice(10, 12));
  }
  // GeneralizedTime (0x18) is YYYYMMDDHHMMSSZ.
  if (tag === 0x18) {
    return isoFromParts(+s.slice(0, 4), +s.slice(4, 6), +s.slice(6, 8), +s.slice(8, 10), +s.slice(10, 12), +s.slice(12, 14));
  }
  throw new Error('unexpected time tag');
}

function isoFromParts(y: number, mo: number, d: number, h: number, mi: number, se: number): string {
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${p(y, 4)}-${p(mo)}-${p(d)}T${p(h)}:${p(mi)}:${p(se)}Z`;
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

const rootUrl = computed(() => OnboardingApi.caddyRootCaUrl());

function copyFingerprint(): void {
  if (!fingerprint.value) return;
  navigator.clipboard
    .writeText(fingerprint.value)
    .then(() => {
      toast({
        title: 'Copied',
        description: 'SHA-256 fingerprint on your clipboard.',
        variant: 'success',
        duration: 2000,
      });
    })
    .catch(() => {
      toast({
        title: "Couldn't copy",
        description: 'Your browser blocked clipboard access.',
        variant: 'warning',
        duration: 3000,
      });
    });
}

onMounted(() => {
  void loadRootMeta();
});
</script>

<template>
  <Card class="p-8" data-card="tls-root">
    <div class="flex items-baseline justify-between mb-3 gap-4">
      <div>
        <h3 class="card-title mb-1">TLS root CA</h3>
        <p class="text-xs text-muted-foreground mt-1">
          Install this on every device you use with this box. Skip it and your
          browser will warn you on every app, every time.
        </p>
      </div>
      <a :href="rootUrl" download="caddy-root.crt" data-test="tls-root-download">
        <Button variant="secondary" size="sm">Download</Button>
      </a>
    </div>

    <Alert v-if="err" variant="destructive" class="mb-3" data-test="tls-root-error">
      <AlertDescription>{{ err }}</AlertDescription>
    </Alert>

    <Alert v-else-if="insecureContext" class="mb-3" data-test="tls-root-insecure">
      <AlertDescription>
        The certificate above is ready to download. Its fingerprint can only be
        shown when you are viewing Aurora over https — which is what installing
        this certificate gets you.
      </AlertDescription>
    </Alert>

    <div v-else-if="loading" class="space-y-2 py-2" data-state="loading">
      <Skeleton class="h-4 w-24" />
      <Skeleton class="h-4 w-full" />
      <Skeleton class="h-4 w-2/3" />
    </div>

    <dl v-else-if="!insecureContext" class="text-xs space-y-2 mb-6" data-test="tls-root-meta">
      <div class="flex flex-col gap-1">
        <dt class="eyebrow">SHA-256 fingerprint</dt>
        <dd class="flex items-start justify-between gap-3">
          <code
            class="font-mono text-[11px] leading-relaxed break-all text-foreground"
            data-test="tls-root-fingerprint"
          >{{ fingerprint }}</code>
          <button
            type="button"
            class="text-muted-foreground hover:text-foreground shrink-0"
            title="Copy fingerprint"
            data-test="tls-root-copy-fingerprint"
            @click="copyFingerprint"
          >Copy</button>
        </dd>
      </div>

      <div v-if="notBefore || notAfter" class="flex justify-between gap-3">
        <dt class="text-muted-foreground">Valid</dt>
        <dd class="font-mono text-muted-foreground">
          {{ formatDate(notBefore) }} → {{ formatDate(notAfter) }}
        </dd>
      </div>
    </dl>

    <!-- One shared component with the wizard's TLS step: these were two
         copies with "keep in sync" comments on both, and they had already
         drifted. -->
    <TrustRootInstructions variant="settings" />

    <Alert variant="info" class="mt-6">
      <AlertDescription>
        Already installed and browsers still warn? Caddy may have regenerated
        its root (fresh install, or the PKI directory was reset). Delete
        every <em>Caddy Local Authority</em> entry from your trust store,
        re-download here, re-import, and restart the browser.
      </AlertDescription>
    </Alert>
  </Card>
</template>
