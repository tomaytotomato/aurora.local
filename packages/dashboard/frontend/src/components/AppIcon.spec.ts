import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import AppIcon from './AppIcon.vue';

describe('AppIcon', () => {
  it('renders the logo when a src is given', () => {
    const w = mount(AppIcon, { props: { src: '/icons/jellyfin.svg', label: 'Media server' } });
    const img = w.find('img');
    expect(img.exists()).toBe(true);
    expect(img.attributes('src')).toBe('/icons/jellyfin.svg');
    expect(w.find('[data-slot="app-icon-fallback"]').exists()).toBe(false);
  });

  it('falls back to the initial when there is no src', () => {
    const w = mount(AppIcon, { props: { src: null, label: 'Photos' } });
    expect(w.find('img').exists()).toBe(false);
    expect(w.find('[data-slot="app-icon-fallback"]').text()).toBe('P');
  });

  it('falls back to the initial when the logo fails to load', async () => {
    const w = mount(AppIcon, { props: { src: '/icons/missing.svg', label: 'notes' } });
    await w.find('img').trigger('error');
    expect(w.find('img').exists()).toBe(false);
    expect(w.find('[data-slot="app-icon-fallback"]').text()).toBe('N');
  });

  it('uses the oh-vue-icons brand glyph when there is no SVG but a fallback icon is named', () => {
    const w = mount(AppIcon, { props: { src: null, label: 'Roundcube', fallbackIcon: 'si-roundcube' } });
    expect(w.find('img').exists()).toBe(false);
    expect(w.find('[data-slot="app-icon-ovi"]').exists()).toBe(true);
    expect(w.find('[data-icon="si-roundcube"]').exists()).toBe(true);
    expect(w.find('[data-slot="app-icon-fallback"]').exists()).toBe(false);
  });

  it('falls back past a missing SVG to the brand glyph, then to the initial', async () => {
    const withIcon = mount(AppIcon, {
      props: { src: '/icons/missing.svg', label: 'Roundcube', fallbackIcon: 'si-roundcube' },
    });
    await withIcon.find('img').trigger('error');
    expect(withIcon.find('[data-slot="app-icon-ovi"]').exists()).toBe(true);

    const noIcon = mount(AppIcon, { props: { src: null, label: 'Photos', fallbackIcon: null } });
    expect(noIcon.find('[data-slot="app-icon-fallback"]').text()).toBe('P');
  });
});
