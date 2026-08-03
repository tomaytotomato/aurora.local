<script setup lang="ts">
import { dismiss, useToastQueue } from '@/composables/useToast';
import Toast from './Toast.vue';

// Aurora Toaster (C10 iter-18).
// Container mounted at App level. Teleports the queue to <body> so
// toasts overlay every route/layout. Bottom-right stack; newest at
// bottom so the eye can rest on it. Callers never render this
// directly — App.vue mounts one.

const queue = useToastQueue();

function fire(id: number, cb?: () => void) {
  cb?.();
  dismiss(id);
}
</script>

<template>
  <Teleport to="body">
    <div
      class="pointer-events-none fixed bottom-4 right-4 z-[100] flex flex-col gap-2"
      role="region"
      aria-label="Notifications"
      data-slot="toaster"
    >
      <Toast
        v-for="t in queue.queue"
        :key="t.id"
        :title="t.title || undefined"
        :description="t.description"
        :variant="t.variant"
        :action-label="t.actionLabel"
        @dismiss="dismiss(t.id)"
        @action="fire(t.id, t.onAction)"
      />
    </div>
  </Teleport>
</template>
