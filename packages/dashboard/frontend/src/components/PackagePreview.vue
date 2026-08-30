<script setup lang="ts">
/**
 * The app detail page's "should I install this, and what will it do to
 * my box" half — everything that is true about a package before a single
 * container exists for it.
 *
 * Deliberately not a Tabs region: Config, Network and Logs all describe
 * something running, and nothing is running yet. Rather than render those
 * tabs empty (or, worse, erroring — the Network tab had a real history
 * of 404s), this page shows only what is
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
import MarkdownBlock from '@/components/MarkdownBlock.vue';
import PackageImpactPanel from '@/components/PackageImpactPanel.vue';
import PackageResourcesCard from '@/components/PackageResourcesCard.vue';
import { humanEnvLabel, cleanEnvHelp } from '@/lib/envCopy';

const props = defineProps<{
  detail: PackageDetail;
  update?: PackageUpdate | null;
}>();

const readmeBody = computed(() => (props.detail.readme ?? '').replace(/^#\s+.*\n+/, '').trim());
// About is the manifest's own description — one paragraph, written for the
// person deciding whether they want this app. The README is not that: it is
// the owner's setup document, full of `./scripts/up.sh privacy`, compose
// edits and "copy .env.example to .env", which is exactly the terminal
// vocabulary this product exists to remove from the journey. It moves into a
// disclosure below, closed by default, rather than being deleted — the
// people who want it are real, they are just not the default reader.
const aboutBody = computed(() => (props.detail.description ?? '').trim());
const ownerNotes = computed(() => readmeBody.value);

// Only the values the app genuinely cannot start without are worth showing
// before install. Everything else has a working default and belongs on the
// Config tab afterwards.
const requiredEnvSpecs = computed(() => (props.detail.envVars ?? []).filter((s) => s.required));
// When the About text came from the manifest README it is authored
// markdown and needs MarkdownBlock — otherwise Notes-style READMEs
// surface `## First-run` and `[SilverBullet](https://…)` as raw text,
// which is what shipped before this fix. The description fallback is
// plain prose, so a Notes-style README triggers markdown but a manifest
// description does not. Matches PackageDetail.vue's `aboutIsMarkdown` —
// the two paths must not disagree about the same field.

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

      <details v-if="ownerNotes" class="mt-5 border-t border-border pt-4" data-test="package-owner-notes">
        <summary class="text-sm text-muted-foreground cursor-pointer select-none">
          Setup notes for the owner · technical
        </summary>
        <div class="mt-3">
          <MarkdownBlock :source="ownerNotes" />
        </div>
      </details>
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

    <Card v-if="requiredEnvSpecs.length" class="col-span-2" data-test="package-preview-config">
      <div class="eyebrow mb-1">Configuration</div>
      <h3 class="mb-3">What you'll need to hand</h3>
      <ul class="text-sm space-y-2">
        <li v-for="spec in requiredEnvSpecs" :key="spec.key" class="flex items-start justify-between gap-4">
          <span>{{ humanEnvLabel(spec.key) }}</span>
          <span class="flex items-center gap-2 text-xs text-muted-foreground shrink-0">
            <Badge v-if="spec.secret" tone="neutral">kept secret</Badge>
            <span v-if="cleanEnvHelp(spec.comment)" class="max-w-xs text-right">{{ cleanEnvHelp(spec.comment) }}</span>
          </span>
        </li>
      </ul>
      <p class="text-xs text-muted-foreground mt-3">
        Aurora fills in everything else. You can change any of it later on this app's
        Config screen.
      </p>
    </Card>
  </div>
</template>
