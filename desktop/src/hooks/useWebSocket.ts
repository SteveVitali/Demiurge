import { useEffect, useRef, useCallback } from 'react';
import { DemiurgeWebSocket, type WsIncomingMessage, type WsStatus } from '@/api/websocket';
import { useLogsStore } from '@/stores/logs.store';
import { useAgentStore } from '@/stores/agent.store';

// Desktop Phase 3 — §7.4: WebSocket connection lifecycle hook.
// Manages a single WS connection per run, dispatches messages to stores.

export function useWebSocket(runId: string | null) {
  const wsRef = useRef<DemiurgeWebSocket | null>(null);

  const backfill = useLogsStore((s) => s.backfill);
  const appendLine = useLogsStore((s) => s.appendLine);
  const appendAgentMessage = useAgentStore((s) => s.appendMessage);

  const handleMessage = useCallback((msg: WsIncomingMessage) => {
    switch (msg.type) {
      case 'log_backfill':
        backfill(msg.serviceId, msg.lines);
        break;
      case 'log_line':
        appendLine(msg.serviceId, msg.line);
        break;
      case 'agent_message':
        appendAgentMessage({
          id: `ws-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          messageType: msg.messageType,
          data: msg.data,
          timestamp: msg.timestamp,
        });
        break;
      case 'heartbeat':
      case 'pong':
        // no-op, connection is alive
        break;
    }
  }, [backfill, appendLine, appendAgentMessage]);

  const handleStatus = useCallback((_status: WsStatus) => {
    // Could dispatch to a store if needed
  }, []);

  const resetLogs = useLogsStore((s) => s.reset);
  const resetAgent = useAgentStore((s) => s.reset);

  useEffect(() => {
    if (!runId) {
      if (wsRef.current) {
        wsRef.current.disconnect();
        wsRef.current = null;
      }
      resetLogs();
      resetAgent();
      return;
    }

    const ws = new DemiurgeWebSocket(handleMessage, handleStatus);
    wsRef.current = ws;
    ws.connect();

    return () => {
      ws.disconnect();
      wsRef.current = null;
      resetLogs();
      resetAgent();
    };
  }, [runId, handleMessage, handleStatus, resetLogs, resetAgent]);

  return wsRef;
}
