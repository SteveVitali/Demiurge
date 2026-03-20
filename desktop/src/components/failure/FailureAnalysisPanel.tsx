import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, FileCode, Server as ServerIcon } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { getFailurePacket } from '@/api/endpoints';
import { FailureClassBadge } from '@/components/shared/FailureClassBadge';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { ConfidenceBar } from '@/components/shared/ConfidenceBar';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import type { RunStatus } from '@/api/types';

// Desktop Phase 3 — §9.12: Failure analysis panel rendering FailurePacket data.

interface FailureAnalysisPanelProps {
  runId: string;
  attemptNumber: number;
  runStatus?: RunStatus | null;
  finalSummary?: string | null;
  onNavigateToTab?: (tabLabel: string) => void;
}

export function FailureAnalysisPanel({ runId, attemptNumber, runStatus, finalSummary, onNavigateToTab }: FailureAnalysisPanelProps) {
  const { data: packet, isLoading, isError } = useQuery({
    queryKey: queryKeys.failures.packet(runId, attemptNumber),
    queryFn: () => getFailurePacket(runId, attemptNumber),
    staleTime: Infinity,
    gcTime: 10 * 60 * 1000,
    retry: 1,
  });

  if (isLoading) {
    return <LoadingSpinner size="md" className="flex-1 p-8" />;
  }

  if (isError || !packet) {
    // Show run-level failure summary for pre-attempt failures (e.g. environment boot)
    if (finalSummary) {
      return (
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <div className="flex items-center gap-3">
            <AlertTriangle className="h-5 w-5 text-red-400 shrink-0" />
            <h3 className="text-sm font-medium">Run Failed</h3>
            {runStatus && <StatusBadge status={runStatus} />}
          </div>
          <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-4">
            <p className="text-sm">{finalSummary}</p>
            {finalSummary.toLowerCase().includes('verification') && onNavigateToTab && (
              <button
                onClick={() => onNavigateToTab('Verification')}
                className="mt-3 text-xs text-blue-400 hover:text-blue-300 underline underline-offset-2"
              >
                View verification results →
              </button>
            )}
          </div>
        </div>
      );
    }

    return (
      <div className="flex flex-1 items-center justify-center p-12">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <AlertTriangle className="h-10 w-10" />
          <p className="text-sm font-medium">No Failure Analysis</p>
          <p className="text-xs">No failure packet available for attempt {attemptNumber}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto p-4 space-y-6">
      {/* Header */}
      <div>
        <h3 className="text-sm font-medium text-muted-foreground mb-2">
          Failure Analysis — Attempt {attemptNumber}
        </h3>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">Primary:</span>
          <FailureClassBadge failureClass={packet.primaryFailureClass} />
          {packet.secondaryFailureClasses.map((fc, i) => (
            <FailureClassBadge key={i} failureClass={fc} />
          ))}
        </div>
      </div>

      {/* Summary */}
      <div>
        <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">
          Summary
        </h4>
        <p className="text-sm">{packet.summary}</p>
      </div>

      {/* Suspected Root Causes */}
      {packet.suspectedRootCauses.length > 0 && (
        <div>
          <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
            Suspected Root Causes
          </h4>
          <div className="space-y-3">
            {packet.suspectedRootCauses.map((cause, i) => (
              <div key={i} className="rounded-lg border border-border bg-zinc-900/50 p-3">
                <div className="flex items-start justify-between gap-2 mb-1">
                  <span className="text-sm font-medium">
                    {i + 1}. {cause.description}
                  </span>
                  <ConfidenceBar value={cause.confidence} className="shrink-0" />
                </div>
                {cause.files.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1 mt-1">
                    <FileCode className="h-3 w-3 text-muted-foreground" />
                    {cause.files.map((f, j) => (
                      <code key={j} className="text-xs bg-zinc-800 px-1 py-0.5 rounded">
                        {f}
                      </code>
                    ))}
                  </div>
                )}
                {cause.components.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1 mt-1">
                    <ServerIcon className="h-3 w-3 text-muted-foreground" />
                    {cause.components.map((c, j) => (
                      <span key={j} className="text-xs text-muted-foreground">{c}</span>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Reproduction Steps */}
      {packet.reproductionSteps.length > 0 && (
        <div>
          <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
            Reproduction Steps
          </h4>
          <ol className="space-y-1 list-decimal list-inside">
            {packet.reproductionSteps.map((step, i) => (
              <li key={i} className="text-sm">{step}</li>
            ))}
          </ol>
        </div>
      )}

      {/* Repair Scope */}
      <div>
        <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
          Repair Scope
        </h4>
        <div className="rounded-lg border border-border bg-zinc-900/50 p-3 space-y-2">
          {packet.recommendedRepairScope.files.length > 0 && (
            <div className="flex flex-wrap items-center gap-1">
              <span className="text-xs text-muted-foreground">Files:</span>
              {packet.recommendedRepairScope.files.map((f, i) => (
                <code key={i} className="text-xs bg-zinc-800 px-1 py-0.5 rounded">{f}</code>
              ))}
            </div>
          )}
          {packet.recommendedRepairScope.services.length > 0 && (
            <div className="flex flex-wrap items-center gap-1">
              <span className="text-xs text-muted-foreground">Services:</span>
              {packet.recommendedRepairScope.services.map((s, i) => (
                <code key={i} className="text-xs bg-zinc-800 px-1 py-0.5 rounded">{s}</code>
              ))}
            </div>
          )}
          <div className="text-xs text-muted-foreground">
            Requires Env Rebuild: {packet.recommendedRepairScope.requiresEnvRebuild ? 'Yes' : 'No'}
          </div>
        </div>
      </div>

      {/* Blockers */}
      {(packet.hardBlockers.length > 0 || packet.softBlockers.length > 0) && (
        <div>
          <h4 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
            Blockers
          </h4>
          <div className="flex gap-4 text-sm">
            <div>
              <span className="text-xs text-muted-foreground">Hard:</span>{' '}
              {packet.hardBlockers.length > 0 ? packet.hardBlockers.join(', ') : 'none'}
            </div>
            <div>
              <span className="text-xs text-muted-foreground">Soft:</span>{' '}
              {packet.softBlockers.length > 0 ? packet.softBlockers.join(', ') : 'none'}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
