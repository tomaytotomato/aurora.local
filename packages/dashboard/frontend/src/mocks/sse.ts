// Server-Sent Events for the mock layer. MSW intercepts the EventSource
// GET (it's a normal text/event-stream fetch under the hood) and we hand
// back a streaming ReadableStream body the browser's EventSource consumes.
//
// `pump` receives an `emit` function and returns an optional cleanup
// callback (clear your timers there); it runs on client disconnect.

export interface SseFrame {
  /** Named event; omit for the default (unnamed) message event. */
  event?: string;
  /** Payload. Multi-line strings are split into multiple `data:` lines. */
  data: string;
}

export function sseResponse(
  pump: (emit: (frame: SseFrame) => void) => (() => void) | void,
): Response {
  const encoder = new TextEncoder();
  let cleanup: (() => void) | void;

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const emit = ({ event, data }: SseFrame) => {
        let frame = '';
        if (event) frame += `event: ${event}\n`;
        for (const line of String(data).split('\n')) frame += `data: ${line}\n`;
        frame += '\n';
        try {
          controller.enqueue(encoder.encode(frame));
        } catch {
          // Controller already closed (client went away) — ignore.
        }
      };
      cleanup = pump(emit) ?? undefined;
    },
    cancel() {
      if (typeof cleanup === 'function') cleanup();
    },
  });

  return new Response(stream, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
    },
  });
}
