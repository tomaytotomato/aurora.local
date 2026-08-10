<script setup lang="ts">
/**
 * What sits in front of each vhost: rate limiting, geo-blocking and bot
 * filtering in Caddy.
 *
 * Aurora's premise includes "a sane security posture out of the box",
 * and until now that meant HTTPS and Authelia. Anything deliberately
 * left outside Authelia — Jellyfin is the standing example, because
 * native clients break under forward-auth — has had nothing at all in
 * front of it.
 *
 * The rule the UI follows: protection defaults on for names that resolve
 * from outside, and stays off for LAN-only names. Rate-limiting your own
 * laptop is pure downside.
 */
import { computed, onMounted, ref } from 'vue';

import {
  NetworkApi,
  protectionSummary,
  totalBlocked,
  unprotectedVhosts,
  type VhostProtection,
} from '@/api/network';
import { humanCopyForError } from '@/lib/http-error-copy';
import { relTime } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Badge, Checkbox, Input, Skeleton } from '@/components/ui';

const rows = ref<VhostProtection[]>([]);
const loading = ref(true);
const loadErr = ref<string | null>(null);
const busy = ref<string | null>(null);
const expanded = ref<string | null>(null);

const exposed = computed(() => rows.value.filter((v) => v.publiclyResolvable));
const unprotected = computed(() => unprotectedVhosts(rows.value));
const blocked = computed(() => totalBlocked(rows.value));

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    rows.value = await NetworkApi.protection();
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'edge protection', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(load);

async function patch(row: VhostProtection, change: Partial<VhostProtection>): Promise<void> {
  busy.value = row.vhost;
  try {
    const updated = await NetworkApi.setProtection(row.vhost, change);
    const i = rows.value.findIndex((v) => v.vhost === updated.vhost);
    if (i >= 0) rows.value[i] = updated;
  } catch (e) {
    toast({
      title: "Couldn't change that",
      description: humanCopyForError(e, { subject: 'this protection', action: 'update' }),
      variant: 'destructive',
    });
  } finally {
    busy.value = null;
  }
}

function setRateLimit(row: VhostProtection, enabled: boolean): void {
  void patch(row, { rateLimit: { ...row.rateLimit, enabled } });
}

function setRate(row: VhostProtection, value: string): void {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return;
  void patch(row, { rateLimit: { ...row.rateLimit, requestsPerMinute: Math.round(n) } });
}

function setGeoBlock(row: VhostProtection, enabled: boolean): void {
  void patch(row, {
    geoBlock: {
      enabled,
      // Turning it on with no list would block everything, which is
      // never what anyone means by clicking a checkbox.
      allowCountries: row.geoBlock.allowCountries.length ? row.geoBlock.allowCountries : ['GB'],
    },
  });
}

function setCountries(row: VhostProtection, value: string): void {
  const list = value
    .split(',')
    .map((c) => c.trim().toUpperCase())
    .filter(Boolean);
  void patch(row, { geoBlock: { ...row.geoBlock, allowCountries: list } });
}

function setBots(row: VhostProtection, enabled: boolean): void {
  void patch(row, { botDetection: enabled });
}

/** Turn everything sensible on for one vhost, in a single click. */
function protectAll(row: VhostProtection): void {
  void patch(row, {
    rateLimit: { enabled: true, requestsPerMinute: row.rateLimit.requestsPerMinute || 120 },
    geoBlock: {
      enabled: true,
      allowCountries: row.geoBlock.allowCountries.length ? row.geoBlock.allowCountries : ['GB'],
    },
    botDetection: true,
  });
}
</script>

<template>
  <Card class="p-8" data-card="edge-protection">
    <div class="flex items-baseline justify-between mb-4 gap-4">
      <div>
        <h3 class="card-title mb-1">At the front door</h3>
        <p class="text-xs text-muted-foreground mt-1">
          Rate limiting, geo-blocking and bot filtering in Caddy, for the
          {{ exposed.length }} name{{ exposed.length === 1 ? '' : 's' }} that resolve from outside
          your network.
          <template v-if="blocked > 0">
            {{ blocked.toLocaleString() }} requests turned away in the last day.
          </template>
        </p>
      </div>
    </div>

    <Alert v-if="unprotected.length" variant="destructive" class="mb-4" data-test="unprotected-warning">
      <AlertDescription>
        {{ unprotected.length === 1 ? 'One address is' : `${unprotected.length} addresses are` }}
        reachable from the internet with no sign-in and nothing in front of
        {{ unprotected.length === 1 ? 'it' : 'them' }}:
        <span class="font-mono">{{ unprotected.map((v) => v.vhost).join(', ') }}</span>.
      </AlertDescription>
    </Alert>

    <Alert v-if="loadErr" variant="destructive" class="mb-3">
      <AlertDescription>{{ loadErr }}</AlertDescription>
    </Alert>

    <div v-else-if="loading" class="space-y-2 py-2" data-state="loading">
      <Skeleton v-for="n in 3" :key="`prot-sk-${n}`" class="h-14 w-full" />
    </div>

    <div v-else-if="!rows.length" class="text-xs text-muted-foreground py-4" data-state="empty">
      No vhosts published yet.
    </div>

    <ul v-else class="space-y-2" data-test="protection-list">
      <li
        v-for="row in rows"
        :key="row.vhost"
        class="border border-border rounded-md"
        :data-vhost="row.vhost"
      >
        <button
          type="button"
          class="w-full text-left p-4 flex items-start justify-between gap-4"
          :aria-expanded="expanded === row.vhost"
          @click="expanded = expanded === row.vhost ? null : row.vhost"
        >
          <div class="min-w-0">
            <div class="flex items-center gap-2 mb-1 flex-wrap">
              <span class="font-mono text-sm">{{ row.vhost }}</span>
              <Badge v-if="row.publiclyResolvable" tone="warn">public</Badge>
              <Badge v-else tone="neutral">LAN only</Badge>
              <Badge v-if="row.authelia" tone="ok">sign-in</Badge>
            </div>
            <p class="text-xs text-muted-foreground">
              {{ protectionSummary(row) }}
              <template v-if="row.blocked24h > 0">
                · {{ row.blocked24h.toLocaleString() }} blocked today
              </template>
              <template v-if="row.lastBlockedAt">
                · last {{ relTime(row.lastBlockedAt) }}
              </template>
            </p>
          </div>
          <span class="text-xs text-muted-foreground shrink-0">
            {{ expanded === row.vhost ? 'Hide' : 'Change' }}
          </span>
        </button>

        <div v-if="expanded === row.vhost" class="px-4 pb-4 pt-1 border-t border-border space-y-4">
          <Alert v-if="!row.publiclyResolvable" variant="info">
            <AlertDescription>
              This name only resolves on your own network, so there is nothing here worth
              defending against. Turning these on mostly means rate-limiting your own laptop.
            </AlertDescription>
          </Alert>

          <div class="flex items-start gap-2.5">
            <Checkbox
              :id="`rate-${row.vhost}`"
              :model-value="row.rateLimit.enabled"
              :disabled="busy === row.vhost"
              @update:model-value="setRateLimit(row, $event as boolean)"
            />
            <div class="flex-1">
              <label :for="`rate-${row.vhost}`" class="text-sm cursor-pointer">Rate limit</label>
              <div v-if="row.rateLimit.enabled" class="flex items-center gap-2 mt-2">
                <Input
                  :model-value="String(row.rateLimit.requestsPerMinute)"
                  type="number"
                  min="1"
                  class="h-8 w-24 text-xs"
                  @update:model-value="setRate(row, String($event))"
                />
                <span class="text-xs text-muted-foreground">requests per minute, per address</span>
              </div>
            </div>
          </div>

          <div class="flex items-start gap-2.5">
            <Checkbox
              :id="`geo-${row.vhost}`"
              :model-value="row.geoBlock.enabled"
              :disabled="busy === row.vhost"
              @update:model-value="setGeoBlock(row, $event as boolean)"
            />
            <div class="flex-1">
              <label :for="`geo-${row.vhost}`" class="text-sm cursor-pointer">Only allow certain countries</label>
              <div v-if="row.geoBlock.enabled" class="mt-2">
                <Input
                  :model-value="row.geoBlock.allowCountries.join(', ')"
                  placeholder="GB, IE"
                  class="h-8 w-48 text-xs font-mono"
                  @change="setCountries(row, ($event.target as HTMLInputElement).value)"
                />
                <p class="text-xs text-muted-foreground mt-1">
                  Two-letter country codes, comma separated. Worth remembering before a holiday.
                </p>
              </div>
            </div>
          </div>

          <div class="flex items-start gap-2.5">
            <Checkbox
              :id="`bots-${row.vhost}`"
              :model-value="row.botDetection"
              :disabled="busy === row.vhost"
              @update:model-value="setBots(row, $event as boolean)"
            />
            <div>
              <label :for="`bots-${row.vhost}`" class="text-sm cursor-pointer">Filter obvious bots</label>
              <p class="text-xs text-muted-foreground">
                Turns away known scanners and empty user agents. Will not stop anything determined.
              </p>
            </div>
          </div>

          <Button
            v-if="row.publiclyResolvable"
            size="sm"
            variant="secondary"
            :disabled="busy === row.vhost"
            :data-test="`protect-all-${row.vhost}`"
            @click="protectAll(row)"
          >Turn all three on</Button>
        </div>
      </li>
    </ul>
  </Card>
</template>
