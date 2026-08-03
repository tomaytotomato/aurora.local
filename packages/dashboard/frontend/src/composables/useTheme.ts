import { ref, watch } from 'vue';

/**
 * Aurora theme composable — light / dark, persisted, `prefers-color-scheme`
 * aware on first load. Applies `data-theme` on the <html> element which
 * the CSS custom-property overrides in main.css key off.
 *
 * iter-3 V2: introduced as a standalone composable rather than a Pinia
 * store because there is no cross-view coordination (yet). If future
 * work adds per-user server-side theme persistence (see MEMORY.md
 * "theme upload / customise colours"), lift this to a store.
 */

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'auroraTheme';
const ATTR = 'data-theme';

function detectInitial(): Theme {
  if (typeof window === 'undefined') return 'light';
  const stored = window.localStorage?.getItem(STORAGE_KEY);
  if (stored === 'light' || stored === 'dark') return stored;
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  return prefersDark ? 'dark' : 'light';
}

// Shared reactive singleton so every consumer sees the same value.
const theme = ref<Theme>(detectInitial());

// Sync on load + on every subsequent change.
function apply(next: Theme): void {
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute(ATTR, next);
  }
  if (typeof window !== 'undefined') {
    try { window.localStorage?.setItem(STORAGE_KEY, next); } catch { /* private mode */ }
  }
}

apply(theme.value);
watch(theme, apply);

export function useTheme(): {
  theme: typeof theme;
  toggle: () => void;
  set: (t: Theme) => void;
} {
  return {
    theme,
    toggle: (): void => { theme.value = theme.value === 'dark' ? 'light' : 'dark'; },
    set: (t: Theme): void => { theme.value = t; },
  };
}
