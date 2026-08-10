// Disk fixtures: a plausible four-drive homelab box.
//
// The default state is deliberately "attention" rather than "all green".
// A page that only ever renders healthy is a page nobody has checked the
// warning path of, and the warning path is the entire reason this page
// exists. Two things are off out of the box:
//
//   • /mnt/disk2 has 3 reallocated sectors while SMART still says PASSED
//     (the exact case the page is written to catch)
//   • parity was last synced 4 days ago against a 3-day threshold
//
// EDIT ME:
//   • set REALLOCATED_ON_DISK2 = 0        → that drive goes green
//   • set PARITY_SYNC_DAYS_AGO = 0        → parity goes fresh
//   • set PARITY_STATE = 'aborted'        → the deletion-guard banner
//   • initialPool().configured = false    → the single-disk 'no-pool' view

import type { Disk, DiskSmart, Parity, ParityRunState, Pool } from '@/api/disks';

const GIB = 1024 ** 3;
const TIB = 1024 ** 4;

const REALLOCATED_ON_DISK2 = 3;
const PARITY_SYNC_DAYS_AGO = 4;
const PARITY_STATE: ParityRunState = 'ok';

function daysAgo(d: number): string {
  return new Date(Date.now() - d * 86_400_000).toISOString();
}

export function initialDisks(): Disk[] {
  return [
    {
      id: 'ata-Samsung_SSD_870_EVO_500GB_S5Y7NJ0R',
      device: '/dev/sda',
      model: 'Samsung SSD 870 EVO 500GB',
      serial: 'S5Y7NJ0R203514',
      sizeBytes: Math.round(465.8 * GIB),
      role: 'system',
      mountpoint: '/',
      filesystem: 'ext4',
      usedBytes: Math.round(214 * GIB),
      health: 'passed',
      temperatureC: 34,
      powerOnHours: 14_602,
      reallocatedSectors: 0,
      pendingSectors: 0,
      lastSelfTestAt: daysAgo(1),
      lastSelfTestResult: 'Completed without error',
    },
    {
      id: 'ata-WDC_WD80EFZX-68UW8N0_VKHA1B2C',
      device: '/dev/sdb',
      model: 'WDC WD80EFZX-68UW8N0',
      serial: 'VKHA1B2C',
      sizeBytes: 8 * TIB,
      role: 'data',
      mountpoint: '/mnt/disk1',
      filesystem: 'ext4',
      usedBytes: Math.round(4.9 * TIB),
      health: 'passed',
      temperatureC: 38,
      powerOnHours: 31_204,
      reallocatedSectors: 0,
      pendingSectors: 0,
      lastSelfTestAt: daysAgo(2),
      lastSelfTestResult: 'Completed without error',
    },
    {
      id: 'ata-WDC_WD80EFZX-68UW8N0_VKHD9F1G',
      device: '/dev/sdc',
      model: 'WDC WD80EFZX-68UW8N0',
      serial: 'VKHD9F1G',
      sizeBytes: 8 * TIB,
      role: 'data',
      mountpoint: '/mnt/disk2',
      filesystem: 'ext4',
      // Nearly full: below mergerfs's 20 GB minfreespace, so it has
      // quietly stopped receiving new files. The pool percentage alone
      // would not show this.
      usedBytes: Math.round(8 * TIB - 12 * GIB),
      // SMART still says PASSED. It is reallocating sectors anyway.
      health: 'passed',
      temperatureC: 41,
      powerOnHours: 38_911,
      reallocatedSectors: REALLOCATED_ON_DISK2,
      pendingSectors: 1,
      lastSelfTestAt: daysAgo(2),
      lastSelfTestResult: 'Completed without error',
    },
    {
      id: 'ata-TOSHIBA_MG08ACA16TE_91K0A004FVGG',
      device: '/dev/sdd',
      model: 'TOSHIBA MG08ACA16TE',
      serial: '91K0A004FVGG',
      sizeBytes: 16 * TIB,
      role: 'parity',
      mountpoint: '/mnt/parity1',
      filesystem: 'ext4',
      usedBytes: Math.round(7.9 * TIB),
      health: 'passed',
      temperatureC: 39,
      powerOnHours: 9_140,
      reallocatedSectors: 0,
      pendingSectors: 0,
      lastSelfTestAt: daysAgo(6),
      lastSelfTestResult: 'Completed without error',
    },
    {
      id: 'usb-Seagate_Expansion_HDD_NA9K2R1P',
      device: '/dev/sde',
      model: 'Seagate Expansion HDD',
      serial: null,
      sizeBytes: 2 * TIB,
      role: 'unassigned',
      mountpoint: null,
      filesystem: null,
      usedBytes: null,
      // A USB enclosure that does not pass SMART through. 'unknown' is
      // the honest answer; calling it 'passed' would be a lie.
      health: 'unknown',
      temperatureC: null,
      powerOnHours: null,
      reallocatedSectors: null,
      pendingSectors: null,
      lastSelfTestAt: null,
      lastSelfTestResult: null,
    },
  ];
}

export function initialPool(): Pool {
  const disks = initialDisks().filter((d) => d.role === 'data');
  const branches = disks.map((d) => ({
    diskId: d.id,
    path: d.mountpoint ?? '',
    totalBytes: d.sizeBytes,
    usedBytes: d.usedBytes ?? 0,
  }));
  return {
    configured: true,
    mountpoint: '/mnt/storage',
    totalBytes: branches.reduce((n, b) => n + b.totalBytes, 0),
    usedBytes: branches.reduce((n, b) => n + b.usedBytes, 0),
    branches,
    createPolicy: 'mfs',
    minFreeBytes: 20 * GIB,
  };
}

export function initialParity(): Parity {
  return {
    configured: true,
    parityDiskIds: ['ata-TOSHIBA_MG08ACA16TE_91K0A004FVGG'],
    lastSyncAt: daysAgo(PARITY_SYNC_DAYS_AGO),
    lastSyncState: PARITY_STATE,
    lastScrubAt: daysAgo(11),
    pendingChanges: 1_842,
    deletedSinceSync: 17,
    // Matches snapraid_delete_threshold in host/roles/snapraid.
    deletionThreshold: 200,
    stalenessWarnDays: 3,
  };
}

/** Full attribute table, for the one drive worth looking at closely. */
export function smartFor(diskId: string): DiskSmart {
  const disk = initialDisks().find((d) => d.id === diskId);
  if (!disk || disk.health === 'unknown') {
    return { diskId, supported: false, overall: 'unknown', attributes: [], collectedAt: new Date().toISOString() };
  }
  const reallocated = disk.reallocatedSectors ?? 0;
  const pending = disk.pendingSectors ?? 0;
  return {
    diskId,
    supported: true,
    overall: disk.health,
    collectedAt: new Date().toISOString(),
    attributes: [
      {
        id: 5,
        name: 'Reallocated_Sector_Ct',
        value: reallocated > 0 ? 198 : 200,
        worst: reallocated > 0 ? 198 : 200,
        threshold: 140,
        raw: String(reallocated),
        failedWhen: null,
      },
      { id: 9, name: 'Power_On_Hours', value: 58, worst: 58, threshold: 0, raw: String(disk.powerOnHours ?? 0), failedWhen: null },
      { id: 194, name: 'Temperature_Celsius', value: 110, worst: 98, threshold: 0, raw: String(disk.temperatureC ?? 0), failedWhen: null },
      {
        id: 197,
        name: 'Current_Pending_Sector',
        value: pending > 0 ? 199 : 200,
        worst: 200,
        threshold: 0,
        raw: String(pending),
        failedWhen: null,
      },
      { id: 198, name: 'Offline_Uncorrectable', value: 200, worst: 200, threshold: 0, raw: '0', failedWhen: null },
      { id: 199, name: 'UDMA_CRC_Error_Count', value: 200, worst: 200, threshold: 0, raw: '0', failedWhen: null },
    ],
  };
}
