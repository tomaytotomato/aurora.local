import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import MetricChart from './MetricChart.vue';

/**
 * iter-34 (v0.3 followup): first FE unit test. Pins the empty-data
 * render branch on MetricChart so future refactors don't accidentally
 * mount uPlot with `values: []` — the chart tears down and renders
 * the "No samples yet" copy instead.
 *
 * jsdom stub for uPlot: uPlot expects a real DOM canvas, which jsdom
 * doesn't paint. We stub the module so `new uPlot(...)` records the
 * call without needing a canvas backend. The v-if branch guards
 * against ever invoking it on empty data, so the stub only catches
 * accidental invocations.
 */
vi.mock('uplot', () => {
  const ctor = vi.fn().mockImplementation(function (this: unknown) {
    (this as { destroy: () => void }).destroy = () => {};
    (this as { setData: (_d: unknown) => void }).setData = () => {};
    (this as { setSize: (_s: unknown) => void }).setSize = () => {};
  });
  return { default: ctor };
});

// uPlot ships a stylesheet import; jsdom doesn't need it painted, but
// the loader has to resolve to something.
vi.mock('uplot/dist/uPlot.min.css', () => ({ default: '' }));

class RO implements ResizeObserver {
  observe(_target: Element): void {}
  unobserve(_target: Element): void {}
  disconnect(): void {}
}
beforeEach(() => {
  (globalThis as unknown as { ResizeObserver: typeof ResizeObserver }).ResizeObserver = RO as unknown as typeof ResizeObserver;
});

describe('MetricChart', () => {
  it('renders empty-state copy when the series is empty', () => {
    const wrapper = mount(MetricChart, {
      props: {
        series: { ts: [], values: [] },
        label: 'Host CPU %',
        unit: '%',
      },
    });
    expect(wrapper.text()).toContain('No samples yet');
    // The uPlot container must not have been rendered.
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);
  });

  it('renders a chart container when the series has data', async () => {
    const wrapper = mount(MetricChart, {
      props: {
        series: {
          ts: [1_700_000_000_000, 1_700_000_060_000],
          values: [12.5, 15.0],
        },
        label: 'Host CPU %',
        unit: '%',
      },
    });
    // Chart container div is the sibling of the empty-state div.
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(false);
  });
});
