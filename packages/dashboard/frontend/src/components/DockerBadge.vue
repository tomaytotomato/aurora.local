<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed } from 'vue';
import { cn } from '@/lib/utils';
import type { DockerStructure } from '@/api/packages';

// A small, always-honest label for how a package actually runs. This is
// owner-facing surface (the Apps management pages), so unlike onboarding
// it's fine — expected, even — to name Docker directly. `structure`
// comes from dockerStructureFor() in api/packages.ts; this component
// only renders whatever it's told, it doesn't guess.
const props = withDefaults(
  defineProps<{
    structure: DockerStructure;
    class?: HTMLAttributes['class'];
  }>(),
  {},
);

const label = computed(() => (props.structure === 'compose' ? 'Docker Compose' : 'Docker'));
const title = computed(() =>
  props.structure === 'compose'
    ? 'Runs as a multi-service Docker Compose project'
    : 'Runs in a single Docker container',
);
</script>

<template>
  <span
    :class="cn('inline-flex items-center gap-1.5 text-xs text-muted-foreground', props.class)"
    :title="title"
    data-test="docker-badge"
    :data-structure="structure"
  >
    <!-- Docker Compose's unofficial mascot is an octopus (it herds many
         containers), so a compose stack gets the octopus and a single
         container keeps the whale. Same 24×24 box, currentColor, so both
         sit identically inline. The octopus eyes are punched out with
         fill-rule="evenodd" rather than drawn in a second colour, which
         keeps it a single monochrome glyph. -->
    <svg v-if="structure === 'compose'" viewBox="0 0 24 24" class="h-3.5 w-3.5 shrink-0" fill="currentColor" fill-rule="evenodd" aria-hidden="true" data-slot="docker-octopus">
      <path d="M6 11 a6 6 0 0 1 12 0 v1 q-1.5 2.5 -3 0 q-1.5 2.5 -3 0 q-1.5 2.5 -3 0 q-1.5 2.5 -3 0 z M9.1 9.5 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 z M13.1 9.5 a0.9 0.9 0 1 0 1.8 0 a0.9 0.9 0 1 0 -1.8 0 z" />
    </svg>
    <svg v-else viewBox="0 0 24 24" class="h-3.5 w-3.5 shrink-0" fill="currentColor" aria-hidden="true" data-slot="docker-whale">
      <rect x="4" y="9.2" width="3" height="3" rx="0.4" />
      <rect x="8" y="9.2" width="3" height="3" rx="0.4" />
      <rect x="12" y="9.2" width="3" height="3" rx="0.4" />
      <rect x="8" y="5.2" width="3" height="3" rx="0.4" />
      <path d="M2 14.2c0-1 .8-1.8 1.8-1.8h16.4c1 0 1.8.9 1.6 1.9-.6 3-3.6 5.7-8.4 5.7h-2.8c-4.6 0-8-2.7-8.6-5.8z" />
    </svg>
    {{ label }}
  </span>
</template>
