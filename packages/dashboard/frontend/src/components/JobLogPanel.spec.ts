import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

import { JobsApi, type JobStatus } from '@/api/jobs';

import JobLogPanel from './JobLogPanel.vue';

/**
 * The panel is the shared surface behind every long-running action, so
 * these cover the behaviour that matters when things go wrong: a stream
 * that fails mid-flight, a terminal frame that doesn't parse, and a
 * snapshot that 404s. Any of those leaving a spinner turning forever is
 * the bug this component exists to prevent.
 */

class FakeEventSource {
  static last: FakeEventSource | null = null;
  listeners: Record<string, Array<(e: MessageEvent) => void>> = {};
  closed = false;

  constructor() {
    FakeEventSource.last = this;
  }

  addEventListener(type: string, fn: (e: MessageEvent) => void): void {
    (this.listeners[type] ||= []).push(fn);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, data: string): void {
    for (const fn of this.listeners[type] ?? []) fn(new MessageEvent(type, { data }));
  }
}

function job(over: Partial<JobStatus> = {}): JobStatus {
  return {
    id: 'job-update-1',
    kind: 'update',
    target: 'jellyfin',
    state: 'running',
    startedAt: '2026-08-08T12:00:00Z',
    finishedAt: null,
    exitCode: null,
    failureCode: null,
    failureReason: null,
    tail: [],
    ...over,
  };
}

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(JobLogPanel, { props: { jobId: 'job-update-1', ...props } });
}

beforeEach(() => {
  FakeEventSource.last = null;
  vi.spyOn(JobsApi, 'get').mockResolvedValue(job());
  vi.spyOn(JobsApi, 'openStream').mockImplementation(() => new FakeEventSource() as unknown as EventSource);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('JobLogPanel', () => {
  it('renders nothing at all without a job, so a parent can leave it mounted', async () => {
    const w = mountPanel({ jobId: null });
    await flushPromises();
    expect(w.find('[data-test="job-log-panel"]').exists()).toBe(false);
  });

  it('takes its headline from the job kind rather than saying "job"', async () => {
    const w = mountPanel();
    await flushPromises();
    expect(w.find('[data-test="job-headline"]').text()).toBe('Updating…');
  });

  it('prefers an explicit label over the derived headline', async () => {
    const w = mountPanel({ label: 'Bringing Jellyfin up' });
    await flushPromises();
    expect(w.find('[data-test="job-headline"]').text()).toBe('Bringing Jellyfin up');
  });

  it('appends streamed lines to the log', async () => {
    const w = mountPanel();
    await flushPromises();
    FakeEventSource.last!.emit('log', 'Pulling jellyfin/jellyfin:10.10.0…');
    FakeEventSource.last!.emit('log', 'Pull complete');
    await flushPromises();
    const log = w.find('[data-test="job-log"]');
    expect(log.text()).toContain('Pulling jellyfin/jellyfin:10.10.0…');
    expect(log.text()).toContain('Pull complete');
  });

  it('ignores the snapshot tail so replayed lines are not counted twice', async () => {
    vi.spyOn(JobsApi, 'get').mockResolvedValue(job({ tail: ['Pulling…', 'Pull complete'] }));
    const w = mountPanel();
    await flushPromises();
    // The stream replays from the beginning; only its lines are shown.
    expect(w.find('[data-test="job-log"]').text()).toContain('Waiting for output…');
    FakeEventSource.last!.emit('log', 'Pulling…');
    await flushPromises();
    expect(w.find('[data-test="job-log"]').text()).not.toContain('Waiting for output…');
  });

  it('reports success upward and stops presenting itself as running', async () => {
    const w = mountPanel();
    await flushPromises();
    FakeEventSource.last!.emit(
      'done',
      JSON.stringify(job({ state: 'success', finishedAt: '2026-08-08T12:00:30Z', exitCode: 0 })),
    );
    await flushPromises();
    expect(w.emitted('success')).toHaveLength(1);
    expect(w.find('[data-test="job-headline"]').text()).toBe('Update complete');
    expect(w.attributes('data-state')).toBe('success');
    expect(FakeEventSource.last!.closed).toBe(true);
  });

  it('explains a failure from its code rather than echoing stderr, and offers a retry', async () => {
    const w = mountPanel();
    await flushPromises();
    FakeEventSource.last!.emit(
      'done',
      JSON.stringify(
        job({
          state: 'failed',
          finishedAt: '2026-08-08T12:00:30Z',
          exitCode: 1,
          failureCode: 'pull_rate_limited',
          failureReason: 'exit status 125: toomanyrequests',
        }),
      ),
    );
    await flushPromises();
    expect(w.emitted('failed')).toHaveLength(1);
    const banner = w.find('[data-test="job-failure-reason"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('rate limit');
    expect(banner.text()).not.toContain('exit status 125');
    await w.find('[data-test="job-retry"]').trigger('click');
    expect(w.emitted('retry')).toHaveLength(1);
  });

  it('opens the log on failure, because that is when anyone wants to read it', async () => {
    const w = mountPanel();
    await flushPromises();
    expect(w.find('details').attributes('open')).toBeUndefined();
    FakeEventSource.last!.emit('done', JSON.stringify(job({ state: 'failed', failureCode: 'container_crashed' })));
    await flushPromises();
    expect(w.find('details').attributes('open')).toBeDefined();
  });

  it('treats an unparseable terminal frame as a failure rather than spinning forever', async () => {
    const w = mountPanel();
    await flushPromises();
    FakeEventSource.last!.emit('done', 'not json at all');
    await flushPromises();
    expect(w.attributes('data-state')).toBe('failed');
    expect(w.find('[data-test="job-failure-reason"]').exists()).toBe(true);
  });

  it('shows an honest error when the job cannot be loaded at all', async () => {
    vi.spyOn(JobsApi, 'get').mockRejectedValue(new Error('boom'));
    const w = mountPanel();
    await flushPromises();
    expect(w.attributes('data-state')).toBe('error');
    expect(w.find('[data-test="job-log"]').exists()).toBe(false);
  });

  it('closes the stream when it goes away, so a route change does not leak it', async () => {
    const w = mountPanel();
    await flushPromises();
    const source = FakeEventSource.last!;
    w.unmount();
    expect(source.closed).toBe(true);
  });

  it('starts a fresh stream when the job id changes', async () => {
    const w = mountPanel();
    await flushPromises();
    const first = FakeEventSource.last!;
    await w.setProps({ jobId: 'job-enable-2' });
    await flushPromises();
    expect(first.closed).toBe(true);
    expect(FakeEventSource.last).not.toBe(first);
  });

  it('offers a Hide control only once the job has finished', async () => {
    const w = mountPanel({ dismissible: true });
    await flushPromises();
    expect(w.find('[data-test="job-dismiss"]').exists()).toBe(false);
    FakeEventSource.last!.emit('done', JSON.stringify(job({ state: 'success' })));
    await flushPromises();
    await w.find('[data-test="job-dismiss"]').trigger('click');
    expect(w.emitted('dismiss')).toHaveLength(1);
  });
});
