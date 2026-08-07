import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import DockerBadge from './DockerBadge.vue';

describe('DockerBadge', () => {
  it('labels a single container as "Docker"', () => {
    const w = mount(DockerBadge, { props: { structure: 'container' } });
    expect(w.text()).toBe('Docker');
    expect(w.attributes('data-structure')).toBe('container');
  });

  it('labels a multi-service stack as "Docker Compose"', () => {
    const w = mount(DockerBadge, { props: { structure: 'compose' } });
    expect(w.text()).toBe('Docker Compose');
    expect(w.attributes('data-structure')).toBe('compose');
  });

  it('carries an honest title tooltip describing the structure', () => {
    const container = mount(DockerBadge, { props: { structure: 'container' } });
    expect(container.attributes('title')).toMatch(/single Docker container/);

    const compose = mount(DockerBadge, { props: { structure: 'compose' } });
    expect(compose.attributes('title')).toMatch(/multi-service Docker Compose/);
  });

  it('merges a caller class onto the root element', () => {
    const w = mount(DockerBadge, { props: { structure: 'container', class: 'text-foreground' } });
    expect(w.classes()).toContain('text-foreground');
  });
});
