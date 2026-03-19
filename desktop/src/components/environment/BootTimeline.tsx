import type { ServiceSnapshot } from '@/api/types';
import { StatusBadge } from '@/components/shared/StatusBadge';

// Desktop Phase 3 — §9.5: Horizontal timeline of service boot sequence.

interface BootTimelineProps {
  services: ServiceSnapshot[];
}

const STATUS_ORDER: Record<string, number> = {
  RunningHealthy: 3,
  RunningUnhealthy: 2,
  Starting: 1,
  Pending: 0,
  Degraded: 2,
  Stopped: -1,
  Failed: -1,
};

export function BootTimeline({ services }: BootTimelineProps) {
  if (services.length === 0) return null;

  const sorted = [...services].sort((a, b) => {
    return (STATUS_ORDER[b.status] ?? 0) - (STATUS_ORDER[a.status] ?? 0);
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
