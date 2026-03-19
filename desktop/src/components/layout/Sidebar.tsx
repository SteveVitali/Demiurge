import { useNavigate, useLocation } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import {
  LayoutDashboard,
  Play,
  Settings,
  FileCode,
  PanelLeftClose,
  PanelLeftOpen,
  Circle,
  Wifi,
  WifiOff,
  BarChart3,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAppStore } from '@/stores/app.store';
import { useAuthStore } from '@/stores/auth.store';
import { useUsage } from '@/hooks/useUsage';
import { PlanTierBadge } from '@/components/PlanTierBadge';
import { queryKeys } from '@/lib/query-keys';
import { getRuns } from '@/api/endpoints';
import { RECENT_RUNS_LIMIT } from '@/lib/constants';

interface NavItem {
  label: string;
  path: string;
  icon: React.ReactNode;
}

const navItems: NavItem[] = [
  { label: 'Dashboard', path: '/', icon: <LayoutDashboard className="h-4 w-4" /> },
  { label: 'Config', path: '/config', icon: <FileCode className="h-4 w-4" /> },
  { label: 'Settings', path: '/settings', icon: <Settings className="h-4 w-4" /> },
];

export function Sidebar() {
  const navigate = useNavigate();
  const location = useLocation();
  const sidebarCollapsed = useAppStore((s) => s.sidebarCollapsed);
  const setSidebarCollapsed = useAppStore((s) => s.setSidebarCollapsed);
  const backendStatus = useAppStore((s) => s.backendStatus);
  const activeRunId = useAppStore((s) => s.activeRunId);
  const planTier = useAuthStore((s) => s.planTier);
  const userEmail = useAuthStore((s) => s.userEmail);
  const { data: usage } = useUsage();

  const { data: recentRuns } = useQuery({
    queryKey: queryKeys.runs.list({ limit: RECENT_RUNS_LIMIT, sort: 'created_at', order: 'desc' }),
    queryFn: () => getRuns({ limit: RECENT_RUNS_LIMIT, sort: 'created_at', order: 'desc' }),
    enabled: backendStatus === 'connected',
  });

  const isActive = (path: string) => location.pathname === path;

  return (
    <aside
      className={cn(
        'flex flex-col border-r border-border bg-sidebar-background text-sidebar-foreground transition-all duration-200',
        sidebarCollapsed ? 'w-14' : 'w-56',
      )}
    >
      {/* Header */}
      <div className="flex h-12 items-center justify-between border-b border-border px-3">
        {!sidebarCollapsed && (
          <span className="text-sm font-semibold tracking-tight">Demiurge</span>
        )}
        <button
          onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
          className="rounded-md p-1 hover:bg-sidebar-accent"
          aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {sidebarCollapsed ? (
            <PanelLeftOpen className="h-4 w-4" />
          ) : (
            <PanelLeftClose className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 space-y-1 px-2 py-2">
        {navItems.map((item) => (
          <button
            key={item.path}
            onClick={() => void navigate({ to: item.path })}
            className={cn(
              'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors',
              isActive(item.path)
                ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                : 'text-sidebar-foreground hover:bg-sidebar-accent/50',
            )}
          >
            {item.icon}
            {!sidebarCollapsed && item.label}
          </button>
        ))}

        {/* Active Run shortcut */}
        {activeRunId && (
          <button
            onClick={() => void navigate({ to: '/runs/$runId', params: { runId: activeRunId } })}
            className={cn(
              'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors',
              location.pathname.startsWith('/runs/')
                ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                : 'text-sidebar-foreground hover:bg-sidebar-accent/50',
            )}
          >
            <Play className="h-4 w-4 text-blue-400" />
            {!sidebarCollapsed && <span className="truncate">Active Run</span>}
          </button>
        )}

        {/* Recent Runs */}
        {!sidebarCollapsed && recentRuns && recentRuns.items.length > 0 && (
          <div className="mt-4">
            <p className="mb-1 px-2 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
              Recent Runs
            </p>
            {recentRuns.items.map((run) => (
              <button
                key={run.runId}
                onClick={() => void navigate({ to: '/runs/$runId', params: { runId: run.runId } })}
                className="flex w-full items-center gap-2 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-sidebar-accent/50"
              >
                <Circle
                  className={cn(
                    'h-2 w-2 fill-current',
                    run.status === 'Succeeded' && 'text-emerald-400',
                    (run.status === 'Exhausted' || run.status === 'EnvironmentFailed') && 'text-red-400',
                    run.status === 'Cancelled' && 'text-slate-400',
                    !['Succeeded', 'Exhausted', 'EnvironmentFailed', 'Cancelled', 'Interrupted'].includes(run.status) && 'text-blue-400',
                  )}
                />
                <span className="truncate">{run.taskText}</span>
              </button>
            ))}
          </div>
        )}
      </nav>

      {/* Footer — Plan Badge + Usage + Backend Status */}
      <div className="border-t border-border px-3 py-2">
        {!sidebarCollapsed && planTier && (
          <div className="mb-1.5 flex items-center gap-2">
            <PlanTierBadge tier={planTier} />
            {userEmail && (
              <span className="truncate text-[10px] text-muted-foreground">{userEmail}</span>
            )}
          </div>
        )}

        {/* Spec 05 §7.1: Usage indicator */}
        {!sidebarCollapsed && usage && usage.runs.limit > 0 && (
          <div className="mb-1.5">
            <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
              <BarChart3 className="h-3 w-3" />
              <span>Runs: {usage.runs.used}/{usage.runs.limit}</span>
            </div>
            <div className="mt-0.5 h-1 w-full overflow-hidden rounded-full bg-muted">
              <div
                className={cn(
                  'h-full rounded-full transition-all duration-300',
                  (() => {
                    const pct = (usage.runs.used / usage.runs.limit) * 100;
                    return pct < 60 ? 'bg-emerald-500' : pct < 80 ? 'bg-yellow-500' : 'bg-red-500';
                  })(),
                )}
                style={{ width: `${Math.min((usage.runs.used / usage.runs.limit) * 100, 100)}%` }}
              />
            </div>
          </div>
        )}
        {sidebarCollapsed && usage && usage.runs.limit > 0 && (
          <div className="mb-1.5 flex justify-center" title={`Runs: ${usage.runs.used}/${usage.runs.limit}`}>
            <BarChart3 className={cn(
              'h-3 w-3',
              (() => {
                const pct = (usage.runs.used / usage.runs.limit) * 100;
                return pct < 60 ? 'text-emerald-400' : pct < 80 ? 'text-yellow-400' : 'text-red-400';
              })(),
            )} />
          </div>
        )}

        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          {backendStatus === 'connected' ? (
            <Wifi className="h-3 w-3 text-emerald-400" />
          ) : (
            <WifiOff className="h-3 w-3 text-red-400" />
          )}
          {!sidebarCollapsed && (
            <span>
              {backendStatus === 'connected' ? 'Backend connected' : 'Backend offline'}
            </span>
          )}
        </div>
      </div>
    </aside>
  );
}
