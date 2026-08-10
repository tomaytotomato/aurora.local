<script setup lang="ts">
/**
 * Your own compose files. See docs/CUSTOM_STACK_DESIGN.md.
 *
 * The curated catalogue stays the default and the only recommended path.
 * This exists because the previous answer to "I'd like to run
 * Calibre-Web" was "edit the repo", which puts a first-time user into a
 * git checkout and makes their change the thing that breaks the next
 * pull.
 *
 * The screen is built around one idea: the failure mode is not bad YAML,
 * it is pasting something off a forum that quietly takes the box down.
 * So validation reports consequences, splits blocking from advisory, and
 * the advisory list is the part worth reading.
 */
import { computed, onMounted, ref } from 'vue';

import {
  CustomApi,
  canDeploy,
  dangerousWarnings,
  describeStack,
  stackTone,
  type CustomStack,
  type StackValidation,
} from '@/api/custom';
import { humanCopyForError } from '@/lib/http-error-copy';
import { relTime } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Badge from '@/components/ui/Badge.vue';
import JobLogPanel from '@/components/JobLogPanel.vue';
import SectionNav from '@/components/layout/SectionNav.vue';
import { Alert, AlertDescription, Button, Dialog, Input, Label, Skeleton } from '@/components/ui';

const stacks = ref<CustomStack[]>([]);
const loading = ref(true);
const loadErr = ref<string | null>(null);

const editorOpen = ref(false);
const editing = ref<CustomStack | null>(null);
const name = ref('');
const compose = ref('');
const validation = ref<StackValidation | null>(null);
const validating = ref(false);
const saving = ref(false);

const jobId = ref<string | null>(null);
const removeTarget = ref<CustomStack | null>(null);

const deployable = computed(() => canDeploy(validation.value));
const dangerous = computed(() => (validation.value ? dangerousWarnings(validation.value) : []));

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    stacks.value = await CustomApi.stacks();
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'your stacks', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openNew(): void {
  editing.value = null;
  name.value = '';
  compose.value = '';
  validation.value = null;
  editorOpen.value = true;
}

function openEdit(stack: CustomStack): void {
  editing.value = stack;
  name.value = stack.name;
  compose.value = stack.composeYaml;
  validation.value = null;
  editorOpen.value = true;
  void validate();
}

async function validate(): Promise<void> {
  validating.value = true;
  try {
    validation.value = await CustomApi.validate(compose.value);
  } catch {
    validation.value = null;
  } finally {
    validating.value = false;
  }
}

async function save(): Promise<void> {
  if (!name.value.trim() || !deployable.value) return;
  saving.value = true;
  try {
    if (editing.value) {
      const updated = await CustomApi.update(editing.value.id, {
        name: name.value.trim(),
        composeYaml: compose.value,
      });
      const i = stacks.value.findIndex((s) => s.id === updated.id);
      if (i >= 0) stacks.value[i] = updated;
    } else {
      stacks.value = [...stacks.value, await CustomApi.create(name.value.trim(), compose.value)];
    }
    editorOpen.value = false;
    toast({
      title: 'Saved',
      description: 'Nothing is running yet — deploy it when you are ready.',
      variant: 'success',
      duration: 4000,
    });
  } catch (e) {
    toast({
      title: "Couldn't save",
      description: humanCopyForError(e, { subject: 'this stack', action: 'save' }),
      variant: 'destructive',
    });
  } finally {
    saving.value = false;
  }
}

async function deploy(stack: CustomStack): Promise<void> {
  try {
    const { jobId: id } = await CustomApi.deploy(stack.id);
    jobId.value = id;
  } catch (e) {
    toast({
      title: "Couldn't deploy",
      description: humanCopyForError(e, { subject: 'this stack', action: 'deploy' }),
      variant: 'destructive',
    });
  }
}

async function stop(stack: CustomStack): Promise<void> {
  try {
    const { jobId: id } = await CustomApi.stop(stack.id);
    jobId.value = id;
  } catch (e) {
    toast({
      title: "Couldn't stop it",
      description: humanCopyForError(e, { subject: 'this stack', action: 'stop' }),
      variant: 'destructive',
    });
  }
}

async function confirmRemove(): Promise<void> {
  const stack = removeTarget.value;
  if (!stack) return;
  try {
    await CustomApi.remove(stack.id);
    stacks.value = stacks.value.filter((s) => s.id !== stack.id);
  } catch (e) {
    toast({
      title: "Couldn't remove it",
      description: humanCopyForError(e, { subject: 'this stack', action: 'remove' }),
      variant: 'destructive',
    });
  } finally {
    removeTarget.value = null;
  }
}
</script>

<template>
  <section>
    <div class="mb-6 on-photo">
      <h1 class="mb-3">Your own stacks</h1>
      <p class="max-w-2xl">Compose files you brought yourself. Aurora runs them and stays out of the way.</p>
    </div>

    <SectionNav
      :items="[
        { to: '/apps/catalogue', label: 'Apps' },
        { to: '/apps/core', label: 'Core' },
        { to: '/apps/custom', label: 'Your own' },
      ]"
      class="mb-6"
    />

    <!-- Said once, plainly, at the top. Not a modal nobody reads. -->
    <Card class="p-5 mb-6" data-test="custom-warning">
      <Alert variant="info">
        <AlertDescription>
          These are yours, not Aurora's. Nothing here is vetted, dependency-checked, backed up or
          included in the catalogue's port map. If one of them breaks the box, Aurora will tell you
          what it sees, but it cannot fix it for you.
        </AlertDescription>
      </Alert>
    </Card>

    <JobLogPanel
      v-if="jobId"
      :job-id="jobId"
      dismissible
      class="mb-6"
      @success="load"
      @failed="load"
      @dismiss="jobId = null"
    />

    <div class="flex justify-end mb-4">
      <Button data-test="custom-new" @click="openNew">Add a compose file</Button>
    </div>

    <Card v-if="loadErr" class="p-6" data-state="error" role="alert">
      <Alert variant="destructive"><AlertDescription>{{ loadErr }}</AlertDescription></Alert>
      <Button size="sm" variant="secondary" class="mt-3" @click="load">Try again</Button>
    </Card>

    <div v-else-if="loading" class="space-y-3" data-state="loading">
      <Skeleton v-for="n in 2" :key="`stack-sk-${n}`" class="h-24 w-full" />
    </div>

    <Card v-else-if="!stacks.length" class="py-16 text-center" data-state="empty">
      <p class="text-sm text-foreground mb-1">Nothing of your own yet.</p>
      <p class="text-xs text-muted-foreground max-w-md mx-auto">
        Paste a compose file and Aurora will tell you what it does before running it — which
        ports it wants, what it can reach, and what it will do to the box.
      </p>
    </Card>

    <ul v-else class="space-y-3" data-test="custom-list">
      <li
        v-for="stack in stacks"
        :key="stack.id"
        class="border border-border rounded-lg p-5 bg-card"
        :data-stack="stack.name"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <h3 class="card-title">{{ stack.name }}</h3>
              <Badge :tone="stackTone(stack.state)">{{ stack.state }}</Badge>
            </div>
            <p class="text-xs text-muted-foreground font-mono">
              {{ stack.containers.join(', ') || 'no services' }}
            </p>
            <p v-if="stack.lastDeployedAt" class="text-xs text-muted-foreground mt-1">
              Last deployed {{ relTime(stack.lastDeployedAt) }}.
            </p>
          </div>

          <div class="flex items-center gap-2 shrink-0">
            <Button size="sm" variant="secondary" @click="openEdit(stack)">Edit</Button>
            <Button
              v-if="stack.state === 'running'"
              size="sm"
              variant="secondary"
              @click="stop(stack)"
            >Stop</Button>
            <Button v-else size="sm" :data-test="`deploy-${stack.name}`" @click="deploy(stack)">Deploy</Button>
            <Button size="sm" variant="danger" @click="removeTarget = stack">Remove</Button>
          </div>
        </div>
      </li>
    </ul>

    <!-- Editor + validation report -->
    <Dialog :open="editorOpen" @update:open="editorOpen = $event">
      <template #title>{{ editing ? `Edit ${editing.name}` : 'Add a compose file' }}</template>

      <div class="space-y-4">
        <div>
          <Label for="stack-name">Name</Label>
          <Input id="stack-name" v-model="name" placeholder="calibre-web" class="max-w-64" />
        </div>

        <div>
          <Label for="stack-compose">compose.yml</Label>
          <textarea
            id="stack-compose"
            v-model="compose"
            rows="14"
            spellcheck="false"
            class="w-full rounded-md border border-input bg-card px-3 py-2 font-mono text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-test="stack-compose"
            @blur="validate"
          ></textarea>
          <Button size="sm" variant="secondary" class="mt-2" :disabled="validating" @click="validate">
            {{ validating ? 'Checking…' : 'Check it' }}
          </Button>
        </div>

        <div v-if="validation" data-test="stack-validation">
          <div class="eyebrow mb-2">What Aurora found</div>
          <p class="text-sm mb-3">{{ describeStack(validation) }}</p>

          <div v-if="validation.errors.length" class="space-y-2 mb-3">
            <Alert v-for="e in validation.errors" :key="e.message" variant="destructive">
              <AlertDescription>{{ e.message }}</AlertDescription>
            </Alert>
          </div>

          <!-- Lead with the ones that can do real damage. -->
          <div v-if="dangerous.length" class="space-y-2 mb-3" data-test="stack-dangerous">
            <Alert v-for="w in dangerous" :key="w.message" variant="destructive">
              <AlertDescription>{{ w.message }}</AlertDescription>
            </Alert>
          </div>

          <ul
            v-if="validation.warnings.length"
            class="text-xs text-muted-foreground space-y-1.5 list-disc pl-5"
          >
            <li v-for="w in validation.warnings.filter((x) => !dangerous.includes(x))" :key="w.message">
              {{ w.message }}
            </li>
          </ul>

          <p v-if="!validation.errors.length && !validation.warnings.length" class="text-sm text-muted-foreground">
            Nothing to flag. That is rarer than you would think.
          </p>
        </div>
      </div>

      <template #footer>
        <Button variant="secondary" @click="editorOpen = false">Cancel</Button>
        <Button :disabled="!deployable || !name.trim() || saving" data-test="stack-save" @click="save">
          {{ saving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Dialog>

    <Dialog :open="removeTarget !== null" @update:open="removeTarget = $event ? removeTarget : null">
      <template #title>Remove {{ removeTarget?.name }}?</template>
      <template #description>
        Its containers are stopped and removed. Anything in a named volume stays on disk; anything
        in the container does not.
      </template>
      <template #footer>
        <Button variant="secondary" @click="removeTarget = null">Cancel</Button>
        <Button variant="danger" data-test="stack-confirm-remove" @click="confirmRemove">Remove</Button>
      </template>
    </Dialog>
  </section>
</template>
