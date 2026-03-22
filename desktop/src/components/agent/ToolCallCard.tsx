import { useState } from 'react';
import { ChevronDown, ChevronRight, Wrench, FileEdit, Terminal, CheckCircle, RotateCw, Search, FileText } from 'lucide-react';
import type { AgentTranscriptMessage } from '@/api/types';

// Desktop Phase 3 — §9.4: Collapsible tool call card for agent transcript.

interface ToolCallCardProps {
  message: AgentTranscriptMessage;
}

const toolIcons: Record<string, React.ElementType> = {
  Read: FileText,
  Edit: FileEdit,
  Bash: Terminal,
  verify_requirements: CheckCircle,
  restart_service: RotateCw,
  get_service_logs: Terminal,
  get_requirement_details: Search,
  check_service_health: CheckCircle,
  Grep: Search,
  Glob: Search,
};

export function ToolCallCard({ message }: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(false);
  const data = message.data;
  const toolName = (data.toolName as string) ?? 'unknown';
  const inputSummary = (data.inputSummary as string) ?? '';
  const Icon = toolIcons[toolName] ?? Wrench;

  // Format time
  const time = message.timestamp
    ? new Date(message.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '';

  return (
    <div className="rounded-lg border border-border bg-zinc-900/50 overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-accent/30 transition-colors"
      >
        {expanded ? (
          <ChevronDown className="h-3 w-3 text-muted-foreground shrink-0" />
        ) : (
          <ChevronRight className="h-3 w-3 text-muted-foreground shrink-0" />
        )}
        <Icon className="h-4 w-4 text-blue-400 shrink-0" />
        <span className="text-sm font-medium text-foreground truncate">{toolName}</span>
        {inputSummary && (
          <span className="text-xs text-muted-foreground truncate ml-1">
            {inputSummary.length > 80 ? inputSummary.slice(0, 80) + '...' : inputSummary}
          </span>
        )}
        <span className="ml-auto text-[10px] text-muted-foreground shrink-0">{time}</span>
      </button>

      {expanded && (
        <div className="border-t border-border px-3 py-2">
          <pre className="text-xs text-muted-foreground whitespace-pre-wrap break-all font-mono max-h-60 overflow-y-auto">
            {JSON.stringify(data, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}
