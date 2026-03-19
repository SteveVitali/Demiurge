import type { ChangedSurface } from '@/api/types';
import { ConfidenceBar } from '@/components/shared/ConfidenceBar';

interface ImpactMapProps {
  surfaces: ChangedSurface[];
  className?: string;
}

export function ImpactMap({ surfaces, className }: ImpactMapProps) {
  if (surfaces.length === 0) {
    return (
      <div className="text-sm text-muted-foreground py-2">
        No changed file impacts detected.
      </div>
    );
  }

  return (
    <div className={className}>
      <h4 className="text-xs font-medium text-muted-foreground mb-2">Impact Map</h4>
      <div className="space-y-3">
        {surfaces.map((surface) => (
          <div
            key={surface.filePath}
            className="rounded-lg border border-border p-3 space-y-2"
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-mono text-foreground truncate">
                {surface.filePath}
              </span>
              <ConfidenceBar value={surface.confidence} />
            </div>

            {surface.affectedRoutes.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                <span className="text-xs text-muted-foreground">Routes:</span>
                {surface.affectedRoutes.map((route) => (
                  <span
                    key={route}
                    className="inline-flex items-center rounded bg-blue-500/10 px-1.5 py-0.5 text-xs text-blue-400"
                  >
                    {route}
                  </span>
                ))}
              </div>
            )}

            {surface.affectedComponents.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                <span className="text-xs text-muted-foreground">Components:</span>
                {surface.affectedComponents.map((comp) => (
                  <span
                    key={comp}
                    className="inline-flex items-center rounded bg-purple-500/10 px-1.5 py-0.5 text-xs text-purple-400"
                  >
                    {comp}
                  </span>
                ))}
              </div>
            )}

            {surface.affectedServices.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                <span className="text-xs text-muted-foreground">Services:</span>
                {surface.affectedServices.map((svc) => (
                  <span
                    key={svc}
                    className="inline-flex items-center rounded bg-emerald-500/10 px-1.5 py-0.5 text-xs text-emerald-400"
                  >
                    {svc}
                  </span>
                ))}
              </div>
            )}

            {surface.infraSensitive && (
              <span className="inline-flex items-center rounded bg-red-500/10 px-1.5 py-0.5 text-xs text-red-400">
                Infrastructure-sensitive
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
