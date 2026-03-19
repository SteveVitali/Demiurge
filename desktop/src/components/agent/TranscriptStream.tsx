import { useEffect, useRef } from 'react';
import { MessageSquare, AlertCircle, Info } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAgentStore } from '@/stores/agent.store';
import type { AgentTranscriptMessage } from '@/api/types';
import { ToolCallCard } from './ToolCallCard';

// Desktop Phase 3 — §9.4: Live-scrolling agent transcript stream.

interface TranscriptStreamProps {
  className?: string;
}

export function TranscriptStream({ className }: TranscriptStreamProps) {
  const messages = useAgentStore((s) => s.messages);
  const isPaused = useAgentStore((s) => s.isPaused);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (!isPaused && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isPaused]);

  if (messages.length === 0) {
    return (
      <div className={cn('flex items-center justify-center text-muted-foreground p-8', className)}>
        <p className="text-sm">Waiting for agent messages...</p>
      </div>
    );
  }

  return (
    <div ref={scrollRef} className={cn('overflow-y-auto space-y-2 p-3', className)}>
      {messages.map((msg) => (
        <TranscriptItem key={msg.id} message={msg} />
      ))}
    </div>
  );
}

function TranscriptItem({ message }: { message: AgentTranscriptMessage }) {
  switch (message.messageType) {
    case 'tool_use':
      return <ToolCallCard message={message} />;

    case 'text':
      return <TextBubble message={message} />;

    case 'progress':
      return <ProgressLine message={message} />;

    case 'error':
      return <ErrorLine message={message} />;

    case 'tool_result':
      return <ToolResultLine message={message} />;

    default:
      return null;
  }
}

function TextBubble({ message }: { message: AgentTranscriptMessage }) {
  const text = (message.data.text as string) ?? '';
  if (!text) return null;

  return (
    <div className="flex gap-2">
      <MessageSquare className="h-4 w-4 text-blue-400 mt-0.5 shrink-0" />
      <div className="rounded-lg bg-blue-500/10 border border-blue-500/20 px-3 py-2 max-w-[85%]">
        <p className="text-sm whitespace-pre-wrap">{text}</p>
      </div>
    </div>
  );
}

function ProgressLine({ message }: { message: AgentTranscriptMessage }) {
  const text = (message.data.text as string) ?? '';
  if (!text) return null;

  return (
    <div className="flex items-center gap-2 px-1">
      <Info className="h-3 w-3 text-muted-foreground shrink-0" />
      <span className="text-xs text-muted-foreground italic">{text}</span>
    </div>
  );
}

function ErrorLine({ message }: { message: AgentTranscriptMessage }) {
  const text = (message.data.text as string) ?? (message.data.error as string) ?? '';

  return (
    <div className="flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2">
      <AlertCircle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
      <p className="text-sm text-red-300">{text}</p>
    </div>
  );
}

function ToolResultLine({ message }: { message: AgentTranscriptMessage }) {
  const result = (message.data.result as string) ?? JSON.stringify(message.data);
  const truncated = result.length > 200 ? result.slice(0, 200) + '...' : result;

  return (
    <div className="ml-6 rounded border border-border bg-zinc-900/50 px-3 py-1.5">
      <pre className="text-xs text-muted-foreground whitespace-pre-wrap font-mono">{truncated}</pre>
    </div>
  );
}
