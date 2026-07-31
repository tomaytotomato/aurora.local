<script setup lang="ts">
import Card from '@/components/ui/Card.vue';
import Alert from '@/components/ui/Alert.vue';
import Badge from '@/components/ui/Badge.vue';

// v0.1 stub — real checks land with M4. Show the shape so the M4 backend has a target.
const stubbedFindings = [
  { severity: 'info', title: 'UFW enabled', body: 'Default incoming policy is DROP.', category: 'firewall' },
  { severity: 'warn', title: 'Backup not scheduled', body: 'No systemd timer for scripts/backup.sh. Schedule one from Backups.', category: 'backup' },
  { severity: 'info', title: 'fail2ban running', body: '0 current bans, last 24h.', category: 'firewall' },
  { severity: 'warn', title: 'Unattended-upgrades', body: 'Last run 12 days ago — expected weekly.', category: 'os' },
] as const;

const score = 78;
</script>

<template>
  <section>
    <div class="mb-10">
      <div class="eyebrow mb-2">Posture</div>
      <h1 class="mb-3">Security</h1>
      <p class="text-ink-3 max-w-2xl">
        Aurora runs a fixed set of opinionated checks against your host, containers, and
        secrets. Each finding has a fix — no silent nags.
      </p>
    </div>

    <div class="grid grid-cols-6 gap-4 mb-8">
      <Card class="col-span-2">
        <div class="eyebrow mb-1">Score</div>
        <div class="flex items-baseline gap-1">
          <div class="font-serif text-5xl leading-none text-ink">{{ score }}</div>
          <div class="text-ink-4 text-sm">/ 100</div>
        </div>
        <p class="text-xs text-ink-3 mt-3">Two warnings, no criticals.</p>
      </Card>
      <Card class="col-span-4">
        <div class="eyebrow mb-1">Coming with M4</div>
        <h3 class="mb-2">Deep scans</h3>
        <p class="text-sm text-ink-3">
          Weak-secret detection, exposed-port audit, TLS chain check, fail2ban ban
          history, backup age SLA, doctor.sh integration.
        </p>
      </Card>
    </div>

    <div class="border border-line rounded-lg divide-y divide-[var(--color-line-2)]">
      <div v-for="f in stubbedFindings" :key="f.title" class="px-5 py-4 flex items-start gap-4">
        <Badge :tone="f.severity === 'warn' ? 'warn' : 'info'">{{ f.severity }}</Badge>
        <div class="flex-1">
          <div class="text-sm font-medium text-ink">{{ f.title }}</div>
          <div class="text-sm text-ink-3 mt-0.5">{{ f.body }}</div>
        </div>
        <div class="text-xs text-ink-4 uppercase tracking-wider">{{ f.category }}</div>
      </div>
    </div>

    <Alert tone="info" class="mt-6">
      These findings are placeholders. The rules engine lands with M4.
    </Alert>
  </section>
</template>
