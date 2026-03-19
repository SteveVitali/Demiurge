import type { ServiceSnapshot, ServiceKind } from '@/api/types';

/**
 * Infer a ServiceKind from a ServiceSnapshot's serviceId.
 * Used by ServiceTopology and BootTimeline to map freeform service IDs
 * to typed ServiceKind values for icon display and dependency ordering.
 */
export function inferServiceKind(svc: ServiceSnapshot): ServiceKind {
  const id = svc.serviceId.toLowerCase();
  if (id.includes('mongo') || id.includes('postgres') || id.includes('mysql') || id.includes('sqlite')) return 'Database';
  if (id.includes('redis') || id.includes('cache') || id.includes('memcache')) return 'Cache';
  if (id.includes('queue') || id.includes('rabbit') || id.includes('kafka')) return 'Queue';
  if (id.includes('client') || id.includes('frontend') || id.includes('web') || id.includes('ui')) return 'Frontend';
  if (id.includes('worker') || id.includes('cron') || id.includes('job')) return 'Worker';
  return 'Api';
}

/** Whether a ServiceKind represents infrastructure (DB, cache, queue) vs application layer. */
export function isInfraServiceKind(kind: ServiceKind): boolean {
  return kind === 'Database' || kind === 'Cache' || kind === 'Queue';
}
