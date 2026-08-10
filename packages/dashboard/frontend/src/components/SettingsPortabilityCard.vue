<script setup lang="ts">
/**
 * Export and re-import the box's configuration.
 *
 * The case this exists for: a reinstall, a move to better hardware, or a
 * disk that died. Every one of those currently means doing the wizard
 * again from memory and remembering which nine things you changed
 * afterwards.
 *
 * The file carries no secrets. .env values stay on the box, which is
 * what makes it safe to keep in an ordinary backup — and also why an
 * import can never be the whole story, so the result says plainly what
 * it could not restore.
 *
 * Import previews first. An import that silently enables nine packages
 * is not something anyone should discover after the fact.
 */
import { ref } from 'vue';

import { SystemApi, type ImportResult, type SettingsExport } from '@/api/system';
import { humanCopyForError } from '@/lib/http-error-copy';
import { toast } from '@/composables/useToast';
import Card from '@/components/ui/Card.vue';
import Button from '@/components/ui/Button.vue';
import { Alert, AlertDescription, Dialog } from '@/components/ui';

const exporting = ref(false);
const pending = ref<SettingsExport | null>(null);
const previewResult = ref<ImportResult | null>(null);
const importing = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

async function doExport(): Promise<void> {
  exporting.value = true;
  try {
    const payload = await SystemApi.exportSettings();
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `aurora-settings-${payload.exportedAt.slice(0, 10)}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (e) {
    toast({
      title: "Couldn't export",
      description: humanCopyForError(e, { subject: 'your settings', action: 'export' }),
      variant: 'destructive',
    });
  } finally {
    exporting.value = false;
  }
}

function pickFile(): void {
  fileInput.value?.click();
}

async function onFile(event: Event): Promise<void> {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const payload = JSON.parse(await file.text()) as SettingsExport;
    pending.value = payload;
    previewResult.value = await SystemApi.importSettings(payload, true);
  } catch (e) {
    toast({
      title: "Couldn't read that file",
      description: humanCopyForError(e, { subject: 'this file', action: 'read' }),
      variant: 'destructive',
    });
    pending.value = null;
  } finally {
    // Let the same file be picked again after a cancel.
    (event.target as HTMLInputElement).value = '';
  }
}

async function applyImport(): Promise<void> {
  if (!pending.value) return;
  importing.value = true;
  try {
    const result = await SystemApi.importSettings(pending.value, false);
    toast({
      title: 'Imported',
      description: `${result.applied.length} things restored. Secrets still need filling in.`,
      variant: 'success',
      duration: 5000,
    });
    pending.value = null;
    previewResult.value = null;
  } catch (e) {
    toast({
      title: "Couldn't import",
      description: humanCopyForError(e, { subject: 'these settings', action: 'import' }),
      variant: 'destructive',
    });
  } finally {
    importing.value = false;
  }
}

function cancel(): void {
  pending.value = null;
  previewResult.value = null;
}
</script>

<template>
  <Card class="p-8" data-card="settings-portability">
    <h3 class="card-title mb-1">Take it with you</h3>
    <p class="text-xs text-muted-foreground mt-1 mb-4">
      A copy of how this box is set up: which apps, which addresses, the backup schedule, where
      alerts go. No passwords or keys — those stay here, which is what makes the file safe to
      keep alongside your other backups.
    </p>

    <div class="flex items-center gap-2">
      <Button variant="secondary" size="sm" :disabled="exporting" data-test="settings-export" @click="doExport">
        {{ exporting ? 'Preparing…' : 'Export' }}
      </Button>
      <Button variant="secondary" size="sm" data-test="settings-import" @click="pickFile">Import</Button>
      <input
        ref="fileInput"
        type="file"
        accept="application/json,.json"
        class="hidden"
        @change="onFile"
      />
    </div>

    <Dialog :open="pending !== null" @update:open="pending = $event ? pending : null">
      <template #title>Import these settings?</template>
      <template #description>
        This is a preview. Nothing has changed yet.
      </template>

      <div v-if="previewResult" class="space-y-4" data-test="import-preview">
        <div>
          <div class="eyebrow mb-2">Would restore</div>
          <ul class="text-sm list-disc pl-5 space-y-1">
            <li v-for="line in previewResult.applied" :key="line">{{ line }}</li>
          </ul>
        </div>

        <Alert variant="info">
          <AlertDescription>
            <span v-for="line in previewResult.skipped" :key="line">{{ line }}</span>
          </AlertDescription>
        </Alert>
      </div>

      <template #footer>
        <Button variant="secondary" @click="cancel">Cancel</Button>
        <Button :disabled="importing" data-test="import-apply" @click="applyImport">
          {{ importing ? 'Importing…' : 'Import' }}
        </Button>
      </template>
    </Dialog>
  </Card>
</template>
