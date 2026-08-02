<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useSystemStore } from '@/stores/system';
import Card from '@/components/ui/Card.vue';
import Alert from '@/components/ui/Alert.vue';

// iter-3 P1b: honest empty state. The previous stub emitted a fabricated
// score = 78 and four made-up findings (UFW / backup / fail2ban / etc.)
// even when hand-typed as /security. That was a footgun — Sarah would
// screenshot it thinking Aurora had run a real scan. Now the view keys
// off `system.capabilities.securityScanner`:
//
//   - false (v0.2.x default) → empty-state view. No score. No findings.
//     Just a copy line describing what M4 will scan. Sidebar also hides
//     the /security nav link (see Sidebar.vue).
//   - true (M4+) → placeholder for the real posture view; not this
//     iteration's scope.

const system = useSystemStore();

onMounted(() => {
  if (!system.info) system.fetchInfo().catch(() => { /* silent */ });
});

const scannerLive = computed<boolean>(() =>
  system.info?.capabilities?.securityScanner === true,
);
</script>

<template>
  <section data-view="security-posture">
    <div class="mb-10">
      <div class="eyebrow mb-2">Security</div>
      <h1 class="mb-3">Security posture</h1>
      <p class="text-ink-3 max-w-2xl">
        Aurora will run a fixed set of opinionated checks against your host,
        containers, and secrets. Each finding will have a fix — no silent nags.
      </p>
    </div>

    <!--
      capability flag off — v0.2.x default. We render the same warm
      empty-state pattern used on the dashboard cards: glyph + short
      copy + planned-scope list. Zero fabricated data.
    -->
    <Card v-if="!scannerLive" data-state="empty" class="p-10 text-center" data-test="security-empty">
      <svg
        viewBox="0 0 24 24"
        class="w-8 h-8 text-ink-4 mx-auto mb-4"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        aria-hidden="true"
      >
        <path d="M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M9 12l2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3 class="mb-2">Watching for common misconfigurations</h3>
      <p class="text-sm text-ink-3 max-w-xl mx-auto mb-6">
        The security scanner lands with milestone <span class="font-mono">M4</span>.
        Nothing on this page is a real audit yet — no score, no findings.
      </p>
      <div class="grid grid-cols-2 gap-x-8 gap-y-2 max-w-xl mx-auto text-left text-sm text-ink-3">
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>Weak-secret detection</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>Exposed-port audit</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>TLS chain check</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>Backup-age SLA</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>fail2ban ban history</span>
        </div>
        <div class="flex items-baseline gap-2">
          <span class="text-ink-4 text-xs font-mono">M4</span>
          <span>Unattended-upgrades SLA</span>
        </div>
      </div>
    </Card>

    <!--
      capability flag on — placeholder for the M4 milestone. Not
      implemented in this iteration. Kept as a stub so the M4 chain has
      a target to render into.
    -->
    <Alert v-else tone="info">
      Security scanner is enabled but the M4 view is not yet implemented.
    </Alert>
  </section>
</template>
