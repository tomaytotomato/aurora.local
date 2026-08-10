<script setup lang="ts">
/**
 * The one row at the top of Overview that says what needs a person.
 *
 * Aurora already detected all of this — a failing drive, a backup that
 * stopped, parity going stale, an open finding, an update waiting — and
 * each one lived on its own page. Someone who never visits /disks never
 * learns their drive is reallocating sectors, which rather defeats the
 * point of watching for it.
 *
 * Renders nothing on a clean box. No "all good" banner: a permanent
 * reassurance badge is noise, and noise is what teaches people to stop
 * reading the row that matters.
 *
 * All of the deciding happens in lib/attention.ts. This is presentation.
 */
import { computed } from 'vue';

import { worstTone, type AttentionItem } from '@/lib/attention';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';

const props = defineProps<{ items: AttentionItem[] }>();

const tone = computed(() => worstTone(props.items));

const headline = computed(() => {
  const n = props.items.length;
  if (tone.value === 'err') return n === 1 ? 'One thing needs you' : `${n} things need you`;
  if (tone.value === 'warn') return n === 1 ? 'One thing to look at' : `${n} things to look at`;
  return n === 1 ? 'One thing worth knowing' : `${n} things worth knowing`;
});

const dotClass: Record<string, string> = {
  err: 'bg-destructive',
  warn: 'bg-warning',
  info: 'bg-info',
};
</script>

<template>
  <Card
    v-if="items.length"
    class="col-span-6 p-6"
    data-card="attention"
    data-test="attention-strip"
    :data-tone="tone"
    role="status"
  >
    <div class="flex items-baseline gap-3 mb-4">
      <h3 class="card-title">{{ headline }}</h3>
      <Badge v-if="tone" :tone="tone">{{ tone === 'err' ? 'action needed' : tone === 'warn' ? 'check' : 'info' }}</Badge>
    </div>

    <ul class="space-y-2.5">
      <li
        v-for="item in items"
        :key="item.id"
        class="flex items-start justify-between gap-4 text-sm"
        :data-attention="item.id"
        :data-tone="item.tone"
      >
        <span class="flex items-start gap-2.5 min-w-0">
          <span
            class="w-1.5 h-1.5 rounded-full mt-1.5 shrink-0"
            :class="dotClass[item.tone]"
            aria-hidden="true"
          />
          <span class="text-foreground">{{ item.text }}</span>
        </span>
        <router-link
          :to="item.to"
          class="text-sm text-muted-foreground no-underline hover:text-foreground whitespace-nowrap shrink-0"
        >{{ item.cta }} →</router-link>
      </li>
    </ul>
  </Card>
</template>
