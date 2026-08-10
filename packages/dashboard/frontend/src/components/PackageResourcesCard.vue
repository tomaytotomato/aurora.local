<script setup lang="ts">
/**
 * Memory and CPU ceilings for one app, against what it is actually
 * using.
 *
 * Why caps at all: a home box has no spare capacity and usually no swap,
 * so one runaway container takes the whole machine down and everything
 * on it with it. With a limit in place the kernel kills the offending
 * container instead of the OOM killer picking a victim at random, which
 * turns "the server fell over" into "Ollama hit its limit" — a far more
 * useful thing to be told at 11pm.
 *
 * Defaults come from the package manifest. Anything small and
 * well-behaved ships uncapped, and this card says so rather than
 * inventing a number.
 */
import { computed, onMounted, ref, watch } from 'vue';

import {
  PackagesApi,
  effectiveCpus,
  effectiveMemLimitMb,
  hasResourceOverride,
  memHeadroomPct,
  type PackageResources,
} from '@/api/packages';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import Progress from '@/components/ui/Progress.vue';
import { Alert, AlertDescription, Button, Dialog, Input, Label, Skeleton } from '@/components/ui';

const props = defineProps<{ package: string }>();

const resources = ref<PackageResources | null>(null);
const loadErr = ref<string | null>(null);
const editing = ref(false);
const saving = ref(false);

const memField = ref('');
const cpuField = ref('');

const memLimit = computed(() => (resources.value ? effectiveMemLimitMb(resources.value) : null));
const cpus = computed(() => (resources.value ? effectiveCpus(resources.value) : null));
const headroom = computed(() => (resources.value ? memHeadroomPct(resources.value) : null));
const overridden = computed(() => (resources.value ? hasResourceOverride(resources.value) : false));

async function load(): Promise<void> {
  loadErr.value = null;
  try {
    resources.value = await PackagesApi.resources(props.package);
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: "this app's limits", action: 'load' });
  }
}

onMounted(load);
watch(() => props.package, load);

function openEdit(): void {
  memField.value = resources.value?.memLimitMb != null ? String(resources.value.memLimitMb) : '';
  cpuField.value = resources.value?.cpus != null ? String(resources.value.cpus) : '';
  editing.value = true;
}

function parseField(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  return Number.isFinite(n) && n > 0 ? n : null;
}

async function save(): Promise<void> {
  saving.value = true;
  try {
    resources.value = await PackagesApi.setResources(props.package, {
      memLimitMb: parseField(memField.value),
      cpus: parseField(cpuField.value),
    });
    editing.value = false;
    toast({
      title: 'Saved',
      description: 'The new limits apply next time this app restarts.',
      variant: 'success',
      duration: 4000,
    });
  } catch (e) {
    toast({
      title: "Couldn't save",
      description: humanCopyForError(e, { subject: 'these limits', action: 'save' }),
      variant: 'destructive',
    });
  } finally {
    saving.value = false;
  }
}

function memText(mb: number | null): string {
  if (mb === null) return 'uncapped';
  return mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 === 0 ? 0 : 1)} GB` : `${mb} MB`;
}
</script>

<template>
  <Card data-test="package-resources-card">
    <div class="flex items-start justify-between gap-4 mb-3">
      <div>
        <div class="eyebrow mb-1">Limits</div>
        <h3>Memory and CPU</h3>
      </div>
      <Button v-if="resources" size="sm" variant="secondary" data-test="resources-edit" @click="openEdit">
        Change
      </Button>
    </div>

    <Alert v-if="loadErr" variant="destructive"><AlertDescription>{{ loadErr }}</AlertDescription></Alert>

    <div v-else-if="!resources" class="space-y-2" data-state="loading">
      <Skeleton class="h-4 w-32" />
      <Skeleton class="h-2 w-full" />
    </div>

    <div v-else>
      <div class="flex items-baseline gap-2 mb-1">
        <span class="text-sm">
          <template v-if="resources.memUsedMb !== null">
            {{ memText(resources.memUsedMb) }} of {{ memText(memLimit) }}
          </template>
          <template v-else>{{ memText(memLimit) }} ceiling</template>
        </span>
        <Badge v-if="overridden" tone="info">custom</Badge>
        <Badge v-else-if="memLimit === null" tone="neutral">uncapped</Badge>
      </div>

      <Progress v-if="headroom !== null" :value="headroom" class="mb-3" />

      <dl class="text-xs space-y-1.5">
        <div class="flex justify-between">
          <dt class="text-muted-foreground">CPU ceiling</dt>
          <dd class="font-mono">{{ cpus === null ? 'uncapped' : `${cpus} cores` }}</dd>
        </div>
        <div v-if="resources.cpuPct !== null" class="flex justify-between">
          <dt class="text-muted-foreground">Using now</dt>
          <dd class="font-mono">{{ resources.cpuPct }}%</dd>
        </div>
      </dl>

      <p v-if="memLimit === null" class="text-xs text-muted-foreground mt-3">
        This app ships without a ceiling, which is fine for anything small and well behaved. Set
        one if it ever starts misbehaving.
      </p>
    </div>

    <Dialog :open="editing" @update:open="editing = $event">
      <template #title>Limits for this app</template>
      <template #description>
        Leave a field empty to go back to what the app ships with
        ({{ memText(resources?.defaultMemLimitMb ?? null) }},
        {{ resources?.defaultCpus === null || resources?.defaultCpus === undefined ? 'uncapped' : `${resources.defaultCpus} cores` }}).
      </template>

      <div class="space-y-4">
        <div>
          <Label for="res-mem" hint="MB">Memory ceiling</Label>
          <Input id="res-mem" v-model="memField" type="number" min="64" placeholder="e.g. 4096" />
          <p class="text-xs text-muted-foreground mt-1">
            The container is killed when it goes past this, rather than the whole box running out
            of memory and the kernel picking something at random.
          </p>
        </div>
        <div>
          <Label for="res-cpu" hint="cores">CPU ceiling</Label>
          <Input id="res-cpu" v-model="cpuField" type="number" min="0.1" step="0.1" placeholder="e.g. 2" />
          <p class="text-xs text-muted-foreground mt-1">
            Fractions are allowed. 0.5 means half a core's worth.
          </p>
        </div>
      </div>

      <template #footer>
        <Button variant="secondary" @click="editing = false">Cancel</Button>
        <Button :disabled="saving" data-test="resources-save" @click="save">
          {{ saving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Dialog>
  </Card>
</template>
