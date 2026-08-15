import { http } from './client';
import type { BackupAction } from './backup';

export type PackageStatus = 'running' | 'degraded' | 'stopped' | 'not-installed';
export type PackageCategory =
  | 'core'
  | 'privacy'
  | 'media'
  | 'storage'
  | 'productivity'
  | 'monitoring'
  | 'dev'
  | 'ai'
  | 'home-automation'
  | 'identity'
  // 'vpn' package (2026-08-06): an inbound WireGuard/OpenVPN server for
  // remote access, which is the opposite direction of 'privacy''s
  // outbound Gluetun tunnel — neither existing category fits.
  | 'network';

export interface EnvVarSpec {
  key: string;
  value?: string;
  example?: string;
  comment?: string;
  secret: boolean;
  required: boolean;
}

// iter-3 B4: shape matches what the backend Package record actually
// serialises (Jackson emits camelCase of the record fields). Prior to
// this the type claimed `status: PackageStatus` and `containers: number`
// which the wire never provided — so `p.status === 'running'` was
// always false and the dashboard header always read "0 running".
// Use `packageStatus(p)` to derive the label for the UI.
export interface PackageSummary {
  name: string;
  title?: string;
  category: PackageCategory;
  description: string;
  enabled: boolean;
  running: boolean;
  requires?: Record<string, unknown> | null;
  ports?: Array<Record<string, unknown>> | null;
  dependsOn?: string[] | null;
  /** Upstream project source repo (e.g. GitHub). Optional — the manifest
   * doesn't always carry one yet; render nothing when absent. */
  sourceUrl?: string | null;
  /** Upstream project homepage or docs site. Same optionality as above. */
  homepageUrl?: string | null;
}

/** Derive a status label from the on-wire booleans. */
export function packageStatus(p: PackageSummary): PackageStatus {
  if (!p.enabled) return 'not-installed';
  if (p.running) return 'running';
  return 'stopped';
}

/**
 * How long the frontend should wait after clicking Start before it flips
 * a row to "Couldn't start". Comes from the manifest's
 * `requires.start_budget_seconds`; defaults to 30 s. Media declares 180 s
 * (7 containers + first-run pulls); privacy 60 s (gluetun handshake).
 */
export function startBudgetMs(p: PackageSummary | undefined | null): number {
  const raw = p?.requires?.start_budget_seconds;
  const n = typeof raw === 'number' ? raw : Number(raw);
  if (Number.isFinite(n) && n > 0) return Math.min(600, n) * 1000;
  return 30_000;
}

/**
 * The `backup:` block from the package manifest: which paths this app
 * owns, and what has to happen before they can be snapshotted
 * consistently. Read-only in the dashboard — writing the block is a
 * manifest job. See docs/BACKUP_PAGE_DESIGN.md §6.
 */
export interface PackageBackupSpec {
  paths: string[];
  before: BackupAction[];
}

export interface PackageDetail extends PackageSummary {
  readme?: string;
  vhosts?: string[];
  homepageTiles?: number;
  envVars?: EnvVarSpec[];
  backup?: PackageBackupSpec | null;
}

/**
 * Per-container ceilings for one package: what the manifest declares,
 * what the operator has overridden it to, and what it is actually using
 * right now.
 *
 * The reason these exist at all: a home box has no spare capacity and
 * usually no swap, so one runaway container takes the whole machine down
 * and everything on it with it. A cap turns "the server fell over" into
 * "that container hit its limit", which is a far more useful thing to be
 * told at 11pm.
 */
export interface PackageResources {
  package: string;
  /** From the manifest. Null means the package ships uncapped. */
  defaultMemLimitMb: number | null;
  defaultCpus: number | null;
  /** Operator override. Null means the manifest default applies. */
  memLimitMb: number | null;
  cpus: number | null;
  /** Live usage across the package's containers. Null when not running. */
  memUsedMb: number | null;
  cpuPct: number | null;
}

/** The ceiling actually in force: override if set, otherwise the manifest. */
export function effectiveMemLimitMb(r: PackageResources): number | null {
  return r.memLimitMb ?? r.defaultMemLimitMb;
}

export function effectiveCpus(r: PackageResources): number | null {
  return r.cpus ?? r.defaultCpus;
}

/** True when the operator has moved either value off the manifest default. */
export function hasResourceOverride(r: PackageResources): boolean {
  return r.memLimitMb !== null || r.cpus !== null;
}

/**
 * How close this package is to its memory ceiling, 0-100, or null when
 * either figure is missing. Used to colour the bar, and deliberately not
 * clamped at some arbitrary "danger" threshold — being at 95% of a
 * generous limit is fine, and the operator can see the numbers.
 */
export function memHeadroomPct(r: PackageResources): number | null {
  const limit = effectiveMemLimitMb(r);
  if (limit === null || r.memUsedMb === null || limit <= 0) return null;
  return Math.max(0, Math.min(100, Math.round((r.memUsedMb / limit) * 100)));
}

/**
 * What every lifecycle verb that produces a log returns. Named here
 * because it matches the `JobRef` schema in openapi.yaml; stream it with
 * `JobsApi.openStream(jobId)`.
 */
export interface JobRef {
  jobId: string;
}

/**
 * Core packages are the platform baseline every other app relies on: the
 * reverse proxy + dashboard (`core`), the auth provider (`identity` /
 * Authelia — it fronts sign-on for everything), and LAN file sharing
 * (`storage` / Samba). They can be configured but never added/removed from
 * the box — see docs/PACKAGE_CONTRACT.md. This is an opinionated, curated
 * set (aurora ships a fixed baseline), so it's an explicit name list rather
 * than a category rule. A backend `core` manifest flag can supersede this
 * later; until then the frontend owns the list.
 */
const CORE_PACKAGES: ReadonlySet<string> = new Set(['core', 'identity', 'storage']);

export function isCorePackage(p: Pick<PackageSummary, 'name'>): boolean {
  return CORE_PACKAGES.has(p.name);
}

/** Only non-core packages can be enabled/disabled from the dashboard. */
export function isRemovable(p: Pick<PackageSummary, 'name'>): boolean {
  return !isCorePackage(p);
}

/** Split a catalogue into its core and non-core halves, in original order. */
export function splitByCore(list: PackageSummary[]): { core: PackageSummary[]; apps: PackageSummary[] } {
  const core: PackageSummary[] = [];
  const apps: PackageSummary[] = [];
  for (const p of list) (isCorePackage(p) ? core : apps).push(p);
  return { core, apps };
}

/**
 * The Catalogue page's Installed/Marketplace split — non-core packages
 * only (Core has its own page), partitioned on the same `enabled` flag
 * the old All/Enabled/Available filter used.
 */
export function splitCatalogue(list: PackageSummary[]): { installed: PackageSummary[]; marketplace: PackageSummary[] } {
  const { apps } = splitByCore(list);
  return {
    installed: apps.filter((p) => p.enabled),
    marketplace: apps.filter((p) => !p.enabled),
  };
}

export interface PackageLink {
  label: 'Source' | 'Docs';
  url: string;
}

/** Renderable {label, url} pairs for a package's upstream links — guards
 * against missing data so callers can `v-for` without an `v-if` per link. */
export function packageLinks(p: Pick<PackageSummary, 'sourceUrl' | 'homepageUrl'>): PackageLink[] {
  const links: PackageLink[] = [];
  if (p.sourceUrl) links.push({ label: 'Source', url: p.sourceUrl });
  if (p.homepageUrl) links.push({ label: 'Docs', url: p.homepageUrl });
  return links;
}

export type DockerStructure = 'container' | 'compose';

/**
 * Best-effort read of whether a package's compose.yml is one service or a
 * multi-service Docker Compose stack. The catalogue wire only gives us
 * dependsOn + ports, not the manifest itself, so this is a heuristic: a
 * package that depends on something beyond the implicit `core`, or that
 * exposes more than one host port, reads as a compose stack. Anything
 * weaker than that stays labelled as plain "Docker" rather than guessing
 * per-app — see docs/PACKAGE_CONTRACT.md for the real manifest shape.
 */
export function dockerStructureFor(p: Pick<PackageSummary, 'dependsOn' | 'ports'>): DockerStructure {
  const extraDeps = (p.dependsOn ?? []).filter((d) => d !== 'core');
  const portCount = (p.ports ?? []).length;
  return extraDeps.length > 0 || portCount > 1 ? 'compose' : 'container';
}

export const PackagesApi = {
  async list(): Promise<PackageSummary[]> {
    const { data } = await http.get<PackageSummary[]>('/packages');
    return data;
  },
  async get(name: string): Promise<PackageDetail> {
    const { data } = await http.get<PackageDetail>(`/packages/${name}`);
    return data;
  },
  async env(name: string, reveal = false): Promise<Record<string, string>> {
    const { data } = await http.get<Record<string, string>>(`/packages/${name}/env`, {
      params: reveal ? { reveal: 1 } : {},
    });
    return data;
  },
  async setEnv(name: string, vars: Record<string, string>): Promise<void> {
    await http.put(`/packages/${name}/env`, vars);
  },
  /** Install: enrol the package and start it. */
  async enable(name: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/packages/${name}/enable`);
    return data;
  },
  /**
   * Disable: stop the package's containers but leave it enrolled, so a
   * plain Start brings it back with no reinstall. Distinct from
   * `disable()` below, which un-enrols as well as stopping.
   */
  async stop(name: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/packages/${name}/stop`);
    return data;
  },
  /** Uninstall: stop the package's containers and un-enrol it. Data under data/<name> is preserved. */
  async disable(name: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/packages/${name}/disable`);
    return data;
  },
  async restart(name: string): Promise<void> {
    await http.post(`/packages/${name}/restart`);
  },
  async upgrade(name: string): Promise<JobRef> {
    const { data } = await http.post<JobRef>(`/packages/${name}/upgrade`);
    return data;
  },
  async resources(name: string): Promise<PackageResources> {
    const { data } = await http.get<PackageResources>(`/packages/${name}/resources`);
    return data;
  },
  /** Null on either field clears the override and restores the manifest default. */
  async setResources(
    name: string,
    patch: { memLimitMb: number | null; cpus: number | null },
  ): Promise<PackageResources> {
    const { data } = await http.put<PackageResources>(`/packages/${name}/resources`, patch);
    return data;
  },
};
