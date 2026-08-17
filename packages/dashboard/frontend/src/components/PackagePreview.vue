<script setup lang="ts">
/**
 * The app detail page's "should I install this, and what will it do to
 * my box" half — everything that is true about a package before a single
 * container exists for it.
 *
 * Deliberately not a Tabs region: Config, Network and Logs all describe
 * something running, and nothing is running yet. Rather than render those
 * tabs empty (or, worse, erroring — see dev/notes/detail-page-truth-progress.md
 * for the Network tab's real-404 history), this page shows only what is
 * honestly knowable pre-install: what the app is, what installing it would
 * cost (`PackageImpactPanel`, already built and already used one dialog
 * over from here for exactly this), the version it would install (a tag,
 * not a freshness verdict — nothing has been pulled, so there is nothing
 * to call current or behind), the ceiling it would run under
 * (`PackageResourcesCard`), and the env vars it will ask for once it does
 * exist (manifest-only — no live values, because there are none).
 */
import { computed } from 'vue';

import type { PackageDetail } from '@/api/packages';
import type { PackageUpdate } from '@/api/updates';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import PackageImpactPanel from '@/components/PackageImpactPanel.vue';
import PackageResourcesCard from '@/components/PackageResourcesCard.vue';

const props = defineProps<{
  detail: PackageDetail;
  update?: PackageUpdate | null;
}>();

// Same readme-falls-back-to-description rule as the installed Overview's
// About card (detail-page-truth-progress.md, fault 3) — the two must never
// disagree about whether this app has a description.
const readmeBody = computed(() => (props.detail.readme ?? '').replace(/^#\s+.*\n+/, '').trim());
const aboutBody = computed(() => readmeBody.value || (props.detail.description ?? '').trim());

const envSpecs = computed(() => props.detail.envVars ?? []);

// The images list carries the tag the compose file references — knowable
// from the repository alone, whether or not the image has ever been
// pulled. No freshness claim: `update.state` (current/available/unknown)
// describes a comparison against a running install, which this isn't.
const hasImages = computed(() => (props.update?.images.length ?? 0) > 0);
</script>

<template>
  <div class="grid grid-cols-2 gap-4" data-test="package-preview">
    <Card class="col-span-2">
      <div class="eyebrow mb-1">About</div>
      <h3 class="mb-3">What this is</h3>
      <p v-if="aboutBody" class="text-sm text-foreground whitespace-pre-line">{{ aboutBody }}</p>
      <p v-else class="text-sm text-muted-foreground">No description yet.</p>
    </Card>

    <Card class="col-span-2" data-test="package-preview-impact">
      <div class="eyebrow mb-1">Impact</div>
      <h3 class="mb-3">What installing this changes</h3>
      <PackageImpactPanel :detail="detail" />
    </Card>

    <Card v-if="hasImages" class="col-span-2" data-test="package-preview-version">
      <div class="eyebrow mb-1">Version</div>
      <h3 class="mb-3">What you'd install</h3>
      <ul class="text-sm space-y-1.5">
        <li
          v-for="img in update!.images"
          :key="img.image"
          class="flex items-center justify-between gap-4"
        >
          <span class="font-mono text-xs text-muted-foreground truncate">{{ img.image }}</span>
          <span class="flex items-center gap-2 shrink-0">
            <span class="font-mono">{{ img.currentTag }}</span>
            <Badge v-if="img.pinned" tone="neutral">pinned</Badge>
          </span>
        </li>
      </ul>
      <p class="text-xs text-muted-foreground mt-3">
        This is what the app installs on today. Nothing has been pulled onto this box yet, so
        there's no "up to date" or "behind" to report — that starts meaning something once it's
        actually running.
      </p>
    </Card>

    <PackageResourcesCard :package="detail.name" class="col-span-2" />

    <Card v-if="envSpecs.length" class="col-span-2" data-test="package-preview-config">
      <div class="eyebrow mb-1">Configuration</div>
      <h3 class="mb-3">What you'll be asked to set</h3>
      <ul class="text-sm space-y-2">
        <li v-for="spec in envSpecs" :key="spec.key" class="flex items-center justify-between gap-4">
          <span class="font-mono text-xs">{{ spec.key }}</span>
          <span class="flex items-center gap-2 text-xs text-muted-foreground shrink-0">
            <Badge v-if="spec.required" tone="warn">required</Badge>
            <Badge v-if="spec.secret" tone="neutral">secret</Badge>
            <span v-if="spec.comment" class="max-w-xs text-right">{{ spec.comment }}</span>
          </span>
        </li>
      </ul>
    </Card>
  </div>
</template>
