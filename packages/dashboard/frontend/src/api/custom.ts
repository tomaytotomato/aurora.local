// Custom stacks: running your own compose file on the box, clearly
// marked as your own problem. See docs/CUSTOM_STACK_DESIGN.md.
//
// Aurora's curated catalogue stays the default and the only recommended
// path. This exists because the alternative answer to "I'd like to run
// Calibre-Web" was "edit the repo", which puts a first-time user into a
// git checkout and makes their change the thing that breaks the next
// pull.
//
// The failure mode being designed against is not bad YAML. It is
// pasting something off a forum that quietly takes the box down, which
// is why the validation below reports consequences rather than syntax.

import { http } from './client';
import type { JobRef } from './packages';

export type StackState = 'draft' | 'running' | 'stopped' | 'failed';

export type IssueKind =
  // blocking
  | 'parse-error'
  | 'no-services'
  | 'port-conflict'
  | 'name-conflict'
  | 'privileged-port'
  // advisory
  | 'unpinned-image'
  | 'privileged'
  | 'docker-socket'
  | 'host-network'
  | 'no-restart-policy'
  | 'uncapped';

export interface StackIssue {
  kind: IssueKind;
  message: string;
  /** Which service in the file, when it is attributable to one. */
  service: string | null;
}

export interface StackValidation {
  valid: boolean;
  /** Must be fixed before it will deploy. */
  errors: StackIssue[];
  /** Deployable, with the consequence stated. */
  warnings: StackIssue[];
  services: string[];
  ports: number[];
  images: string[];
  volumes: string[];
}

export interface CustomStack {
  id: string;
  name: string;
  state: StackState;
  composeYaml: string;
  createdAt: string;
  lastDeployedAt: string | null;
  lastJobId: string | null;
  containers: string[];
}

/** Deployable when nothing blocking is outstanding. */
export function canDeploy(validation: StackValidation | null): boolean {
  return validation !== null && validation.errors.length === 0 && validation.services.length > 0;
}

/**
 * One line describing what the file contains, for the confirm step.
 * Counts rather than lists, because a compose file with nine services
 * would otherwise fill the dialog.
 */
export function describeStack(validation: StackValidation): string {
  const parts: string[] = [];
  const s = validation.services.length;
  parts.push(`${s} service${s === 1 ? '' : 's'}`);
  if (validation.ports.length) {
    parts.push(`${validation.ports.length} port${validation.ports.length === 1 ? '' : 's'}`);
  }
  if (validation.volumes.length) {
    parts.push(`${validation.volumes.length} volume${validation.volumes.length === 1 ? '' : 's'}`);
  }
  return parts.join(', ');
}

/** Badge tone for a stack's current state. */
export function stackTone(state: StackState): 'ok' | 'warn' | 'err' | 'neutral' {
  if (state === 'running') return 'ok';
  if (state === 'failed') return 'err';
  if (state === 'stopped') return 'warn';
  return 'neutral';
}

/**
 * The warnings that mean this container could do real damage, as
 * opposed to the ones that just mean it is untidy. Surfaced together so
 * the confirm step can lead with them.
 */
const DANGEROUS: readonly IssueKind[] = ['privileged', 'docker-socket', 'host-network'];

export function dangerousWarnings(validation: StackValidation): StackIssue[] {
  return validation.warnings.filter((w) => DANGEROUS.includes(w.kind));
}

export const CustomApi = {
  async stacks(): Promise<CustomStack[]> {
    const { data } = await http.get<CustomStack[]>('/custom/stacks');
    return data;
  },
  async validate(composeYaml: string): Promise<StackValidation> {
    const { data } = await http.post<StackValidation>('/custom/stacks/validate', { composeYaml });
    return data;
  },
  /** Saves without running. A stack that has never run is a useful state. */
  async create(name: string, composeYaml: string): Promise<CustomStack> {
    const { data } = await http.post<CustomStack>('/custom/stacks', { name, composeYaml });
    return data;
  },
  async update(id: string, patch: { name?: string; composeYaml?: string }): Promise<CustomStack> {
    const { data } = await http.put<CustomStack>(`/custom/stacks/${encodeURIComponent(id)}`, patch);
    return data;
  },
  async deploy(id: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/custom/stacks/${encodeURIComponent(id)}/deploy`);
    return data;
  },
  async stop(id: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/custom/stacks/${encodeURIComponent(id)}/stop`);
    return data;
  },
  async remove(id: string): Promise<void> {
    await http.delete(`/custom/stacks/${encodeURIComponent(id)}`);
  },
};
