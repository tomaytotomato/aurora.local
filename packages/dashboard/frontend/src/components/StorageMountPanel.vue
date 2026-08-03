<script setup lang="ts">
import { computed, ref } from 'vue';
import { copyToClipboard } from '@/lib/utils';

/**
 * iter-3 BL3: per-OS mount instructions for the SMB share exposed by
 * the storage package. Rendered inline on the storage checklist row
 * (see ChecklistItem.vue).
 *
 * QR codes for iOS/Android are deferred to a follow-up — task file
 * mentions `qrcode-svg` but installing a new npm dep needs a Dockerfile
 * rebuild cycle we don't want to burn in this iteration. The tabs
 * carry a link + copyable command in every OS panel; QR renders as
 * "Show QR code" placeholder for the mobile panels.
 */

const props = withDefaults(defineProps<{
  lanIp?: string | null;
  mdnsHost?: string | null;    // e.g. `aurora.local`
  share?: string;              // top-level share name; default is empty (root)
}>(), {
  share: '',
});

type OsTab = 'mac' | 'windows' | 'ios' | 'android';
const active = ref<OsTab>('mac');
const tabs: { key: OsTab; label: string }[] = [
  { key: 'mac', label: 'macOS' },
  { key: 'windows', label: 'Windows' },
  { key: 'ios', label: 'iOS' },
  { key: 'android', label: 'Android' },
];

const hostTarget = computed(() => props.lanIp || props.mdnsHost || 'aurora.local');
const smbUrl = computed(() => `smb://${hostTarget.value}/${props.share}`);
// Windows UNC path uses backslashes and a lowercase leading double-slash.
const uncPath = computed(() => `\\\\${hostTarget.value}\\${props.share || ''}`);

const copiedKey = ref<string | null>(null);
let clearTimer: number | undefined;
async function copy(kind: string, text: string): Promise<void> {
  const ok = await copyToClipboard(text);
  if (!ok) return;
  copiedKey.value = kind;
  if (clearTimer) window.clearTimeout(clearTimer);
  clearTimer = window.setTimeout(() => { copiedKey.value = null; }, 1600);
}
</script>

<template>
  <div
    class="border border-line rounded-md bg-surface-2/40 p-4"
    data-test="storage-mount-panel"
  >
    <div class="eyebrow mb-3">Mount instructions</div>

    <!-- tabs -->
    <div
      role="tablist"
      class="flex gap-1 border-b border-line/60 mb-4"
      data-test="storage-mount-tabs"
    >
      <button
        v-for="t in tabs"
        :key="t.key"
        type="button"
        role="tab"
        :aria-selected="active === t.key"
        :data-tab="t.key"
        class="px-3 py-1.5 text-sm rounded-t-md border-b-2 transition-colors"
        :class="active === t.key
          ? 'text-ink border-ink-2'
          : 'text-ink-3 hover:text-ink border-transparent'"
        @click="active = t.key"
      >{{ t.label }}</button>
    </div>

    <!-- panels -->
    <div v-if="active === 'mac'" role="tabpanel" data-panel="mac" class="space-y-3">
      <p class="text-sm text-ink-2">
        In Finder press <kbd>⌘</kbd>+<kbd>K</kbd> ("Connect to Server…") and paste:
      </p>
      <div class="flex items-center gap-3">
        <code class="font-mono text-sm text-ink flex-1 truncate">{{ smbUrl }}</code>
        <button
          type="button"
          class="text-xs text-ink-3 hover:text-ink px-2 py-1 rounded border border-line"
          data-test="storage-mount-copy"
          @click="copy('mac', smbUrl)"
        >{{ copiedKey === 'mac' ? 'Copied' : 'Copy' }}</button>
      </div>
      <p class="text-xs text-ink-4">
        Click <em>Connect</em>, then choose <em>Guest</em> or type the share credentials.
      </p>
    </div>

    <div v-else-if="active === 'windows'" role="tabpanel" data-panel="windows" class="space-y-3">
      <p class="text-sm text-ink-2">
        Open File Explorer, click the address bar, and paste:
      </p>
      <div class="flex items-center gap-3">
        <code class="font-mono text-sm text-ink flex-1 truncate">{{ uncPath }}</code>
        <button
          type="button"
          class="text-xs text-ink-3 hover:text-ink px-2 py-1 rounded border border-line"
          data-test="storage-mount-copy"
          @click="copy('windows', uncPath)"
        >{{ copiedKey === 'windows' ? 'Copied' : 'Copy' }}</button>
      </div>
      <p class="text-xs text-ink-4">
        Or from a terminal: <code class="font-mono">net use Z: {{ uncPath }} /persistent:yes</code>.
      </p>
    </div>

    <div v-else-if="active === 'ios'" role="tabpanel" data-panel="ios" class="space-y-3">
      <p class="text-sm text-ink-2">
        Open the Files app, tap <em>⋯</em> → <em>Connect to Server</em>, and paste:
      </p>
      <div class="flex items-center gap-3">
        <code class="font-mono text-sm text-ink flex-1 truncate">{{ smbUrl }}</code>
        <button
          type="button"
          class="text-xs text-ink-3 hover:text-ink px-2 py-1 rounded border border-line"
          data-test="storage-mount-copy"
          @click="copy('ios', smbUrl)"
        >{{ copiedKey === 'ios' ? 'Copied' : 'Copy' }}</button>
      </div>
      <p class="text-xs text-ink-4" data-test="storage-mount-qr-placeholder">
        QR code for the URL lands in the next release. Until then, tap the address bar,
        long-press → <em>Paste</em>.
      </p>
    </div>

    <div v-else-if="active === 'android'" role="tabpanel" data-panel="android" class="space-y-3">
      <p class="text-sm text-ink-2">
        Install <em>Solid Explorer</em> or <em>CX File Explorer</em>, add a new <em>SMB / LAN</em>
        location with:
      </p>
      <ul class="text-sm text-ink-2 space-y-1 font-mono">
        <li>Host: <code>{{ hostTarget }}</code></li>
        <li>Share: <code>{{ props.share || '/' }}</code></li>
      </ul>
      <div class="flex items-center gap-3 pt-1">
        <code class="font-mono text-sm text-ink flex-1 truncate">{{ smbUrl }}</code>
        <button
          type="button"
          class="text-xs text-ink-3 hover:text-ink px-2 py-1 rounded border border-line"
          data-test="storage-mount-copy"
          @click="copy('android', smbUrl)"
        >{{ copiedKey === 'android' ? 'Copied' : 'Copy' }}</button>
      </div>
      <p class="text-xs text-ink-4" data-test="storage-mount-qr-placeholder">
        QR code for the URL lands in the next release.
      </p>
    </div>

    <p class="text-xs text-ink-4 mt-4">
      Reach the box via mDNS name <code class="font-mono">{{ mdnsHost || 'aurora.local' }}</code>
      or the LAN IP <code class="font-mono">{{ lanIp || '—' }}</code>. If one fails, try the other.
    </p>
  </div>
</template>
