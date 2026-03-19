import { useMutation, useQueryClient } from '@tanstack/react-query';
import { XCircle, Play } from 'lucide-react';
import { cancelRun, resumeRun } from '@/api/endpoints';
import { queryKeys } from '@/lib/query-keys';
import type { RunStatus } from '@/api/types';

interface RunActionsProps {
  runId: string;
  status: RunStatus;
}

const terminalStatuses: RunStatus[] = ['Succeeded', 'Exhausted', 'Cancelled', 'Interrupted'];
const resumableStatuses: RunStatus[] = ['Interrupted', 'ReadyToVerify', 'AnalyzingFailure', 'PlanningRepair'];

export function RunActions({ runId, status }: RunActionsProps) {
  const queryClient = useQueryClient();

  const cancelMutation = useMutation({
    mutationFn: () => cancelRun(runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.runs.detail(runId) });
    },
  });

  const resumeMutation = useMutation({
    mutationFn: () => resumeRun(runId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.runs.detail(runId) });
    },
  });

  const canCancel = !terminalStatuses.includes(status);
  const canResume = resumableStatuses.includes(status);

  return (
    <div className="flex items-center gap-2">
      {canResume && (
        <button
          onClick={() => resumeMutation.mutate()}
          disabled={resumeMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-md border border-emerald-600 bg-emerald-600/10 px-3 py-1.5 text-sm font-medium text-emerald-400 transition-colors hover:bg-emerald-600/20 disabled:opacity-50"
        >
          <Play className="h-3.5 w-3.5" />
          {resumeMutation.isPending ? 'Resuming...' : 'Resume'}
        </button>
      )}
      {canCancel && (
        <button
          onClick={() => cancelMutation.mutate()}
          disabled={cancelMutation.isPending}
          className="inline-flex items-center gap-1.5 rounded-md border border-red-600 bg-red-600/10 px-3 py-1.5 text-sm font-medium text-red-400 transition-colors hover:bg-red-600/20 disabled:opacity-50"
        >
          <XCircle className="h-3.5 w-3.5" />
          {cancelMutation.isPending ? 'Cancelling...' : 'Cancel'}
        </button>
      )}
    </div>
  );
}
