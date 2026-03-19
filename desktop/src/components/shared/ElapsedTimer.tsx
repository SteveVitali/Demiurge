import { useState, useEffect, useCallback } from 'react';
import { formatElapsed } from '@/lib/utils';
import { Clock } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ElapsedTimerProps {
  startedAt: string | null;
  endedAt?: string | null;
  className?: string;
  showIcon?: boolean;
}

export function ElapsedTimer({ startedAt, endedAt, className, showIcon = true }: ElapsedTimerProps) {
  const getElapsed = useCallback(() => {
    if (!startedAt) return '—';
    const start = new Date(startedAt).getTime();
    const end = endedAt ? new Date(endedAt).getTime() : undefined;
    return formatElapsed(start, end);
  }, [startedAt, endedAt]);

  const [elapsed, setElapsed] = useState(getElapsed);

  useEffect(() => {
    setElapsed(getElapsed());

    if (!startedAt || endedAt) return;

    const id = setInterval(() => {
      setElapsed(getElapsed());
    }, 1000);

    return () => clearInterval(id);
  }, [startedAt, endedAt, getElapsed]);

  return (
    <span className={cn('inline-flex items-center gap-1 tabular-nums text-muted-foreground', className)}>
      {showIcon && <Clock className="h-3.5 w-3.5" />}
      {elapsed}
    </span>
  );
}
