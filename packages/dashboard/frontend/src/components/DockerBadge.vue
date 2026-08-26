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
    <!-- A single container gets the real Docker whale (Simple Icons). A
         compose stack has no official brand mark, so it gets a stacked-
         layers glyph — clearer than reusing the whale, and honest about
         there being no Compose logo. Both are currentColor, 3.5×3.5, so
         they sit identically inline. -->
    <v-icon
      :name="structure === 'compose' ? 'fa-layer-group' : 'si-docker'"
      class="h-3.5 w-3.5 shrink-0"
      :data-slot="structure === 'compose' ? 'docker-layers' : 'docker-whale'"
    />
    {{ label }}
  </span>
</template>
