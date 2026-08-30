/**
 * Turning the box's internal event names into sentences.
 *
 * Two feeds render raw keys today: the Overview's "Recent changes"
 * (`health:healthy stalwart`, `start adguard`) and Settings' "Recent
 * activity" (`mdns.alias.publish`, `job.finish`, `enable:jellyfin`,
 * `stalwart.secrets.bootstrap`). Both are the honest truth and both are
 * addressed to whoever wrote the code. The owner should be able to read
 * their own box's history without knowing what a container health probe or
 * an mDNS alias is.
 *
 * Unknown keys fall through unchanged rather than being hidden: an event we
 * have no sentence for is still real, and silently dropping it would be a
 * worse failure than showing its name.
 */

/** Container lifecycle events, from the docker event stream. */
export function containerEventText(action: string, container: string): string {
  const name = container || 'a service';
  switch (action) {
    case 'start':          return `${name} started`;
    case 'stop':           return `${name} stopped`;
    case 'die':            return `${name} exited`;
    case 'restart':        return `${name} restarted`;
    case 'kill':           return `${name} was stopped`;
    case 'create':         return `${name} was created`;
    case 'destroy':        return `${name} was removed`;
    case 'health:healthy': return `${name} is healthy`;
    case 'health:unhealthy': return `${name} stopped responding`;
    case 'oom':            return `${name} ran out of memory`;
    default:               return `${name}: ${action}`;
  }
}

/**
 * Audit-log actions. Keys are dotted namespaces (`mdns.alias.publish`) and
 * a few carry a colon-suffixed subject (`enable:jellyfin`).
 */
export function auditActionText(action: string): string {
  const [head, subject] = action.split(':', 2);
  const pretty = subject ? subject.replace(/[-_]/g, ' ') : null;

  const table: Record<string, string> = {
    'onboarding.admin.create': 'Created the admin account',
    'onboarding.domain.set': 'Set the domain',
    'onboarding.dns.set': 'Chose how DNS works',
    'onboarding.packages.set': 'Chose which apps to install',
    'onboarding.install': 'Wrote the setup',
    'onboarding.complete': 'Finished first-run setup',
    'onboarding.launch.start': 'Started bringing services online',
    'onboarding.launch.finish': 'Services finished starting',
    'mdns.alias.publish': 'Published an address on the network',
    'authelia.users.projected': 'Updated who can sign in',
    'stalwart.secrets.bootstrap': 'Set up the mail server',
    'auth.login': 'Signed in',
    'auth.logout': 'Signed out',
    'auth.password.change': 'Changed a password',
    // Added with the recovery-code feature. Without them the fallback
    // rendered 'auth.recovery_code.issue' as "Auth recovery code issue",
    // which reads as a fault report — on the security-sensitive rows where
    // a reader is least able to shrug off an apparent problem.
    'auth.recovery_code.issue': 'Created a new recovery code',
    'auth.recovery_code.redeem': 'Used the recovery code to set a password',
    'auth.recovery_code.reject': 'A recovery code was entered incorrectly',
    'user.create': 'Added a user',
    'user.delete': 'Removed a user',
    'job.start': 'Started a task',
    'job.finish': 'Finished a task',
    'job.fail': 'A task failed',
    'security.finding.dismiss': 'Dismissed a security check',
    'backup.run': 'Ran a backup',
  };

  const base = table[head] ?? table[action];
  if (base && pretty) return `${base}: ${pretty}`;
  if (base) return base;

  // Unknown: make it readable without pretending to understand it.
  const words = head.replace(/[._]/g, ' ').trim();
  const sentence = words.charAt(0).toUpperCase() + words.slice(1);
  return pretty ? `${sentence}: ${pretty}` : sentence;
}
