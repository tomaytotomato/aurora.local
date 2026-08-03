<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ServiceStatus } from '@/api/services';
import StorageMountPanel from '@/components/StorageMountPanel.vue';
import { useSystemStore } from '@/stores/system';

const props = defineProps<{
  service: ServiceStatus;
}>();

const system = useSystemStore();
const showMountPanel = ref(false);
// iter-3 BL1: child sub-package rows (media → prowlarr/sonarr/...).
const showChildren = ref(false);
const hasChildren = computed<boolean>(() => (props.service.children?.length ?? 0) > 0);

// iter-3 BL3: expandable per-OS mount instructions replace the raw
// `smb://` link on the storage row. Kept behind an explicit toggle so
// the row height doesn't balloon by default.
const isStorageRunning = computed(() =>
  props.service.package === 'storage' && props.service.state === 'running',
);

const emit = defineEmits<{
  (e: 'markDone', pkg: string): void;
  (e: 'skip', pkg: string): void;
  (e: 'retry', pkg: string): void;
  (e: 'start', pkg: string): void;
}>();

// data-tone maps to the E2E fixture expectations (spec §5.1).
const tone = computed<'ok' | 'warn' | 'err' | 'info'>(() => {
  switch (props.service.state) {
    case 'running': return 'ok';
    case 'needs-config': return 'warn';
    case 'not-started': return 'warn';
    case 'failed': return 'err';
    case 'starting': return 'info';
    default: return 'info';
  }
});

// Approved primary CTA labels (E2E: /^(Open|Finish setup|Retry|Waiting…|Start)$/).
const ctaLabel = computed<string>(() => {
  switch (props.service.state) {
    case 'running': return 'Open';
    case 'needs-config': return props.service.package === 'privacy' ? 'Finish setup' : 'Finish setup';
    case 'failed': return 'Retry';
    case 'starting': return 'Waiting…';
    case 'not-started': return 'Start';
    default: return 'Open';
  }
});

const ctaDisabled = computed(() => props.service.state === 'starting');

// Human-readable pill text (visible copy — data-status carries the enum).
const pillText = computed(() => {
  switch (props.service.state) {
    case 'running': return 'Running';
    case 'needs-config': return 'Needs setup';
    case 'failed': return 'Not responding';
    case 'not-started': return 'Not started';
    case 'starting': return 'Starting…';
    default: return props.service.state;
  }
});

const pillClass = computed(() => {
  // iter-3 dark-mode contrast fix: switched from hardcoded Tailwind pastels
  // (light-green / light-red / light-neutral) to the design-token status
  // pairs so the pills invert cleanly with the theme. Same visual weight in
  // light mode; readable in dark.
  switch (props.service.state) {
    case 'running':
      return 'bg-[var(--color-ok-bg)] text-[var(--color-ok-fg)] border-[var(--color-ok-fg)]/25';
    case 'needs-config':
      return 'bg-[var(--color-warn-bg)] text-[var(--color-warn-fg)] border-[var(--color-warn-fg)]/25';
    case 'failed':
      return 'bg-[var(--color-err-bg)] text-[var(--color-err-fg)] border-[var(--color-err-fg)]/25';
    case 'not-started':
      return 'bg-[var(--color-surface-2)] text-[var(--color-ink-2)] border-[var(--color-line)]';
    case 'starting':
      return 'bg-[var(--color-info-bg)] text-[var(--color-info-fg)] border-[var(--color-info-fg)]/25';
    default:
      return 'bg-[var(--color-surface-2)] text-[var(--color-ink-2)] border-[var(--color-line)]';
  }
});

const isCollapsed = computed(() =>
  props.service.state === 'running' && !props.service.reason,
);

const canOverride = computed(() =>
  props.service.state === 'needs-config' || props.service.state === 'not-started',
);

function onPrimary() {
  const s = props.service;
  // iter-3 Start-button UX: hard belt-and-braces click guard at the
  // child level too. Vue disables the DOM button when :disabled=true
  // but a synthetic-event-in-flight can still fire onPrimary once more
  // during the transition tick. Silently ignore when the parent has
  // already flipped us to 'starting'.
  if (s.state === 'starting') return;
  if (s.state === 'failed') {
    emit('retry', s.package);
  } else if (s.state === 'not-started') {
    emit('start', s.package);
  }
  // For running / needs-config the anchor href handles it; nothing to emit.
}
</script>

<template>
  <li
    :data-package="service.package"
    :data-row="service.package"
    :data-tone="tone"
    class="border border-border rounded-lg p-4 bg-[var(--color-surface)] text-foreground"
    :class="isCollapsed ? 'py-2' : ''"
  >
    <div class="flex items-start justify-between gap-4">
      <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2">
        <span
          :data-status="service.state"
          class="inline-flex items-center text-xs font-medium px-2 py-0.5 rounded border"
          :class="pillClass"
        >{{ pillText }}</span>
        <span class="font-medium capitalize">{{ service.package }}</span>
      </div>
      <p v-if="!isCollapsed && service.reason" class="text-sm text-foreground mt-1">
        {{ service.reason }}
      </p>
      <p v-if="!isCollapsed && service.detail" class="text-xs text-muted-foreground mt-1">
        {{ service.detail }}
      </p>
    </div>
    <div class="flex items-center gap-2 shrink-0">
      <button
        v-if="hasChildren"
        type="button"
        class="text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border"
        :aria-expanded="showChildren"
        data-test="row-children-toggle"
        @click="showChildren = !showChildren"
      >{{ showChildren ? 'Hide' : 'Show' }} {{ service.children!.length }} services</button>
      <button
        v-if="isStorageRunning"
        type="button"
        class="text-xs text-muted-foreground hover:text-foreground px-2 py-1 rounded border border-border"
        :aria-expanded="showMountPanel"
        data-test="storage-mount-toggle"
        @click="showMountPanel = !showMountPanel"
      >{{ showMountPanel ? 'Hide mount steps' : 'How to mount' }}</button>
      <template v-if="ctaLabel === 'Open' || ctaLabel === 'Finish setup'">
        <a
          v-if="service.open_url"
          :href="service.open_url"
          target="_blank"
          rel="noopener"
          role="button"
          class="inline-flex items-center h-9 px-3 text-sm rounded-md border border-border bg-card hover:bg-muted no-underline text-foreground"
        >{{ ctaLabel }}</a>
        <button
          v-else
          type="button"
          class="inline-flex items-center h-9 px-3 text-sm rounded-md border border-border bg-card text-foreground"
          disabled
        >{{ ctaLabel }}</button>
      </template>
      <button
        v-else
        type="button"
        :disabled="ctaDisabled"
        :aria-busy="ctaDisabled || undefined"
        data-test="row-cta"
        class="inline-flex items-center gap-2 h-9 px-3 text-sm rounded-md border border-border bg-card hover:bg-muted text-foreground disabled:opacity-40 disabled:cursor-not-allowed"
        @click="onPrimary"
      >
        <!-- iter-3 Start-button UX: spinner + aria-busy when the row is
             in the 'starting' state (either the backend reported it, or
             DoneChecklist's optimistic overlay set it). Reuses the same
             inline spinner pattern as components/ui/Button.vue loading. -->
        <span
          v-if="ctaDisabled"
          class="inline-block h-3 w-3 border border-current border-t-transparent rounded-full animate-spin"
          aria-hidden="true"
        />
        {{ ctaLabel }}
      </button>
      <button
        v-if="canOverride"
        type="button"
        class="text-xs text-muted-foreground hover:text-foreground"
        @click="emit('markDone', service.package)"
      >I did this</button>
      <button
        v-if="canOverride"
        type="button"
        class="text-xs text-muted-foreground hover:text-foreground"
        @click="emit('skip', service.package)"
      >Skip</button>
    </div>
    </div>

    <!-- iter-3 BL3: per-OS mount instructions for the storage row.
         Rendered only when explicitly toggled so the checklist stays
         compact by default. -->
    <StorageMountPanel
      v-if="isStorageRunning && showMountPanel"
      class="mt-4"
      :lan-ip="system.info?.lanIp"
      :mdns-host="system.info?.domain"
    />

    <!-- iter-3 BL1: child sub-package rows rendered as a nested list
         when the caller toggles "Show" on a row that declares children.
         Recursive: each child gets its own ChecklistItem so `probe.kind`
         and Copy/CTA logic re-use the parent code path unchanged. -->
    <ul
      v-if="hasChildren && showChildren"
      class="mt-3 ml-4 pl-4 border-l border-border/60 space-y-2"
      data-test="row-children"
    >
      <ChecklistItem
        v-for="child in service.children"
        :key="child.package"
        :service="child"
      />
    </ul>
  </li>
</template>
