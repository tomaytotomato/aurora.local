import { createI18n } from 'vue-i18n';

// v0.1: English only. Structure keeps the door open for later locales.
const messages = {
  en: {
    app: {
      name: 'Aurora',
      tagline: 'Admin plane for aurora.local.',
    },
  },
};

export const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages,
});
