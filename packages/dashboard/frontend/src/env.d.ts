/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** '1' turns on the in-browser MSW mock layer (see `npm run dev:mock`). */
  readonly VITE_USE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}
