import { useQuery } from '@tanstack/react-query';
import { Clock, DollarSign, RotateCw } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { useAgentStore } from '@/stores/agent.store';
import { getAgentCost } from '@/api/endpoints';
import { CostDisplay } from '@/components/shared/CostDisplay';

// Desktop Phase 3 — §9.4: Live agent cost tracker.

interface AgentCostTrackerProps {
  runId: string;
}

export function AgentCostTracker({ runId }: AgentCostTrackerProps) {
  const storeCost = useAgentStore((s) => s.cost);
  const isActive = useAgentStore((s) => s.isActive);

  // Poll cost endpoint for non-WS cost updates
  const { data: apiCost } = useQuery({
    queryKey: queryKeys.agent.cost(runId),
    queryFn: () => getAgentCost(runId),
    refetchInterval: isActive ? 5_000 : false,
  });

  const cost = apiCost ?? storeCost;

  return (
    <div className="flex flex-wrap items-center gap-4 text-xs">
      <div className="flex items-center gap-1">
        <DollarSign className="h-3 w-3 text-muted-foreground" />
        <CostDisplay
          costUsd={cost.costUsd}
          inputTokens={cost.inputTokens}
          outputTokens={cost.outputTokens}
        />
      </div>
      <div className="flex items-center gap-1 text-muted-foreground">
        <RotateCw className="h-3 w-3" />
        <span>{cost.numTurns} turns</span>
      </div>
      {cost.durationMs > 0 && (
        <div className="flex items-center gap-1 text-muted-foreground">
          <Clock className="h-3 w-3" />
          <span>{formatDuration(cost.durationMs)}</span>
        </div>
      )}
    </div>
  );
}

function formatDuration(ms: number): string {
  const secs = Math.floor(ms / 1000);
  const mins = Math.floor(secs / 60);
  const remainingSecs = secs % 60;
  if (mins > 0) return `${mins}m ${remainingSecs}s`;
  return `${secs}s`;
}
