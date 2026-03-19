import type { VerdictSummary } from '@/api/types';
import { cn } from '@/lib/utils';

interface AggregateBarProps {
  summary: VerdictSummary;
  className?: string;
}

interface Segment {
  count: number;
  color: string;
  label: string;
}

export function AggregateBar({ summary, className }: AggregateBarProps) {
  const { totalCount, passCount, failCount, flakeCount, inconclusiveCount, blockedCount, timeoutCount } = summary;

  if (totalCount === 0) {
    return (
      <div className={cn('text-sm text-muted-foreground', className)}>
        No verdicts yet
      </div>
    );
  }

  const segments: Segment[] = [
    { count: passCount, color: 'bg-emerald-500', label: 'Pass' },
    { count: failCount, color: 'bg-red-500', label: 'Fail' },
    { count: flakeCount, color: 'bg-yellow-500', label: 'Flake' },
    { count: inconclusiveCount, color: 'bg-zinc-500', label: 'Inconclusive' },
    { count: blockedCount, color: 'bg-slate-500', label: 'Blocked' },
    { count: timeoutCount, color: 'bg-orange-500', label: 'Timeout' },
  ].filter((s) => s.count > 0);

  const nonPassCount = totalCount - passCount;

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center gap-3">
        <div className="h-3 flex-1 rounded-full bg-zinc-800 overflow-hidden flex">
          {segments.map((seg) => (
            <div
              key={seg.label}
              className={cn('h-full transition-all', seg.color)}
              style={{ width: `${(seg.count / totalCount) * 100}%` }}
              title={`${seg.label}: ${seg.count}`}
            />
          ))}
        </div>
        <span className="text-sm font-medium text-foreground whitespace-nowrap">
          {passCount}/{totalCount} passed
        </span>
      </div>
      {nonPassCount > 0 && (
        <div className="flex gap-3 text-xs text-muted-foreground">
          {segments.filter((s) => s.label !== 'Pass').map((seg) => (
            <span key={seg.label} className="flex items-center gap-1">
              <span className={cn('h-2 w-2 rounded-full', seg.color)} />
              {seg.count} {seg.label.toLowerCase()}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
