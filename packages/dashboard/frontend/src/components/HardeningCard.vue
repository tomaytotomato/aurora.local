<script setup lang="ts">
/**
 * Where the three outstanding hardening decisions actually stand.
 *
 * Image pinning, encrypted secrets and a proxied Docker socket have been
 * agreed for a while and recorded in the repo's plan. None of them has
 * ever been visible on the box, which is how a decision quietly becomes
 * a thing nobody did.
 *
 * This is state, not control. Each one is an Ansible run or a script,
 * not a button: pinning rewrites compose files, sops needs a key, and
 * putting a proxy in front of the socket changes how the dashboard talks
 * to Docker. What can honestly be offered here is where each stands and
 * what remains.
 *
 * There is deliberately no score. A number out of ten invites tuning the
 * number, and Aurora already removed one fabricated security score for
 * exactly that reason.
 */
import { computed, onMounted, ref } from 'vue';

import {
  HardeningApi,
  hardeningItems,
  hardeningTone,
  outstanding,
  type HardeningState,
} from '@/api/hardening';
import { humanCopyForError } from '@/lib/http-error-copy';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Badge, Skeleton } from '@/components/ui';

const state = ref<HardeningState | null>(null);
const loadErr = ref<string | null>(null);

const items = computed(() => (state.value ? hardeningItems(state.value) : []));
const remaining = computed(() => outstanding(items.value));

async function load(): Promise<void> {
  loadErr.value = null;
  try {
    state.value = await HardeningApi.state();
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'the hardening state', action: 'load' });
  }
}

onMounted(load);
</script>

<template>
  <Card class="p-8" data-card="hardening">
    <h3 class="card-title mb-1">Hardening</h3>
    <p class="text-xs text-muted-foreground mt-1 mb-4">
      Three things Aurora has decided to do and not finished.
      <template v-if="remaining.length">
        {{ remaining.length }} still outstanding.
      </template>
      <template v-else>All three are done.</template>
    </p>

    <Alert v-if="loadErr" variant="destructive">
      <AlertDescription>{{ loadErr }}</AlertDescription>
      <Button size="sm" variant="secondary" class="mt-3" @click="load">Try again</Button>
    </Alert>

    <div v-else-if="!state" class="space-y-2" data-state="loading">
      <Skeleton v-for="n in 3" :key="`hard-sk-${n}`" class="h-16 w-full" />
    </div>

    <ul v-else class="space-y-4" data-test="hardening-list">
      <li v-for="item in items" :key="item.id" :data-hardening="item.id" :data-status="item.status">
        <div class="flex items-center gap-2 mb-1">
          <span class="text-sm font-medium">{{ item.title }}</span>
          <Badge :tone="hardeningTone(item.status)">{{ item.status }}</Badge>
        </div>
        <p class="text-xs text-foreground">{{ item.detail }}</p>
        <p class="text-xs text-muted-foreground mt-0.5">{{ item.rationale }}</p>
      </li>
    </ul>

    <p v-if="state" class="text-xs text-muted-foreground mt-5 pt-4 border-t border-border">
      None of these has a button, on purpose. Each one is an Ansible run or a script that rewrites
      files on disk, and a dashboard that quietly rewrites your compose files is a dashboard you
      cannot reason about.
    </p>
  </Card>
</template>
