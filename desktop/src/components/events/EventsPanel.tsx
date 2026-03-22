import { useState, useMemo } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { cn, formatTimestamp } from '@/lib/utils';
import { useRunStore } from '@/stores/run.store';
import type { SystemEvent } from '@/api/types';

interface EventsPanelProps {
  runId: string;
}

const severityColors: Record<string, string> = {
  INFO: 'bg-zinc-500/20 text-zinc-400',
  WARN: 'bg-yellow-500/20 text-yellow-400',
  ERROR: 'bg-red-500/20 text-red-400',
  DEBUG: 'bg-blue-500/20 text-blue-400',
};

const eventTypeOptions = [
  'state_transition',
  'verification_started',
  'verdict_produced',
  'service_status_changed',
  'agent_tool_use',
  'agent_progress',
  'agent_completed',
  'artifact_created',
  'repair_started',
  'repair_completed',
  'boot_progress',
];

const severityOptions = ['INFO', 'WARN', 'ERROR', 'DEBUG'];

function EventRow({ event }: { event: SystemEvent }) {
  const [expanded, setExpanded] = useState(false);
  const sevColor = severityColors[event.severity] ?? severityColors.INFO;

  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-3 px-4 py-2 text-left hover:bg-muted/30 transition-colors"
      >
        {expanded ? (
          <ChevronDown size={12} className="text-muted-foreground shrink-0" />
        ) : (
          <ChevronRight size={12} className="text-muted-foreground shrink-0" />
        )}

        <span className="text-xs font-mono text-muted-foreground w-16 shrink-0">
          {formatTimestamp(event.timestamp)}
        </span>

        <span className={cn('inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium shrink-0', sevColor)}>
          {event.severity}
        </span>

        <span className="text-xs text-muted-foreground w-20 shrink-0 truncate">
          {event.component}
        </span>

        <span className="text-xs text-blue-400 w-32 shrink-0 truncate">
          {event.eventType}
        </span>

        <span className="text-xs text-foreground truncate flex-1">
          {event.humanMessage}
        </span>
      </button>

      {expanded && (
        <div className="bg-muted/20 px-4 py-2 ml-6 border-l-2 border-border">
          <pre className="text-xs font-mono text-muted-foreground whitespace-pre-wrap overflow-auto max-h-48">
            {JSON.stringify(event.payload, null, 2)}
          </pre>
          {Object.keys(event.correlationFields).length > 0 && (
            <div className="mt-2 flex flex-wrap gap-2">
              {Object.entries(event.correlationFields).map(([k, v]) => (
                <span key={k} className="inline-flex items-center rounded bg-zinc-800 px-1.5 py-0.5 text-[10px] text-muted-foreground">
                  {k}={v}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function EventsPanel({ runId: _runId }: EventsPanelProps) {
  const events = useRunStore((s) => s.events);
  const [typeFilter, setTypeFilter] = useState<string>('');
  const [severityFilter, setSeverityFilter] = useState<string>('');

  const filtered = useMemo(() => {
    let result = events;
    if (typeFilter) {
      result = result.filter((e) => e.eventType === typeFilter);
    }
    if (severityFilter) {
      result = result.filter((e) => e.severity === severityFilter);
    }
    return result;
  }, [events, typeFilter, severityFilter]);

  return (
    <div className="flex flex-col flex-1 overflow-hidden">
      {/* Filters */}
      <div className="flex items-center gap-3 border-b border-border px-4 py-2">
        <span className="text-xs text-muted-foreground">
          Events ({filtered.length})
        </span>

        <select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value)}
          className="rounded border border-border bg-card px-2 py-1 text-xs text-foreground"
        >
          <option value="">All Types</option>
          {eventTypeOptions.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>

        <select
          value={severityFilter}
          onChange={(e) => setSeverityFilter(e.target.value)}
          className="rounded border border-border bg-card px-2 py-1 text-xs text-foreground"
        >
          <option value="">All Severity</option>
          {severityOptions.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      {/* Event list */}
      <div className="flex-1 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
            {events.length === 0 ? 'No events recorded yet' : 'No events match filters'}
          </div>
        ) : (
          filtered.map((event) => (
            <EventRow key={event.eventId} event={event} />
          ))
        )}
      </div>
    </div>
  );
}
