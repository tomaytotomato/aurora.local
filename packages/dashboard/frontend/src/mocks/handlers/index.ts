// MSW request handlers covering the full /api surface described in
// packages/dashboard/openapi.yaml. One file per domain; stateful bits
// read and write ../state so the UI behaves like a real backend within a
// session.
//
// Order matters only where paths could collide (MSW matches first-wins),
// which is why the specific /api/users routes sit before the broader
// package routes, as they did when this was one file.

import { authHandlers } from './auth';
import { backupHandlers } from './backup';
import { containersHandlers } from './containers';
import { customHandlers } from './custom';
import { disksHandlers } from './disks';
import { hardeningHandlers } from './hardening';
import { jobsHandlers } from './jobs';
import { marketplaceHandlers } from './marketplace';
import { networkHandlers } from './network';
import { notificationsHandlers } from './notifications';
import { observabilityHandlers } from './observability';
import { onboardingHandlers } from './onboarding';
import { packagesHandlers } from './packages';
import { proxyHandlers } from './proxy';
import { servicesHandlers } from './services';
import { systemHandlers } from './system';
import { updatesHandlers } from './updates';
import { usersHandlers } from './users';
import { vpnHandlers } from './vpn';

export const handlers = [
  ...authHandlers,
  ...usersHandlers,
  ...onboardingHandlers,
  ...packagesHandlers,
  ...networkHandlers,
  ...proxyHandlers,
  ...updatesHandlers,
  ...marketplaceHandlers,
  ...jobsHandlers,
  ...backupHandlers,
  ...disksHandlers,
  ...customHandlers,
  ...notificationsHandlers,
  ...servicesHandlers,
  ...containersHandlers,
  ...observabilityHandlers,
  ...hardeningHandlers,
  ...systemHandlers,
  ...vpnHandlers,
];
