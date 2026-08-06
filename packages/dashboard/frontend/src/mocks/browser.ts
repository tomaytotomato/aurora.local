// MSW browser worker. Started from main.ts only when VITE_USE_MOCKS is
// set (see `npm run dev:mock`). Never imported by a production build.

import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);

export async function startMockWorker(): Promise<void> {
  await worker.start({
    // Anything the handlers don't cover (static assets, the aurora photos)
    // passes straight through to the dev server.
    onUnhandledRequest: 'bypass',
    quiet: false,
  });
  // eslint-disable-next-line no-console
  console.info('[aurora] MSW mocks active — backend not required.');
}
