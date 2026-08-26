import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { i18n } from './i18n';
import { OhVueIcon } from './plugins/icons';
import './assets/main.css';

async function bootstrap() {
  // Dev-only: start the in-browser API mock before the app makes its
  // first request. Guarded by VITE_USE_MOCKS (set by `npm run dev:mock`)
  // and behind a dynamic import so the mock code is tree-shaken out of
  // production builds entirely.
  if (import.meta.env.VITE_USE_MOCKS === '1') {
    const { startMockWorker } = await import('./mocks/browser');
    await startMockWorker();
  }

  const app = createApp(App);
  app.component('v-icon', OhVueIcon);
  app.use(createPinia());
  app.use(router);
  app.use(i18n);
  app.mount('#app');
}

void bootstrap();
