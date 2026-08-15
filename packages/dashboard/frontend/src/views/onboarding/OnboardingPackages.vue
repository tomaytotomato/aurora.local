<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useOnboardingStore } from '@/stores/onboarding';
import { usePackagesStore } from '@/stores/packages';
import { OnboardingApi } from '@/api/onboarding';
import Button from '@/components/ui/Button.vue';
import Tabs from '@/components/ui/Tabs.vue';
import { Alert, AlertDescription, Badge } from '@/components/ui';
import { isCorePackage, type PackageCategory } from '@/api/packages';
import { packageLabel } from '@/lib/packageName';

const store = useOnboardingStore();
const packages = usePackagesStore();
const router = useRouter();

const err = ref<string | null>(null);
const activeCategory = ref<PackageCategory | 'all'>('all');

// Packages that ship on every box regardless of what the operator picks:
// Caddy (core, the reverse proxy), Authelia (identity, the forward-auth
// SSO provider every protected service fronts through), and Samba
// (storage) — see PackagesCore.vue's own comment and isCorePackage()'s
// doc comment for why these three are the platform baseline. They still
// show in the grid, with a "core" pill, so the operator can see what
// they're getting rather than wonder where Caddy went; the control is
// just locked on, because there's no coherent "off" state for any of
// them.
//
// isCorePackage() is the single source of truth — reused here rather
// than re-deriving a picker-local list, so this screen can't drift from
// what PackagesCore.vue / PackageDetail.vue already treat as mandatory
// (the previous version of this file only locked 'core', which let an
// operator deselect Samba here and then find it relabelled "Always on —
// can't be removed" one screen later).
function isMandatory(name: string): boolean {
  return isCorePackage({ name });
}

// Live resource-warning preview. Populated by a debounced GET /plan
// scoped to the current selection so users see warnings (e.g. Ollama
// needs a GPU) before committing. Preview failures are non-fatal:
// selection must always remain usable even if the backend is flaky.
const previewWarnings = ref<string[]>([]);
const previewChecking = ref(false);
let previewTimer: ReturnType<typeof setTimeout> | null = null;
let previewSeq = 0;

// Fallback catalogue if the backend isn't up yet — real values match
// packages/*/manifest.yml. Kept as a duplicate deliberately: first boot
// is exactly when the backend is most likely to be slow (still probing
// the host, migrating the DB, etc.), and an empty picker is worse than a
// slightly-stale one. Descriptions here still need to stay in sync by
// hand, though — this is what let the old "+ Homepage" copy survive a
// manifest fix that didn't touch this file.
const fallback = [
  { name: 'core', category: 'core' as PackageCategory, description: 'Caddy reverse proxy.' },
  { name: 'privacy', category: 'privacy' as PackageCategory, description: 'AdGuard Home + Gluetun VPN sidecar.' },
  { name: 'media', category: 'media' as PackageCategory, description: 'Sonarr, Radarr, Bazarr, Prowlarr, Seerr, qBittorrent, SABnzbd.' },
  { name: 'storage', category: 'storage' as PackageCategory, description: 'Samba + MiniDLNA.' },
  { name: 'monitoring', category: 'monitoring' as PackageCategory, description: 'Prometheus + Grafana + Uptime-Kuma.' },
  { name: 'backup', category: 'storage' as PackageCategory, description: 'Kopia dedup backup with Web UI.' },
  { name: 'photos', category: 'productivity' as PackageCategory, description: 'Immich — photos & video library.' },
  { name: 'documents', category: 'productivity' as PackageCategory, description: 'Paperless-ngx + Stirling-PDF.' },
  { name: 'notes', category: 'productivity' as PackageCategory, description: 'SilverBullet — offline-first notes.' },
  { name: 'git', category: 'dev' as PackageCategory, description: 'Forgejo + Forgejo runner CI.' },
  { name: 'dev', category: 'dev' as PackageCategory, description: 'code-server + Postgres + Redis.' },
  { name: 'ai', category: 'ai' as PackageCategory, description: 'Ollama + Open-WebUI.' },
  { name: 'home-automation', category: 'home-automation' as PackageCategory, description: 'Home Assistant + Mosquitto + Zigbee2MQTT.' },
  { name: 'identity', category: 'identity' as PackageCategory, description: 'Authelia SSO + 2FA forward-auth.' },
];

onMounted(async () => {
  try {
    await packages.fetchList();
  } catch { /* fall through to fallback */ }
  // Kick off an initial preview so warnings show for the default preset.
  schedulePreview();
});

onUnmounted(() => {
  if (previewTimer) clearTimeout(previewTimer);
});

// Debounced live preview. Watches selectedPackages (deep, since it's an
// array mutated in place by togglePackage / selectPreset) and schedules a
// 250ms-delayed /plan call. Uses an incrementing seq so a slow response
// from an earlier selection can't clobber a fresher one.
function schedulePreview(): void {
  if (previewTimer) clearTimeout(previewTimer);
  previewChecking.value = true;
  previewTimer = setTimeout(async () => {
    const seq = ++previewSeq;
    const snapshot = [...store.selectedPackages];
    try {
      const plan = await OnboardingApi.previewPlan(snapshot);
      if (seq !== previewSeq) return; // stale — a newer selection is in flight
      previewWarnings.value = plan.warnings;
    } catch (e) {
      // Silent by design: a failed preview must never block selection.
      // eslint-disable-next-line no-console
      console.warn('preview /plan failed', e);
      if (seq !== previewSeq) return;
      previewWarnings.value = [];
    } finally {
      if (seq === previewSeq) previewChecking.value = false;
    }
  }, 250);
}

watch(
  () => store.selectedPackages,
  () => schedulePreview(),
  { deep: true },
);

const catalogue = computed(() => {
  if (packages.list.length > 0) {
    return packages.list.map((p) => ({
      name: p.name,
      category: p.category,
      description: p.description,
    }));
  }
  return fallback;
});

const categories = computed<Array<{ value: PackageCategory | 'all'; label: string }>>(() => {
  const set = new Set<PackageCategory>();
  catalogue.value.forEach((p) => set.add(p.category));
  return [
    { value: 'all', label: 'All' },
    ...[...set].map((c) => ({
      value: c,
      label: c.replace('-', ' ').replace(/\b\w/g, (m) => m.toUpperCase()),
    })),
  ];
});

const filtered = computed(() =>
  activeCategory.value === 'all'
    ? catalogue.value
    : catalogue.value.filter((p) => p.category === activeCategory.value),
);

const isSelected = (name: string): boolean => store.selectedPackages.includes(name);

function toggle(name: string): void {
  if (isMandatory(name)) return; // locked on — see isMandatory() above
  store.togglePackage(name);
}

async function proceed(): Promise<void> {
  err.value = null;
  const missingMandatory = catalogue.value
    .map((p) => p.name)
    .filter((name) => isMandatory(name) && !store.selectedPackages.includes(name));
  if (missingMandatory.length > 0) {
    store.selectedPackages = [...missingMandatory, ...store.selectedPackages];
  }
  await store.patchDraft({
    enabled_packages: store.selectedPackages,
    step: 'secrets',
  });
  store.next();
  router.push(`/onboarding/${store.currentStep}`);
}
</script>

<template>
  <div>
    <div class="eyebrow mb-3">Step 4 of 10</div>
    <h1 class="mb-4">Pick your packages.</h1>
    <p class="text-foreground mb-6">
      Each app comes with sensible defaults. You can add or remove any of them
      later; nothing is permanent.
    </p>

    <div class="mb-8">
      <div class="eyebrow mb-2">Presets</div>
      <div class="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" @click="store.selectPreset('safe')">Safe default</Button>
        <Button variant="secondary" size="sm" @click="store.selectPreset('media')">Media server</Button>
        <Button variant="secondary" size="sm" @click="store.selectPreset('cloud')">Personal cloud</Button>
      </div>
    </div>

    <Alert v-if="err" variant="destructive" class="mb-6">
      <AlertDescription>{{ err }}</AlertDescription>
    </Alert>

    <Tabs
      :model-value="activeCategory"
      :tabs="categories"
      size="sm"
      class="overflow-x-auto mb-6"
      @update:model-value="activeCategory = $event as PackageCategory | 'all'"
    />

    <div class="grid grid-cols-2 gap-3 mb-3">
      <!--
        Single control per card, not a checkbox nested inside a button.
        The card itself is the checkbox (role="checkbox" + aria-checked);
        the little box on the left is a decorative echo of that state,
        not a second interactive element. Locked (mandatory) cards stay
        focusable-but-inert: aria-disabled rather than the native
        `disabled` so a screen reader still reaches and announces them,
        pointed at the footnote via aria-describedby instead of just
        going silent.
      -->
      <button
        v-for="pkg in filtered"
        :key="pkg.name"
        :data-package="pkg.name"
        type="button"
        role="checkbox"
        :aria-checked="isSelected(pkg.name)"
        :aria-disabled="isMandatory(pkg.name) || undefined"
        :aria-describedby="isMandatory(pkg.name) ? 'mandatory-packages-note' : undefined"
        class="text-left p-4 rounded-lg border transition-all duration-150 flex items-start gap-3"
        :class="[
          isSelected(pkg.name) ? 'border-foreground bg-card' : 'border-border bg-card hover:border-muted-foreground',
          isMandatory(pkg.name) ? 'cursor-default' : 'cursor-pointer',
        ]"
        @click="toggle(pkg.name)"
      >
        <span
          class="inline-flex items-center justify-center h-4 w-4 rounded-[3px] border mt-0.5 shrink-0 transition-colors duration-150"
          :class="isSelected(pkg.name)
            ? 'bg-primary border-primary text-primary-foreground'
            : 'bg-background border-input'"
          :style="isMandatory(pkg.name) ? 'opacity: 0.6' : undefined"
          aria-hidden="true"
        >
          <svg
            v-if="isSelected(pkg.name)"
            viewBox="0 0 12 12"
            class="w-2.5 h-2.5"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          >
            <path d="M2 6 L5 9 L10 3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-foreground">{{ packageLabel(pkg) }}</span>
            <Badge v-if="isMandatory(pkg.name)" tone="info">core</Badge>
          </div>
          <p class="text-xs text-muted-foreground mt-1 line-clamp-2">{{ pkg.description }}</p>
        </div>
      </button>
    </div>

    <p id="mandatory-packages-note" class="text-xs text-muted-foreground mb-8">
      Core packages are switched on by default — they're essential to
      aurora.local, so they can't be turned off here.
    </p>

    <!-- Live resource warnings. Only renders when there's something to say,
         so the layout doesn't have an empty gap on a healthy selection. -->
    <div v-if="previewWarnings.length > 0" class="mb-8">
      <div class="flex items-center gap-2 mb-2">
        <div class="eyebrow">Resource warnings</div>
        <div v-if="previewChecking" class="text-xs text-muted-foreground">checking…</div>
      </div>
      <Alert
        v-for="(w, i) in previewWarnings"
        :key="i"
        variant="warning"
        class="mb-2"
      >
        <AlertDescription>{{ w }}</AlertDescription>
      </Alert>
    </div>
    <div v-else-if="previewChecking" class="mb-8 text-xs text-muted-foreground">
      checking selection…
    </div>

    <div class="flex items-center justify-between">
      <Button variant="ghost" @click="() => { store.back(); router.push(`/onboarding/${store.currentStep}`); }">
        Back
      </Button>
      <Button variant="primary" size="lg" @click="proceed">
        Continue with {{ store.selectedPackages.length }} package{{ store.selectedPackages.length === 1 ? '' : 's' }}
      </Button>
    </div>
  </div>
</template>
