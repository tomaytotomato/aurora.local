import { http, HttpResponse } from 'msw';

import type { OnboardingPatch } from '@/api/onboarding';

import { state } from '../state';
import { sseResponse } from '../sse';
import { packageSeeds } from '../fixtures/packages';
import { onboardingEnv } from '../fixtures/system';
import { noContent, nowIso } from './shared';

// Snake_case wire shape of GET /onboarding/plan (api/onboarding.ts keeps
// this type private, so the mock mirrors it locally).
interface PlanWire {
  packages_to_enable: string[];
  packages_to_disable: string[];
  vhosts: string[];
  ports: number[];
  warnings: string[];
}

function planFor(enabled: string[]): PlanWire {
  const seeds = packageSeeds.filter((s) => enabled.includes(s.summary.name));
  const vhosts = seeds.flatMap((s) => s.detail.vhosts ?? []);
  const ports = seeds.flatMap((s) => (s.summary.ports ?? []).map((p) => Number((p as { host?: number }).host)).filter(Boolean));
  const warnings: string[] = [];
  if (enabled.includes('media') && !enabled.includes('privacy')) {
    warnings.push('Media runs behind the privacy VPN; enable privacy too.');
  }
  if (enabled.includes('ai')) {
    warnings.push('AI (Ollama) is memory-hungry; 8 GB+ recommended.');
  }
  return {
    packages_to_enable: enabled,
    packages_to_disable: [],
    vhosts,
    ports,
    warnings,
  };
}

export const onboardingHandlers = [
  http.get('/api/onboarding', () => HttpResponse.json(state.onboarding)),
  http.patch('/api/onboarding', async ({ request }) => {
    const patch = (await request.json()) as OnboardingPatch;
    if (patch.domain !== undefined) state.onboarding.domain = patch.domain;
    if (patch.dns_mode !== undefined) state.onboarding.dns_mode = patch.dns_mode;
    if (patch.step !== undefined) state.onboarding.step = patch.step;
    if (patch.enabled_packages !== undefined) {
      state.onboarding.enabled_packages = patch.enabled_packages;
      state.enabled = new Set(patch.enabled_packages);
    }
    return HttpResponse.json(state.onboarding);
  }),
  http.get('/api/onboarding/status', () =>
    HttpResponse.json({
      complete: state.onboarding.complete,
      bootstrap_mode: state.onboarding.bootstrap_mode,
      step: state.onboarding.step,
    }),
  ),
  http.get('/api/onboarding/env', () => HttpResponse.json(onboardingEnv)),
  http.post('/api/onboarding/admin', async ({ request }) => {
    const { username } = (await request.json()) as { username: string; password: string };
    state.onboarding.admin_username = username;
    return noContent();
  }),
  http.get('/api/onboarding/plan', ({ request }) => {
    const enabledParam = new URL(request.url).searchParams.get('enabled');
    const enabled = enabledParam ? enabledParam.split(',').filter(Boolean) : [...state.enabled];
    return HttpResponse.json(planFor(enabled));
  }),
  http.post('/api/onboarding/install', () =>
    HttpResponse.json({
      applied: [...state.enabled],
      packages_to_start: [...state.enabled],
      packages_to_stop: [],
      host_command: 'sudo ./scripts/up.sh',
    }),
  ),
  http.post('/api/onboarding/launch', () =>
    HttpResponse.json(
      { job_id: 'mock-launch', packages: [...state.enabled], started_at: nowIso() },
      { status: 202 },
    ),
  ),
  http.get('/api/onboarding/launch/:id', ({ params }) =>
    HttpResponse.json({
      id: String(params.id),
      state: 'running',
      packages: [...state.enabled],
      started_at: nowIso(),
      finished_at: null,
      exit_code: null,
      failure_reason: null,
      tail: ['[mock] bringing up services…'],
    }),
  ),
  // SSE: stream a short launch log, then a terminal success `done` event.
  http.get('/api/onboarding/launch/:id/stream', () =>
    sseResponse((emit) => {
      const log = [
        'Creating network aurora_default',
        'Pulling core (caddy:2.8)…',
        'Starting aurora-caddy … done',
        'Starting aurora-privacy-adguard … done',
        'Starting aurora-monitoring-grafana … done',
        'All services healthy',
      ];
      let i = 0;
      const timer = setInterval(() => {
        if (i < log.length) {
          emit({ event: 'log', data: log[i++] });
          return;
        }
        emit({ event: 'done', data: JSON.stringify({ state: 'success', reason: null }) });
        clearInterval(timer);
      }, 700);
      return () => clearInterval(timer);
    }),
  ),
  http.post('/api/onboarding/complete', () => {
    state.onboarding.complete = true;
    state.onboarding.bootstrap_mode = false;
    state.onboarding.step = 'done';
    return noContent();
  }),
];
