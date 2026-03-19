import { useParams } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useState, useEffect } from 'react';
import { Construction } from 'lucide-react';
import { cn } from '@/lib/utils';
import { queryKeys } from '@/lib/query-keys';
import { getRun } from '@/api/endpoints';
import { useSSE } from '@/hooks/useSSE';
import { useRunStore } from '@/stores/run.store';
import { useAppStore } from '@/stores/app.store';
import { isTerminalStatus } from '@/lib/run-status';
import { PipelineStepper } from '@/components/run-detail/PipelineStepper';
import { RunTimers } from '@/components/run-detail/RunTimers';
import { RunActions } from '@/components/run-detail/RunActions';
import { AttemptTabs } from '@/components/run-detail/AttemptTabs';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { VerificationPanel } from '@/components/verification/VerificationPanel';
import { ArtifactBrowser } from '@/components/artifacts/ArtifactBrowser';
import { InspectionPanel } from '@/components/inspection/InspectionPanel';
import { EventsPanel } from '@/components/events/EventsPanel';

const tabLabels = [
  'Verification',
  'Agent',
  'Environment',
  'Artifacts',
  'Inspection',
  'Events',
];

export function RunDetailScreen() {
  const { runId } = useParams({ from: '/runs/$runId' });
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const currentStatus = useRunStore((s) => s.currentStatus);
  const resetRunStore = useRunStore((s) => s.reset);
  const [selectedAttempt, setSelectedAttempt] = useState(1);
  const [activeTab, setActiveTab] = useState(0);

  // Reset RunStore when switching between runs to prevent state bleed
  useEffect(() => {
    resetRunStore();
  }, [runId, resetRunStore]);

  const { data: run, isLoading, isError } = useQuery({
    queryKey: queryKeys.runs.detail(runId),
    queryFn: () => getRun(runId),
  });

  // Derive terminal from both SSE store and API data (SSE store is null on first render)
  const displayStatus = currentStatus ?? run?.status ?? null;
  const isTerminal = isTerminalStatus(displayStatus);
  const showBuildStep = run?.runMode === 'Build';

  // Subscribe to SSE for live updates (only for non-terminal runs)
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

  if (isLoading) {
    return <LoadingSpinner size="lg" className="flex-1" />;
  }

  if (isError || !run) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <ErrorState message={`Failed to load run ${runId}`} />
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
              className={cn(
                'px-4 py-2 text-sm font-medium transition-colors',
                activeTab === i
                  ? 'border-b-2 border-blue-500 text-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {label}
            </button>
          ))}
        </div>

        {/* Tab Content */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {activeTab === 0 && (
            <VerificationPanel runId={run.runId} attemptNumber={selectedAttempt} />
          )}
          {activeTab === 1 && (
            <div className="flex flex-1 items-center justify-center p-12">
              <div className="flex flex-col items-center gap-3 text-muted-foreground">
                <Construction className="h-10 w-10" />
                <p className="text-sm font-medium">Agent Panel</p>
                <p className="text-xs">Coming in Phase 3</p>
              </div>
            </div>
          )}
          {activeTab === 2 && (
            <div className="flex flex-1 items-center justify-center p-12">
              <div className="flex flex-col items-center gap-3 text-muted-foreground">
                <Construction className="h-10 w-10" />
                <p className="text-sm font-medium">Environment Panel</p>
                <p className="text-xs">Coming in Phase 3</p>
              </div>
            </div>
          )}
          {activeTab === 3 && (
            <ArtifactBrowser runId={run.runId} />
          )}
          {activeTab === 4 && (
            <InspectionPanel runId={run.runId} />
          )}
          {activeTab === 5 && (
            <EventsPanel runId={run.runId} />
          )}
        </div>
      </div>
    </div>
  );
}
