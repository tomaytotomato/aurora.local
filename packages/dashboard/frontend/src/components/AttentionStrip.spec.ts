import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';

import type { AttentionItem } from '@/lib/attention';

import AttentionStrip from './AttentionStrip.vue';

function mountStrip(items: AttentionItem[]) {
  return mount(AttentionStrip, {
    props: { items },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
}

const item = (over: Partial<AttentionItem> & { id: string }): AttentionItem => ({
  tone: 'warn',
  text: 'Something is up',
  to: '/disks',
  cta: 'Disks',
  ...over,
});

describe('AttentionStrip', () => {
  it('renders nothing at all on a clean box', () => {
    // No "all good" banner on purpose: permanent reassurance is noise,
    // and noise is what teaches people to stop reading the row.
    const w = mountStrip([]);
    expect(w.find('[data-test="attention-strip"]').exists()).toBe(false);
    expect(w.text()).toBe('');
  });

  it('takes its overall tone from the worst item present', () => {
    const w = mountStrip([item({ id: 'a', tone: 'info' }), item({ id: 'b', tone: 'err' })]);
    expect(w.find('[data-test="attention-strip"]').attributes('data-tone')).toBe('err');
  });

  it('escalates its headline with the tone', () => {
    expect(mountStrip([item({ id: 'a', tone: 'err' })]).text()).toContain('One thing needs you');
    expect(mountStrip([item({ id: 'a', tone: 'warn' })]).text()).toContain('One thing to look at');
    expect(mountStrip([item({ id: 'a', tone: 'info' })]).text()).toContain('One thing worth knowing');
  });

  it('counts correctly once there is more than one', () => {
    const w = mountStrip([item({ id: 'a', tone: 'err' }), item({ id: 'b', tone: 'err' })]);
    expect(w.text()).toContain('2 things need you');
  });

  it('renders one row per item, each with somewhere to go', () => {
    const w = mountStrip([
      item({ id: 'disk:sdc', text: '/dev/sdc is failing', to: '/disks', cta: 'Disks' }),
      item({ id: 'updates', tone: 'info', text: '2 apps have an update waiting', to: '/apps', cta: 'Apps' }),
    ]);
    const rows = w.findAll('[data-attention]');
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain('/dev/sdc is failing');
    expect(rows[1].text()).toContain('Apps');
  });

  it('announces itself politely rather than as an alert', () => {
    // role="status" not role="alert": this is a standing summary, not an
    // interruption, and it is present on every page load.
    expect(mountStrip([item({ id: 'a' })]).find('[data-test="attention-strip"]').attributes('role')).toBe('status');
  });
});
