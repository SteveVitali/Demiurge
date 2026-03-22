import { cn } from '@/lib/utils';
import type { FailureClass } from '@/api/types';

interface FailureClassBadgeProps {
  failureClass: FailureClass;
  className?: string;
}

const config: Record<FailureClass, { color: string; label: string }> = {
  FrontendRenderFailure: { color: 'bg-purple-500/20 text-purple-400 border-purple-500/30', label: 'Frontend Render' },
  BackendContractFailure: { color: 'bg-red-500/20 text-red-400 border-red-500/30', label: 'Backend Contract' },
  AuthenticationFailure: { color: 'bg-orange-500/20 text-orange-400 border-orange-500/30', label: 'Authentication' },
  DataIntegrityFailure: { color: 'bg-amber-500/20 text-amber-400 border-amber-500/30', label: 'Data Integrity' },
  EnvironmentFailure: { color: 'bg-blue-500/20 text-blue-400 border-blue-500/30', label: 'Environment' },
  NetworkFailure: { color: 'bg-indigo-500/20 text-indigo-400 border-indigo-500/30', label: 'Network' },
  PerformanceFailure: { color: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30', label: 'Performance' },
  RegressionFailure: { color: 'bg-red-500/20 text-red-400 border-red-500/30', label: 'Regression' },
  UnknownFailure: { color: 'bg-zinc-500/20 text-zinc-400 border-zinc-500/30', label: 'Unknown' },
};

export function FailureClassBadge({ failureClass, className }: FailureClassBadgeProps) {
  const { color, label } = config[failureClass] ?? config.UnknownFailure;

  return (
    <span
      className={cn(
        'inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-medium',
        color,
        className,
      )}
      title={failureClass}
    >
      {label}
    </span>
  );
}
