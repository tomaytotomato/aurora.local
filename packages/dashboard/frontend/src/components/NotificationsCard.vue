<script setup lang="ts">
/**
 * Notification channels, on the Settings page.
 *
 * Aurora already knows when a service falls over, a backup fails, a
 * finding is raised or a drive starts going bad. Until this existed it
 * told nobody, so you found out by opening the dashboard, which for a
 * home server means three weeks later.
 *
 * The design point worth keeping: a test send reports what actually
 * happened. A channel that has quietly stopped working — somebody
 * deleted the Discord webhook, the ntfy topic changed — is worse than no
 * channel at all, because you believe you are covered. The failure state
 * gets a row of its own rather than a silent null.
 */
import { computed, onMounted, ref } from 'vue';

import {
  NOTIFY_EVENTS,
  NotificationsApi,
  defaultEvents,
  eventDescription,
  eventLabel,
  failingChannels,
  kindHelp,
  kindLabel,
  kindPlaceholder,
  validateChannel,
  type ChannelDraft,
  type ChannelKind,
  type NotificationChannel,
  type NotifyEvent,
} from '@/api/notifications';
import { humanCopyForError } from '@/lib/http-error-copy';
import { relTime } from '@/lib/utils';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import {
  Alert,
  AlertDescription,
  Badge,
  Checkbox,
  Dialog,
  Input,
  Label,
  Select,
  Skeleton,
} from '@/components/ui';

const channels = ref<NotificationChannel[]>([]);
const loading = ref(true);
const loadErr = ref<string | null>(null);

const testing = ref<string | null>(null);
const busy = ref<string | null>(null);
const removeTarget = ref<NotificationChannel | null>(null);

const failing = computed(() => failingChannels(channels.value));

async function load(): Promise<void> {
  loading.value = true;
  loadErr.value = null;
  try {
    channels.value = await NotificationsApi.channels();
  } catch (e) {
    loadErr.value = humanCopyForError(e, { subject: 'your notification channels', action: 'load' });
  } finally {
    loading.value = false;
  }
}

onMounted(load);

// ── Add / edit form ───────────────────────────────────────────────────
const formOpen = ref(false);
const editing = ref<NotificationChannel | null>(null);
const draft = ref<ChannelDraft>({ kind: 'ntfy', name: '', target: '', events: defaultEvents() });
const submitted = ref(false);
const saving = ref(false);

const errors = computed(() => validateChannel(draft.value));
const showError = (field: string): string | undefined => (submitted.value ? errors.value[field] : undefined);

const kindOptions = (['ntfy', 'discord', 'webhook'] as ChannelKind[]).map((k) => ({
  value: k,
  label: kindLabel(k),
}));

function openAdd(): void {
  editing.value = null;
  draft.value = { kind: 'ntfy', name: '', target: '', events: defaultEvents() };
  submitted.value = false;
  formOpen.value = true;
}

function openEdit(channel: NotificationChannel): void {
  editing.value = channel;
  draft.value = {
    kind: channel.kind,
    name: channel.name,
    target: channel.target,
    events: [...channel.events],
  };
  submitted.value = false;
  formOpen.value = true;
}

function toggleEvent(event: NotifyEvent, on: boolean): void {
  const next = new Set(draft.value.events);
  if (on) next.add(event);
  else next.delete(event);
  draft.value = { ...draft.value, events: NOTIFY_EVENTS.filter((e) => next.has(e)) };
}

async function save(): Promise<void> {
  submitted.value = true;
  if (Object.keys(errors.value).length) return;
  saving.value = true;
  try {
    if (editing.value) {
      const updated = await NotificationsApi.update(editing.value.id, draft.value);
      const i = channels.value.findIndex((c) => c.id === updated.id);
      if (i >= 0) channels.value[i] = updated;
    } else {
      channels.value = [...channels.value, await NotificationsApi.create(draft.value)];
    }
    formOpen.value = false;
    toast({ title: 'Saved', description: 'Aurora will use this from now on.', variant: 'success', duration: 3000 });
  } catch (e) {
    toast({
      title: "Couldn't save",
      description: humanCopyForError(e, { subject: 'this channel', action: 'save' }),
      variant: 'destructive',
    });
  } finally {
    saving.value = false;
  }
}

// ── Row actions ───────────────────────────────────────────────────────
async function sendTest(channel: NotificationChannel): Promise<void> {
  testing.value = channel.id;
  try {
    const { result, error } = await NotificationsApi.test(channel.id);
    const i = channels.value.findIndex((c) => c.id === channel.id);
    if (i >= 0) {
      channels.value[i] = {
        ...channels.value[i],
        lastSentAt: new Date().toISOString(),
        lastResult: result,
        lastError: error,
      };
    }
    if (result === 'ok') {
      toast({
        title: 'Sent',
        description: `Check ${channel.name} — it should be there now.`,
        variant: 'success',
        duration: 4000,
      });
    } else {
      toast({
        title: "That didn't arrive",
        description: error ?? 'The channel rejected the message.',
        variant: 'destructive',
      });
    }
  } catch (e) {
    toast({
      title: "Couldn't send the test",
      description: humanCopyForError(e, { subject: 'a test message', action: 'send' }),
      variant: 'destructive',
    });
  } finally {
    testing.value = null;
  }
}

async function toggleEnabled(channel: NotificationChannel): Promise<void> {
  busy.value = channel.id;
  try {
    const updated = await NotificationsApi.update(channel.id, { enabled: !channel.enabled });
    const i = channels.value.findIndex((c) => c.id === channel.id);
    if (i >= 0) channels.value[i] = updated;
  } catch (e) {
    toast({
      title: "Couldn't change that",
      description: humanCopyForError(e, { subject: 'this channel', action: 'update' }),
      variant: 'destructive',
    });
  } finally {
    busy.value = null;
  }
}

async function confirmRemove(): Promise<void> {
  const channel = removeTarget.value;
  if (!channel) return;
  try {
    await NotificationsApi.remove(channel.id);
    channels.value = channels.value.filter((c) => c.id !== channel.id);
  } catch (e) {
    toast({
      title: "Couldn't remove that",
      description: humanCopyForError(e, { subject: 'this channel', action: 'remove' }),
      variant: 'destructive',
    });
  } finally {
    removeTarget.value = null;
  }
}

function lastLine(channel: NotificationChannel): string {
  if (!channel.lastSentAt) return 'Nothing sent yet.';
  const when = relTime(channel.lastSentAt);
  return channel.lastResult === 'failed' ? `Last attempt ${when} failed.` : `Last sent ${when}.`;
}
</script>

<template>
  <Card class="p-8" data-card="notifications">
    <div class="flex items-baseline justify-between mb-4 gap-4">
      <div>
        <h3 class="card-title mb-1">Notifications</h3>
        <p class="text-xs text-muted-foreground mt-1">
          Aurora notices when an app stops, a backup fails or a drive starts going bad. This is
          where it tells you.
        </p>
      </div>
      <Button variant="secondary" size="sm" data-test="notify-add" @click="openAdd">Add</Button>
    </div>

    <!-- A channel that has stopped working is the state worth shouting
         about: you think you are covered and you are not. -->
    <Alert v-if="failing.length" variant="destructive" class="mb-4" data-test="notify-failing">
      <AlertDescription>
        {{ failing.length === 1 ? 'One channel is' : `${failing.length} channels are` }} switched on
        but not delivering. Until that is fixed, Aurora is detecting problems and telling nobody.
      </AlertDescription>
    </Alert>

    <Alert v-if="loadErr" variant="destructive" class="mb-3">
      <AlertDescription>{{ loadErr }}</AlertDescription>
    </Alert>

    <div v-else-if="loading" class="space-y-2 py-2" data-state="loading">
      <Skeleton v-for="n in 2" :key="`notify-sk-${n}`" class="h-16 w-full" />
    </div>

    <div
      v-else-if="!channels.length"
      class="text-xs text-muted-foreground py-4"
      data-state="empty"
      data-test="notify-empty"
    >
      Nowhere to send anything yet. ntfy is the quickest — pick an unguessable topic name, install
      the app, and you have alerts on your phone in about two minutes.
    </div>

    <ul v-else class="space-y-3" data-test="notify-list">
      <li
        v-for="channel in channels"
        :key="channel.id"
        class="border border-border rounded-md p-4"
        :data-channel="channel.id"
        :class="channel.enabled ? '' : 'opacity-60'"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <span class="font-medium text-sm">{{ channel.name }}</span>
              <Badge tone="neutral">{{ kindLabel(channel.kind) }}</Badge>
              <Badge v-if="!channel.enabled" tone="neutral">muted</Badge>
              <Badge v-else-if="channel.lastResult === 'failed'" tone="err">not delivering</Badge>
            </div>
            <p class="text-xs text-muted-foreground font-mono truncate">{{ channel.target }}</p>
            <p class="text-xs text-muted-foreground mt-1">
              {{ lastLine(channel) }}
              <span v-if="channel.lastError" class="text-destructive">{{ channel.lastError }}</span>
            </p>
            <p class="text-xs text-muted-foreground mt-1">
              {{ channel.events.length }} of {{ NOTIFY_EVENTS.length }} events
            </p>
          </div>

          <div class="flex items-center gap-2 shrink-0">
            <Button
              size="sm"
              variant="secondary"
              :disabled="testing === channel.id"
              :data-test="`notify-test-${channel.id}`"
              @click="sendTest(channel)"
            >{{ testing === channel.id ? 'Sending…' : 'Test' }}</Button>
            <Button size="sm" variant="secondary" @click="openEdit(channel)">Edit</Button>
            <Button
              size="sm"
              variant="secondary"
              :disabled="busy === channel.id"
              @click="toggleEnabled(channel)"
            >{{ channel.enabled ? 'Mute' : 'Unmute' }}</Button>
            <Button size="sm" variant="danger" @click="removeTarget = channel">Remove</Button>
          </div>
        </div>
      </li>
    </ul>

    <!-- Add / edit -->
    <Dialog :open="formOpen" @update:open="formOpen = $event">
      <template #title>{{ editing ? `Edit ${editing.name}` : 'Add somewhere to send alerts' }}</template>

      <form class="space-y-4" @submit.prevent="save">
        <div>
          <Label for="notify-kind">Kind</Label>
          <Select id="notify-kind" v-model="draft.kind" :options="kindOptions" />
          <p class="text-xs text-muted-foreground mt-1">{{ kindHelp(draft.kind) }}</p>
        </div>

        <div>
          <Label for="notify-name">Name</Label>
          <Input
            id="notify-name"
            v-model="draft.name"
            placeholder="Phone"
            :invalid="!!showError('name')"
          />
          <p v-if="showError('name')" class="text-xs text-destructive mt-1">{{ showError('name') }}</p>
        </div>

        <div>
          <Label for="notify-target">Where</Label>
          <Input
            id="notify-target"
            v-model="draft.target"
            :placeholder="kindPlaceholder(draft.kind)"
            :invalid="!!showError('target')"
          />
          <p v-if="showError('target')" class="text-xs text-destructive mt-1">{{ showError('target') }}</p>
        </div>

        <div>
          <Label>Tell me about</Label>
          <p v-if="showError('events')" class="text-xs text-destructive mb-2">{{ showError('events') }}</p>
          <ul class="space-y-2 mt-1">
            <li v-for="event in NOTIFY_EVENTS" :key="event" class="flex items-start gap-2.5">
              <Checkbox
                :id="`notify-event-${event}`"
                :model-value="draft.events.includes(event)"
                @update:model-value="toggleEvent(event, $event as boolean)"
              />
              <label :for="`notify-event-${event}`" class="text-sm leading-tight cursor-pointer">
                {{ eventLabel(event) }}
                <span class="block text-xs text-muted-foreground">{{ eventDescription(event) }}</span>
              </label>
            </li>
          </ul>
        </div>
      </form>

      <template #footer>
        <Button variant="secondary" @click="formOpen = false">Cancel</Button>
        <Button :disabled="saving" data-test="notify-save" @click="save">
          {{ saving ? 'Saving…' : 'Save' }}
        </Button>
      </template>
    </Dialog>

    <Dialog :open="removeTarget !== null" @update:open="removeTarget = $event ? removeTarget : null">
      <template #title>Remove {{ removeTarget?.name }}?</template>
      <template #description>
        Aurora will stop sending anything there. Nothing else changes — it still detects all the
        same things, it just goes quiet about them on this channel.
      </template>
      <template #footer>
        <Button variant="secondary" @click="removeTarget = null">Cancel</Button>
        <Button variant="danger" data-test="notify-confirm-remove" @click="confirmRemove">Remove</Button>
      </template>
    </Dialog>
  </Card>
</template>
