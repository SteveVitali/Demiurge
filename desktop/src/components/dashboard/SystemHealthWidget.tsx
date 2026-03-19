import { Wifi, WifiOff, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAppStore } from '@/stores/app.store';

export function SystemHealthWidget() {
  const backendStatus = useAppStore((s) => s.backendStatus);

  const isConnected = backendStatus === 'connected';
  const isStarting = backendStatus === 'connecting';

  const backendDescription = isConnected
    ? 'localhost:19440'
    : isStarting
      ? 'Starting backend...'
      : 'Not reachable';

  const dbDescription = isConnected ? 'SQLite WAL' : isStarting ? 'Waiting...' : 'Unknown';

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="mb-3 text-sm font-medium text-muted-foreground">System Health</h3>
      <div className="space-y-2">
        <HealthRow
          label="Backend API"
          status={isConnected ? 'ok' : isStarting ? 'starting' : 'error'}
          description={backendDescription}
        />
        <HealthRow
          label="Database"
          status={isConnected ? 'ok' : isStarting ? 'starting' : 'error'}
          description={dbDescription}
        />
      </div>
    </div>
  );
}

function HealthRow({ label, status, description }: { label: string; status: 'ok' | 'starting' | 'error'; description: string }) {
  return (
    <div className="flex items-center justify-between text-sm">
      <div className="flex items-center gap-2">
        {status === 'ok' ? (
          <Wifi className="h-3.5 w-3.5 text-emerald-400" />
        ) : status === 'starting' ? (
          <Loader2 className="h-3.5 w-3.5 text-yellow-400 animate-spin" />
        ) : (
          <WifiOff className="h-3.5 w-3.5 text-red-400" />
        )}
        <span>{label}</span>
      </div>
      <span className={cn('text-xs', status === 'ok' ? 'text-muted-foreground' : status === 'starting' ? 'text-yellow-400' : 'text-red-400')}>
        {description}
      </span>
    </div>
  );
}
