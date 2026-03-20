import { useParams } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useState, useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';
import { queryKeys } from '@/lib/query-keys';
import { getRun } from '@/api/endpoints';
import { useSSE } from '@/hooks/useSSE';
import { useWebSocket } from '@/hooks/useWebSocket';
import { useRunStore } from '@/stores/run.store';
import { useAppStore } from '@/stores/app.store';
import type { RunStatus } from '@/api/types';
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
import { AgentPanel } from '@/components/agent/AgentPanel';
import { EnvironmentPanel } from '@/components/environment/EnvironmentPanel';
import { FailureAnalysisPanel } from '@/components/failure/FailureAnalysisPanel';

const BASE_TAB_LABELS = [
  'Verification',
  'Agent',
  'Environment',
  'Artifacts',
  'Inspection',
  'Events',
] as const;

const FAILURE_STATUSES: RunStatus[] = ['Exhausted', 'EnvironmentFailed'];

export function RunDetailScreen() {
  const { runId } = useParams({ from: '/runs/$runId' });
  const setActiveRun = useAppStore((s) => s.setActiveRun);
  const currentStatus = useRunStore((s) => s.currentStatus);
  const resetRunStore = useRunStore((s) => s.reset);
  const [selectedAttempt, setSelectedAttempt] = useState(1);
  const [activeTab, setActiveTab] = useState(0);
  const hasAutoSelectedFailureTab = useRef(false);

  // Reset RunStore when switching between runs to prevent state bleed
  useEffect(() => {
    resetRunStore();
  }, [runId, resetRunStore]);

  const { data: run, isLoading, isError } = useQuery({
    queryKey: queryKeys.runs.detail(runId),
    queryFn: () => getRun(runId),
  });

  // Derive display status: API is source of truth for terminal runs (DB committed),
  // SSE store is more recent for in-progress runs (real-time updates).
  // This prevents stale SSE values (e.g. transient EnvironmentFailed from boot retry)
  // from overriding the actual final status (e.g. Exhausted).
  const apiStatus = run?.status ?? null;
  const displayStatus = (apiStatus && isTerminalStatus(apiStatus))
    ? apiStatus
    : (currentStatus ?? apiStatus);
  const isTerminal = isTerminalStatus(displayStatus);
  const showBuildStep = run?.runMode === 'Build';
  const showFailureTab = FAILURE_STATUSES.includes(displayStatus as RunStatus);
  const tabLabels = showFailureTab ? [...BASE_TAB_LABELS, 'Failure'] : [...BASE_TAB_LABELS];

  // Auto-select Failure tab when the run enters a failure state
  useEffect(() => {
    if (showFailureTab && !hasAutoSelectedFailureTab.current) {
      hasAutoSelectedFailureTab.current = true;
      // Failure tab is appended after BASE_TAB_LABELS
      setActiveTab(BASE_TAB_LABELS.length);
    }
  }, [showFailureTab]);

  // Reset auto-select flag when switching runs
  useEffect(() => {
    hasAutoSelectedFailureTab.current = false;
  }, [runId]);

  // Subscribe to SSE for live updates (only for non-terminal runs)
  useSSE(isTerminal ? null : runId);

  // Desktop Phase 3: WebSocket connection for log tailing + agent transcript
  const wsRef = useWebSocket(isTerminal ? null : runId);

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
        attemptCount={run.attemptCount}
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
          {tabLabels[activeTab] === 'Verification' && (
            <VerificationPanel runId={run.runId} attemptNumber={selectedAttempt} />
          )}
          {tabLabels[activeTab] === 'Agent' && (
            <AgentPanel runId={run.runId} wsRef={wsRef} />
          )}
          {tabLabels[activeTab] === 'Environment' && (
            <EnvironmentPanel runId={run.runId} wsRef={wsRef} />
          )}
          {tabLabels[activeTab] === 'Artifacts' && (
            <ArtifactBrowser runId={run.runId} />
          )}
          {tabLabels[activeTab] === 'Inspection' && (
            <InspectionPanel runId={run.runId} />
          )}
          {tabLabels[activeTab] === 'Events' && (
            <EventsPanel runId={run.runId} />
          )}
          {tabLabels[activeTab] === 'Failure' && (
            <FailureAnalysisPanel
              runId={run.runId}
              attemptNumber={selectedAttempt}
              runStatus={displayStatus}
              finalSummary={run.finalSummary}
              onNavigateToTab={(label) => {
                const idx = tabLabels.indexOf(label as typeof tabLabels[number]);
                if (idx >= 0) setActiveTab(idx);
              }}
            />
          )}
        </div>
      </div>
    </div>
  );
}
