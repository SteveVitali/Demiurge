import { Server, Terminal, Layers, Cog, RotateCw } from 'lucide-react';
import { cn } from '@/lib/utils';
import { StatusBadge } from '@/components/shared/StatusBadge';
import type { ServiceSnapshot } from '@/api/types';

interface ServiceCardProps {
  service: ServiceSnapshot;
  isSelected: boolean;
  onSelect: () => void;
  onRestart?: () => void;
}

const startupModeIcons: Record<string, React.ElementType> = {
  ScriptNative: Terminal,
  ComposeNative: Layers,
  Hybrid: Cog,
};

export function ServiceCard({ service, isSelected, onSelect, onRestart }: ServiceCardProps) {
  const ModeIcon = startupModeIcons[service.startupMode] ?? Server;

  return (
    <button
      onClick={onSelect}
      className={cn(
        'w-full text-left rounded-lg border p-3 transition-colors',
        isSelected
          ? 'border-blue-500 bg-blue-500/10'
          : 'border-border bg-card hover:border-border/80 hover:bg-accent/50',
      )}
    >
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ModeIcon className="h-4 w-4 text-muted-foreground" />
          <span className="text-sm font-medium">{service.serviceId}</span>
        </div>
        <StatusBadge status={service.status} size="sm" />
      </div>

      <div className="mt-2 flex items-center gap-3 text-xs text-muted-foreground">
        {service.pid && <span>PID: {service.pid}</span>}
        {service.containerId && (
          <span title={service.containerId}>
            Container: {service.containerId.slice(0, 12)}
          </span>
        )}
        <span>{service.logLineCount} log lines</span>
      </div>

      {onRestart && (
        <div className="mt-2">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onRestart();
            }}
            className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
          >
            <RotateCw className="h-3 w-3" />
            Restart
          </button>
        </div>
      )}
    </button>
  );
}
