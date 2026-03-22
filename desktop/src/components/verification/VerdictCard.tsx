import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { cn, formatDuration } from '@/lib/utils';
import type { RequirementVerdict, FailureClass } from '@/api/types';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { ConfidenceBar } from '@/components/shared/ConfidenceBar';
import { FailureClassBadge } from '@/components/shared/FailureClassBadge';

interface VerdictCardProps {
  verdict: RequirementVerdict;
  requirementDescription?: string;
  priority?: string;
  className?: string;
}

export function VerdictCard({ verdict, requirementDescription, priority, className }: VerdictCardProps) {
  const [expanded, setExpanded] = useState(false);

  const priorityColor =
    priority === 'Required' ? 'border-l-red-500' :
    priority === 'Important' ? 'border-l-yellow-500' :
    'border-l-zinc-600';

  return (
    <div
      className={cn(
        'rounded-lg border border-border bg-card overflow-hidden border-l-4',
        priorityColor,
        className,
      )}
    >
      {/* Header row */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-muted/50 transition-colors"
      >
        {expanded ? (
          <ChevronDown size={14} className="text-muted-foreground shrink-0" />
        ) : (
          <ChevronRight size={14} className="text-muted-foreground shrink-0" />
        )}

        <div className="flex flex-1 items-center gap-2 min-w-0">
          <span className="text-sm font-medium truncate">
            {verdict.requirementId}
          </span>
          {priority && (
            <span className="text-xs text-muted-foreground">({priority})</span>
          )}
        </div>

        <div className="flex items-center gap-3 shrink-0">
          <StatusBadge status={verdict.status} size="sm" />
          <span className="text-xs text-muted-foreground w-14 text-right">
            {formatDuration(verdict.executionDurationMs)}
          </span>
        </div>
      </button>

      {/* Expanded detail */}
      {expanded && (
        <div className="border-t border-border px-4 py-3 space-y-3 bg-muted/20">
          {requirementDescription && (
            <div className="text-sm text-muted-foreground">{requirementDescription}</div>
          )}

          <div className="flex flex-wrap items-center gap-4 text-xs">
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground">Confidence:</span>
              <ConfidenceBar value={verdict.confidence} />
            </div>
            {verdict.failureClass && (
              <FailureClassBadge failureClass={verdict.failureClass as FailureClass} />
            )}
          </div>

          {verdict.failureMessage && (
            <div className="rounded bg-zinc-900 px-3 py-2 text-xs font-mono text-red-400 whitespace-pre-wrap">
              {verdict.failureMessage}
            </div>
          )}

          {verdict.observations.length > 0 && (
            <div className="space-y-1">
              <span className="text-xs font-medium text-muted-foreground">Observations:</span>
              {verdict.observations.map((obs, i) => (
                <div key={i} className="flex gap-2 text-xs pl-2">
                  <span className="text-muted-foreground">•</span>
                  <div>
                    <span className="text-foreground">{obs.label}</span>
                    {obs.expected && obs.actual && (
                      <span className="text-muted-foreground">
                        {' '}— expected: <span className="text-emerald-400">{obs.expected}</span>,
                        actual: <span className="text-red-400">{obs.actual}</span>
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {verdict.evidenceRefs.length > 0 && (
            <div className="flex flex-wrap gap-2">
              <span className="text-xs text-muted-foreground">Evidence:</span>
              {verdict.evidenceRefs.map((ref) => (
                <span
                  key={ref}
                  className="inline-flex items-center rounded bg-zinc-800 px-2 py-0.5 text-xs text-blue-400 cursor-pointer hover:bg-zinc-700"
                >
                  {ref}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
