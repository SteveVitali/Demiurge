import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { GitFork } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { getVerdicts } from '@/api/endpoints';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { AggregateBar } from './AggregateBar';
import { VerifierMatrix } from './VerifierMatrix';
import { RequirementGraphModal } from './RequirementGraph';
import type { VerdictSummary } from '@/api/types';

interface VerificationPanelProps {
  runId: string;
  attemptNumber: number;
}

function computeSummary(verdicts: { status: string }[]): VerdictSummary {
  const summary: VerdictSummary = {
    totalCount: verdicts.length,
    passCount: 0,
    failCount: 0,
    inconclusiveCount: 0,
    blockedCount: 0,
    timeoutCount: 0,
    flakeCount: 0,
  };
  for (const v of verdicts) {
    switch (v.status) {
      case 'Pass': summary.passCount++; break;
      case 'Fail': summary.failCount++; break;
      case 'Inconclusive': summary.inconclusiveCount++; break;
      case 'Blocked': summary.blockedCount++; break;
      case 'Timeout': summary.timeoutCount++; break;
      case 'Flake': summary.flakeCount++; break;
    }
  }
  return summary;
}

export function VerificationPanel({ runId, attemptNumber }: VerificationPanelProps) {
  const [graphOpen, setGraphOpen] = useState(false);

  const { data: verdicts, isLoading, isError } = useQuery({
    queryKey: queryKeys.verdicts.list(runId, attemptNumber),
    queryFn: () => getVerdicts(runId, attemptNumber),
  });

  if (isLoading) {
    return <LoadingSpinner size="lg" className="flex-1 py-12" />;
  }

  if (isError) {
    return <ErrorState message="Failed to load verification results" />;
  }

  if (!verdicts || verdicts.length === 0) {
    return <EmptyState message="No verification results for this attempt" />;
  }

  const summary = computeSummary(verdicts);

  return (
    <div className="flex flex-col gap-4 p-4">
      <AggregateBar summary={summary} />

      <VerifierMatrix verdicts={verdicts} />

      <button
        onClick={() => setGraphOpen(true)}
        className="flex items-center gap-2 self-start rounded-md border border-border px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted transition-colors"
      >
        <GitFork size={14} />
        View Requirement Graph
      </button>

      <RequirementGraphModal
        runId={runId}
        open={graphOpen}
        onClose={() => setGraphOpen(false)}
      />
    </div>
  );
}
