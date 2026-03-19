import { cn } from '@/lib/utils';
import type { RunStatus, VerdictStatus, ServiceStatus, AttemptStatus } from '@/api/types';

type AnyStatus = RunStatus | VerdictStatus | ServiceStatus | AttemptStatus;

interface StatusBadgeProps {
  status: AnyStatus;
  size?: 'sm' | 'md' | 'lg';
  animated?: boolean;
}

type StatusCategory = 'active' | 'success' | 'failure' | 'warning' | 'neutral' | 'cancelled';

function categorize(status: AnyStatus): StatusCategory {
  switch (status) {
    // Active/In-Progress
    case 'InspectingRepo':
    case 'CompilingRequirements':
    case 'PlanningEnvironment':
    case 'BootstrappingEnvironment':
    case 'SeedingFixtures':
    case 'BootstrappingAuth':
    case 'Verifying':
    case 'AnalyzingFailure':
    case 'PlanningRepair':
    case 'Repairing':
    case 'PlanningRerun':
    case 'SoftResettingEnvironment':
    case 'RebuildingEnvironment':
    case 'PlanningFeature':
    case 'GeneratingCode':
    case 'Starting':
    case 'Running':
      return 'active';

    // Success/Healthy
    case 'Succeeded':
    case 'Pass':
    case 'RunningHealthy':
    case 'Passed':
      return 'success';

    // Failure/Error
    case 'Exhausted':
    case 'Fail':
    case 'EnvironmentFailed':
    case 'Failed':
      return 'failure';

    // Warning/Partial
    case 'Flake':
    case 'Inconclusive':
    case 'RepairFailed':
    case 'RunningUnhealthy':
    case 'Degraded':
    case 'Timeout':
    case 'ReadyToVerify':
      return 'warning';

    // Cancelled/Stopped
    case 'Cancelled':
    case 'Interrupted':
    case 'Stopped':
      return 'cancelled';

    // Neutral/Pending
    default:
      return 'neutral';
  }
}

const categoryColors: Record<StatusCategory, string> = {
  active: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  success: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30',
  failure: 'bg-red-500/20 text-red-400 border-red-500/30',
  warning: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
  neutral: 'bg-zinc-500/20 text-zinc-400 border-zinc-500/30',
  cancelled: 'bg-slate-500/20 text-slate-400 border-slate-500/30',
};

const sizeClasses = {
  sm: 'text-[10px] px-1.5 py-0.5',
  md: 'text-xs px-2 py-0.5',
  lg: 'text-sm px-2.5 py-1',
};

function formatStatus(status: string): string {
  return status.replace(/([A-Z])/g, ' $1').trim();
}

export function StatusBadge({ status, size = 'md', animated = false }: StatusBadgeProps) {
  const category = categorize(status);

  return (
    <span
      className={cn(
        'inline-flex items-center rounded-md border font-medium',
        categoryColors[category],
        sizeClasses[size],
        animated && category === 'active' && 'animate-pulse',
      )}
      aria-label={`Status: ${formatStatus(status)}`}
    >
      {formatStatus(status)}
    </span>
  );
}
