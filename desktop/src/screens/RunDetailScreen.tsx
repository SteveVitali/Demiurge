import { useParams } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useState, useEffect } from 'react';
import { AlertCircle, Construction } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { getRun } from '@/api/endpoints';
import { useSSE } from '@/hooks/useSSE';
import { useRunStore } from '@/stores/run.store';
import { useAppStore } from '@/stores/app.store';
import { PipelineStepper } from '@/components/run-detail/PipelineStepper';
import { RunTimers } from '@/components/run-detail/RunTimers';
import { RunActions } from '@/components/run-detail/RunActions';
import { AttemptTabs } from '@/components/run-detail/AttemptTabs';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { ACTIVE_RUN_STALE_TIME_MS, COMPLETED_RUN_STALE_TIME_MS } from '@/lib/constants';
import type { RunStatus } from '@/api/types';

const terminalStatuses: RunStatus[] = ['Succeeded', 'Exhausted', 'Cancelled', 'Interrupted', 'EnvironmentFailed'];

const tabLabels = [
  'Verification',
  'Agent',
  'Environment',
  'Artifacts',
  'Inspection',
  'Events',
  'Failure',
];

export function RunDetailScreen() {
  const { runId } = useParams({ from: '/runs/$runId' });
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const currentStatus = useRunStore((s) => s.currentStatus);
  const [selectedAttempt, setSelectedAttempt] = useState(1);
  const [activeTab, setActiveTab] = useState(0);

  const isTerminal = currentStatus ? terminalStatuses.includes(currentStatus) : false;

  const { data: run, isLoading, isError } = useQuery({
    queryKey: queryKeys.runs.detail(runId),
    queryFn: () => getRun(runId),
    staleTime: isTerminal ? COMPLETED_RUN_STALE_TIME_MS : ACTIVE_RUN_STALE_TIME_MS,
    refetchInterval: isTerminal ? false : ACTIVE_RUN_STALE_TIME_MS,
  });

  // Subscribe to SSE for live updates
  useSSE(isTerminal ? null : runId);

  // Track active run in app store
  useEffect(() => {
    if (!isTerminal) {
      setActiveRun(runId);
    }
    return () => {
      setActiveRun(null);
    };
  }, [runId, isTerminal, setActiveRun]);

  // Sync current status from API data when SSE hasn't provided one
  const displayStatus = currentStatus ?? run?.status ?? null;
  const showBuildStep = run?.runMode === 'Build';

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted-foreground border-t-transparent" />
      </div>
    );
  }

  if (isError || !run) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-red-400">
          <AlertCircle className="h-8 w-8" />
          <p className="text-sm">Failed to load run {runId}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-3">
            <h1 className="text-lg font-semibold truncate max-w-md">{run.taskText}</h1>
            {displayStatus && <StatusBadge status={displayStatus} animated />}
          </div>
          <RunTimers run={run} />
        </div>
        <RunActions runId={run.runId} status={displayStatus ?? run.status} />
      </div>

      {/* Pipeline Stepper */}
      <PipelineStepper
        currentStatus={displayStatus}
        showBuildStep={showBuildStep}
        attemptNumber={run.attemptCount}
      />

      {/* Attempt Tabs */}
      {run.attemptCount > 0 && (
        <AttemptTabs
          attemptCount={run.attemptCount}
          currentAttempt={selectedAttempt}
          onSelectAttempt={setSelectedAttempt}
        />
      )}

      {/* Content Tabs */}
      <div className="flex-1 rounded-lg border border-border bg-card">
        {/* Tab Bar */}
        <div className="flex border-b border-border">
          {tabLabels.map((label, i) => (
            <button
              key={label}
              onClick={() => setActiveTab(i)}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                activeTab === i
                  ? 'border-b-2 border-blue-500 text-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        {/* Tab Content — All placeholders for Phase 2+ */}
        <div className="flex flex-1 items-center justify-center p-12">
          <div className="flex flex-col items-center gap-3 text-muted-foreground">
            <Construction className="h-10 w-10" />
            <p className="text-sm font-medium">{tabLabels[activeTab]} Panel</p>
            <p className="text-xs">Coming in Phase {activeTab < 2 ? 2 : 3}</p>
          </div>
        </div>
      </div>
    </div>
  );
}
