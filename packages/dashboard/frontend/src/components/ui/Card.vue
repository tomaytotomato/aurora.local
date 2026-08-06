<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';

// shadcn-vue Card (C7 iter-9).
//
// Aurora's flat single-primitive Card is preserved — shadcn ships
// Card / CardHeader / CardTitle / CardDescription / CardContent /
// CardFooter as separate sub-primitives. Six caller files use the
// flat surface with inline layout (eyebrow / h3 / body) rather than
// the shadcn slot hierarchy, and that pattern reads well for a
// dense homelab admin. Extracting sub-primitives now would be a
// bigger API break than the token migration warrants.
//
// Migration is TOKEN-LEVEL:
//   bg-[var(--color-surface)]  → bg-card
//   text-[var(--color-ink)]    → text-card-foreground
//   border-[var(--color-line)] → border-border
//   hover:border-[var(--color-ink-4)] → hover:border-muted-foreground
//
// Padding + text-inheritance behaviour preserved: explicit
// text-card-foreground still guards against `.on-photo`'s white
// cascade when Card sits over the photoBg canvas.
//
// Padding default (fixed): Vue 3 coerces a *missing* boolean prop to
// `false`, so `props.padded !== false && 'p-7'` used to drop the
// default whenever a caller wrote a bare <Card> — the "p-7 by default"
// contract only fired for :padded="true". That's why the PackageDetail
// Overview cards rendered unpadded. withDefaults({ padded: true })
// gives the absent prop a real default, so bare <Card> is padded and
// only :padded="false" opts out. Pinned by Card.spec.ts.
const props = withDefaults(
  defineProps<{
    class?: HTMLAttributes['class'];
    padded?: boolean;
    hover?: boolean;
  }>(),
  { padded: true },
);

const cls = computed(() =>
  cn(
    // iter-3 theme-flip: explicit text-card-foreground so nested content
    // inside Card doesn't inherit `.on-photo`'s white cascade from a
    // photoBg route. Card is our canonical opaque surface.
    'bg-card text-card-foreground border border-border rounded-lg',
    // iter-3 padding audit: default card padding 24 → 28 (p-7). Keeps
    // rhythm with DashboardHome's p-8 override without doubling up.
    // Override to zero or larger via `padded: false` + class prop.
    props.padded !== false && 'p-7',
    props.hover && 'transition-colors duration-200 hover:border-muted-foreground',
    props.class,
  ),
);
</script>

<template>
  <div :class="cls">
    <slot />
  </div>
</template>
