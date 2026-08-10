<script setup lang="ts">
/**
 * What adding this app will actually do to the box, shown before you add
 * it rather than discovered afterwards.
 *
 * Umbrel and TrueNAS both disclose this at install time and it is the
 * one thing their app stores do better than Aurora's catalogue: the
 * manifest has known all of it since the beginning — ports, other apps
 * it drags in, host roles it needs, how much memory it wants — and none
 * of it was ever shown at the moment it mattered.
 */
import { computed } from 'vue';

import type { PackageDetail } from '@/api/packages';
import { prettyPackageName } from '@/lib/packageName';

const props = defineProps<{ detail: PackageDetail }>();

interface ImpactRow {
  label: string;
  values: string[];
  /** Worth a second look before agreeing. */
  notable?: boolean;
}

function portLabel(p: Record<string, unknown>): string {
  const host = p.host ?? p.port ?? '?';
  const proto = p.proto ?? 'tcp';
  return `${host}/${proto}`;
}

function requirement(key: string): number | undefined {
  const raw = props.detail.requires?.[key];
  return typeof raw === 'number' ? raw : undefined;
}

const rows = computed<ImpactRow[]>(() => {
  const out: ImpactRow[] = [];

  const deps = (props.detail.dependsOn ?? []).filter((d) => d !== 'core');
  if (deps.length) {
    out.push({
      label: 'Also starts',
      values: deps.map(prettyPackageName),
      notable: true,
    });
  }

  const ports = props.detail.ports ?? [];
  if (ports.length) {
    out.push({ label: 'Takes ports', values: ports.map(portLabel) });
  }

  const vhosts = props.detail.vhosts ?? [];
  if (vhosts.length) {
    out.push({ label: 'Adds addresses', values: vhosts });
  }

  const roles = props.detail.requires?.host_roles;
  if (Array.isArray(roles) && roles.length) {
    out.push({
      label: 'Needs host setup',
      values: roles.map(String),
      notable: true,
    });
  }

  const ram = requirement('min_ram_mb');
  const disk = requirement('min_disk_gb');
  const needs: string[] = [];
  if (ram !== undefined) needs.push(ram >= 1024 ? `${(ram / 1024).toFixed(0)} GB memory` : `${ram} MB memory`);
  if (disk !== undefined) needs.push(`${disk} GB disk`);
  if (needs.length) out.push({ label: 'Wants at least', values: needs });

  if (props.detail.backup?.paths?.length) {
    out.push({ label: 'Stores data in', values: props.detail.backup.paths });
  }

  return out;
});
</script>

<template>
  <div data-test="package-impact">
    <dl v-if="rows.length" class="text-sm space-y-2.5">
      <div v-for="row in rows" :key="row.label" class="flex justify-between gap-6">
        <dt class="text-muted-foreground shrink-0">{{ row.label }}</dt>
        <dd class="text-right" :class="row.notable ? 'text-foreground' : 'font-mono text-xs'">
          {{ row.values.join(', ') }}
        </dd>
      </div>
    </dl>
    <p v-else class="text-sm text-muted-foreground">
      Nothing much: no extra apps, no ports, no host setup.
    </p>
  </div>
</template>
