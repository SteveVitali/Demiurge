import { Wifi, WifiOff } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAppStore } from '@/stores/app.store';

export function SystemHealthWidget() {
  const backendStatus = useAppStore((s) => s.backendStatus);

  const isConnected = backendStatus === 'connected';

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="mb-3 text-sm font-medium text-muted-foreground">System Health</h3>
      <div className="space-y-2">
        <HealthRow
          label="Backend API"
          ok={isConnected}
          description={isConnected ? 'localhost:19440' : 'Not reachable'}
        />
        <HealthRow
          label="Database"
          ok={isConnected}
          description={isConnected ? 'SQLite WAL' : 'Unknown'}
        />
      </div>
    </div>
  );
}

function HealthRow({ label, ok, description }: { label: string; ok: boolean; description: string }) {
  return (
    <div className="flex items-center justify-between text-sm">
      <div className="flex items-center gap-2">
        {ok ? (
          <Wifi className="h-3.5 w-3.5 text-emerald-400" />
        ) : (
          <WifiOff className="h-3.5 w-3.5 text-red-400" />
        )}
        <span>{label}</span>
      </div>
      <span className={cn('text-xs', ok ? 'text-muted-foreground' : 'text-red-400')}>
        {description}
      </span>
    </div>
  );
}
