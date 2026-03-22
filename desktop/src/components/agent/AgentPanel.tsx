import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bot, Pause, Play } from 'lucide-react';
import { cn } from '@/lib/utils';
import { queryKeys } from '@/lib/query-keys';
import { useAgentStore } from '@/stores/agent.store';
import { useAgentTranscript } from '@/hooks/useAgentTranscript';
import { getAgentTranscript } from '@/api/endpoints';
import type { DemiurgeWebSocket } from '@/api/websocket';
import { TranscriptStream } from './TranscriptStream';
import { AgentCostTracker } from './AgentCostTracker';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';

// Desktop Phase 3 — §9.4: Agent panel with transcript stream + cost tracker.

interface AgentPanelProps {
  runId: string;
  wsRef: React.RefObject<DemiurgeWebSocket | null>;
}

export function AgentPanel({ runId, wsRef }: AgentPanelProps) {
  const isPaused = useAgentStore((s) => s.isPaused);
  const setPaused = useAgentStore((s) => s.setPaused);
  const messages = useAgentStore((s) => s.messages);
  const setMessages = useAgentStore((s) => s.setMessages);
  // Subscribe to live agent messages via WS
  useAgentTranscript(wsRef, runId, true);

  // Fetch initial transcript from REST API
  const { data: initialTranscript, isLoading } = useQuery({
    queryKey: queryKeys.agent.transcript(runId),
    queryFn: () => getAgentTranscript(runId),
    staleTime: 30_000,
  });

  // Seed store with initial transcript if WS hasn't provided messages yet
  useEffect(() => {
    if (initialTranscript && initialTranscript.length > 0 && messages.length === 0) {
      const withIds = initialTranscript.map((msg, i) => ({
        ...msg,
        id: msg.id || `rest-${i}`,
      }));
      setMessages(withIds);
    }
  }, [initialTranscript, messages.length, setMessages]);

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Bot className="h-4 w-4 text-blue-400" />
          <span className="text-sm font-medium">Agent Session</span>
          <span className="text-xs text-muted-foreground">
            {messages.length} messages
          </span>
        </div>
        <div className="flex items-center gap-3">
          <AgentCostTracker runId={runId} />
          <button
            onClick={() => setPaused(!isPaused)}
            className={cn(
              'inline-flex items-center gap-1 rounded px-2 py-1 text-xs transition-colors',
              isPaused
                ? 'bg-yellow-500/20 text-yellow-400 hover:bg-yellow-500/30'
                : 'text-muted-foreground hover:bg-accent hover:text-foreground',
            )}
            title={isPaused ? 'Resume auto-scroll' : 'Pause auto-scroll'}
          >
            {isPaused ? <Play className="h-3 w-3" /> : <Pause className="h-3 w-3" />}
            {isPaused ? 'Resume' : 'Pause'}
          </button>
        </div>
      </div>

      {/* Transcript */}
      {isLoading && messages.length === 0 ? (
        <LoadingSpinner size="md" className="flex-1 p-8" />
      ) : (
        <TranscriptStream className="flex-1" />
      )}
    </div>
  );
}
