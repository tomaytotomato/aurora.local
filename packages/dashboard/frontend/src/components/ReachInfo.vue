<script setup lang="ts">
import { computed, ref } from 'vue';
import { copyToClipboard } from '@/lib/utils';
import { renderIdentity } from '@/lib/identity';

/**
 * Reach-this-box panel — shows the two ways to reach Aurora from another
 * device on the LAN, with copy-to-clipboard for each.
 *
 * iter-3 P1a: This is the productionize fix for the "aurora.local doesn't
 * resolve on my Mac" nightmare Bruce hit on 2026-08-02. The mDNS hostname
 * is the ergonomic default, but the LAN IP is the always-works fallback
 * for browsers that block LAN mDNS (Firefox on macOS Sequoia without the
 * Local Network permission being the case in point).
 *
 * Props:
 *   - hostname / domain: for the `aurora.local` line (uses shared
 *     renderIdentity so the dedup rule from B2 applies).
 *   - lanIp: for the always-works fallback line.
 *   - port: optional, appended to both URLs. Left unset in production:
 *     Caddy fronts the box on the standard port, so the address is a bare
 *     `http://aurora.local`. Pass a port only for a direct-to-backend dev
 *     link. (Previously defaulted to 8090, which advertised the dev port
 *     as the way back to a box that actually answers on Caddy.)
 *   - variant: 'card' (bordered box, for Done page) or 'inline' (borderless,
 *     for the System card on /dashboard/home).
 */

const props = withDefaults(defineProps<{
  hostname?: string | null;
  domain?: string | null;
  lanIp?: string | null;
  port?: number;
  variant?: 'card' | 'inline';
  /**
   * Scheme for the name-based link. The Done page passes 'https': by the
   * time the wizard reaches it the user has been walked through trusting
   * this box's certificate, and handing them an http:// link three screens
   * later undoes that. The IP fallback stays http — the certificate covers
   * the name, not the address, so an https IP link would produce exactly
   * the browser warning the trust step was for.
   */
  scheme?: 'http' | 'https';
}>(), {
  variant: 'card',
  scheme: 'http',
});

const mdnsHost = computed(() => renderIdentity(props.hostname, props.domain));
const portSuffix = computed(() => (props.port ? `:${props.port}` : ''));
const mdnsUrl = computed(() => `${props.scheme}://${mdnsHost.value}${portSuffix.value}`);
const ipUrl = computed(() => (props.lanIp ? `http://${props.lanIp}${portSuffix.value}` : null));

// Copy-feedback state, keyed by which button was clicked.
const copied = ref<'mdns' | 'ip' | null>(null);
let clearTimer: number | undefined;

async function copy(kind: 'mdns' | 'ip'): Promise<void> {
  const text = kind === 'mdns' ? mdnsUrl.value : ipUrl.value;
  if (!text) return;
  const ok = await copyToClipboard(text);
  if (!ok) return;
  copied.value = kind;
  if (clearTimer) window.clearTimeout(clearTimer);
  clearTimer = window.setTimeout(() => { copied.value = null; }, 1600);
}
</script>

<template>
  <section
    :class="variant === 'card'
      ? 'border border-border rounded-lg p-5 bg-muted/60'
      : ''"
    data-test="reach-info"
  >
    <div v-if="variant === 'card'" class="eyebrow mb-3">Reach this box at</div>

    <div :class="variant === 'card' ? 'space-y-3' : 'space-y-2'">
      <!-- mDNS host: the ergonomic default. -->
      <div class="flex items-center gap-3">
        <span
          v-if="variant === 'inline'"
          class="text-muted-foreground text-xs uppercase tracking-wide flex-shrink-0"
        >mDNS</span>
        <code class="font-mono text-sm text-foreground flex-1 truncate" data-test="reach-mdns">{{ mdnsUrl }}</code>
        <button
          type="button"
          class="text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border hover:border-muted-foreground transition-colors flex-shrink-0"
          data-test="reach-copy-mdns"
          :aria-label="`Copy ${mdnsUrl}`"
          @click="copy('mdns')"
        >
          {{ copied === 'mdns' ? 'Copied' : 'Copy' }}
        </button>
      </div>

      <!-- LAN IP: the always-works fallback. -->
      <div v-if="ipUrl" class="flex items-center gap-3">
        <span
          v-if="variant === 'inline'"
          class="text-muted-foreground text-xs uppercase tracking-wide flex-shrink-0"
        >LAN IP</span>
        <code class="font-mono text-sm text-foreground flex-1 truncate" data-test="reach-ip">{{ ipUrl }}</code>
        <button
          type="button"
          class="text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border hover:border-muted-foreground transition-colors flex-shrink-0"
          data-test="reach-copy-ip"
          :aria-label="`Copy ${ipUrl}`"
          @click="copy('ip')"
        >
          {{ copied === 'ip' ? 'Copied' : 'Copy' }}
        </button>
      </div>
    </div>

    <p
      v-if="variant === 'card'"
      class="text-xs text-muted-foreground mt-4"
      data-test="reach-help"
    >
      Bookmark the first one. Apple devices find
      <code class="font-mono">{{ mdnsHost }}</code> on their own; Windows, Android and
      Linux find it once this box is their DNS server — see below. The
      address underneath always works, from anything, and never depends on
      names resolving.
    </p>
  </section>
</template>
