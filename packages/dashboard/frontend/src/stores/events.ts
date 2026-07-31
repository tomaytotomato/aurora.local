import { defineStore } from 'pinia';
import { ref, onScopeDispose } from 'vue';
import { openEventStream, type AuroraEvent } from '@/api/events';

// Ring buffer of recent events. Consumers subscribe via getters/computed.
const MAX = 200;

export const useEventsStore = defineStore('events', () => {
  const buffer = ref<AuroraEvent[]>([]);
  const connected = ref(false);
  let es: EventSource | null = null;

  function push(e: AuroraEvent): void {
    buffer.value.push(e);
    if (buffer.value.length > MAX) buffer.value.splice(0, buffer.value.length - MAX);
  }

  function connect(): void {
    if (es) return;
    es = openEventStream(
      (e) => {
        connected.value = true;
        push(e);
      },
      () => {
        connected.value = false;
      },
    );
  }

  function disconnect(): void {
    es?.close();
    es = null;
    connected.value = false;
  }

  onScopeDispose(disconnect);

  return { buffer, connected, connect, disconnect };
});
