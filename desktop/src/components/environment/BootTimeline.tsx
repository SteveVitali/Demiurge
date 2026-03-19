import type { ServiceSnapshot, ServiceKind } from '@/api/types';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { inferServiceKind } from '@/lib/service-utils';

// Desktop Phase 3 — §9.5: Horizontal timeline of service boot sequence.
// Shows services in dependency order: infrastructure first, then API, then frontend.

interface BootTimelineProps {
  services: ServiceSnapshot[];
}

const KIND_BOOT_ORDER: Record<ServiceKind, number> = {
  Database: 0,
  Cache: 1,
  Queue: 2,
  Worker: 3,
  Api: 4,
  Frontend: 5,
};

export function BootTimeline({ services }: BootTimelineProps) {
  if (services.length === 0) return null;

  const sorted = [...services].sort((a, b) => {
    return (KIND_BOOT_ORDER[inferServiceKind(a)] ?? 4) - (KIND_BOOT_ORDER[inferServiceKind(b)] ?? 4);
  });

  return (
    <div className="flex flex-col gap-1.5">
      <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
        Boot Sequence
      </h4>
      <div className="flex items-center gap-1 overflow-x-auto pb-1">
        {sorted.map((svc, i) => (
          <div key={svc.serviceId} className="flex items-center">
            <div className="flex flex-col items-center gap-1 rounded-md border border-border bg-background/50 px-3 py-2 min-w-[100px]">
              <span className="text-xs font-medium truncate max-w-[90px]">{svc.serviceId}</span>
              <StatusBadge status={svc.status} size="sm" />
            </div>
            {i < sorted.length - 1 && (
              <div className="mx-0.5 h-px w-4 bg-border" />
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
