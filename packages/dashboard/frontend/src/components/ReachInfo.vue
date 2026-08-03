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
 *   - port: appended to both URLs (defaults to 8090 until Caddy/TLS lands).
 *   - variant: 'card' (bordered box, for Done page) or 'inline' (borderless,
 *     for the System card on /dashboard/home).
 */

const props = withDefaults(defineProps<{
  hostname?: string | null;
  domain?: string | null;
  lanIp?: string | null;
  port?: number;
  variant?: 'card' | 'inline';
}>(), {
  port: 8090,
  variant: 'card',
});

const mdnsHost = computed(() => renderIdentity(props.hostname, props.domain));
const mdnsUrl = computed(() => `http://${mdnsHost.value}:${props.port}`);
const ipUrl = computed(() => (props.lanIp ? `http://${props.lanIp}:${props.port}` : null));

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
      Use the mDNS name from any device that supports Bonjour / Avahi.
      If Firefox on macOS refuses to resolve <code class="font-mono">{{ mdnsHost }}</code>,
      or the site says "Unable to connect", paste the LAN IP link instead —
      it never depends on mDNS working.
    </p>
  </section>
</template>
