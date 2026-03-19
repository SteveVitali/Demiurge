import { cn } from '@/lib/utils';

interface ConfidenceBarProps {
  value: number; // 0.0 – 1.0
  className?: string;
  showLabel?: boolean;
}

function getColor(value: number): string {
  if (value < 0.3) return 'bg-red-500';
  if (value < 0.7) return 'bg-yellow-500';
  return 'bg-emerald-500';
}

export function ConfidenceBar({ value, className, showLabel = true }: ConfidenceBarProps) {
  const clamped = Math.max(0, Math.min(1, value));
  const pct = Math.round(clamped * 100);

  return (
    <div className={cn('flex items-center gap-2', className)} title={`Confidence: ${clamped.toFixed(2)}`}>
      <div className="h-2 w-20 rounded-full bg-zinc-800 overflow-hidden">
        <div
          className={cn('h-full rounded-full transition-all', getColor(clamped))}
          style={{ width: `${pct}%` }}
        />
      </div>
      {showLabel && <span className="text-xs text-muted-foreground">{clamped.toFixed(2)}</span>}
    </div>
  );
}
