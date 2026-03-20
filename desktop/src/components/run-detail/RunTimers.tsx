import { Clock, Hash, RotateCcw } from 'lucide-react';
import { ElapsedTimer } from '@/components/shared/ElapsedTimer';
import type { TaskRun } from '@/api/types';

interface RunTimersProps {
  run: TaskRun;
}

export function RunTimers({ run }: RunTimersProps) {
  return (
    <div className="flex items-center gap-6 text-sm">
      <div className="flex items-center gap-1.5">
        <Clock className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-muted-foreground">Total:</span>
        <ElapsedTimer startedAt={run.startedAt} endedAt={run.endedAt} showIcon={false} />
      </div>

      <div className="flex items-center gap-1.5">
        <Hash className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-muted-foreground">Attempts:</span>
        <span className="tabular-nums">{run.attemptCount}/{run.maxAttempts}</span>
      </div>

      <div className="flex items-center gap-1.5">
        <RotateCcw className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-muted-foreground">Mode:</span>
        <span>{run.runMode}</span>
      </div>
    </div>
  );
}
