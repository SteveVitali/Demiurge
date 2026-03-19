import { useEffect, useRef, useCallback } from 'react';
import { useParams } from '@tanstack/react-router';
import { LogTailer } from '@/components/environment/LogTailer';
import { useLogsStore } from '@/stores/logs.store';
import { DemiurgeWebSocket, type WsIncomingMessage, type WsStatus } from '@/api/websocket';

// Desktop Phase 5 — §12.5: Standalone detached log screen.
// Rendered in a secondary native window created by create_log_window IPC command.
// Contains only a LogTailer with its own WebSocket connection. No sidebar, no navigation.

export function DetachedLogScreen() {
  const { runId, serviceId } = useParams({ strict: false }) as {
    runId: string;
    serviceId: string;
  };

  const wsRef = useRef<DemiurgeWebSocket | null>(null);
  const backfill = useLogsStore((s) => s.backfill);
  const appendLine = useLogsStore((s) => s.appendLine);

  const handleMessage = useCallback((msg: WsIncomingMessage) => {
    if (msg.type === 'log_backfill') {
      backfill(msg.serviceId, msg.lines);
    } else if (msg.type === 'log_line') {
      appendLine(msg.serviceId, msg.line);
    }
  }, [backfill, appendLine]);

  const handleStatus = useCallback((_status: WsStatus) => {}, []);

  // Connect an independent WebSocket and subscribe to the specific service logs
  useEffect(() => {
    if (!runId || !serviceId) return;

    const ws = new DemiurgeWebSocket(handleMessage, handleStatus);
    wsRef.current = ws;
    ws.connect();

    // Subscribe once connected — DemiurgeWebSocket handles resubscribe on reconnect
    // We need a small delay to let the WS connect first
    const subTimer = setTimeout(() => {
      ws.subscribeLogs(runId, serviceId);
    }, 500);

    return () => {
      clearTimeout(subTimer);
      ws.disconnect();
      wsRef.current = null;
    };
  }, [runId, serviceId, handleMessage, handleStatus]);

  if (!runId || !serviceId) {
    return (
      <div className="flex h-screen items-center justify-center bg-background text-muted-foreground">
        <p>Missing run or service ID</p>
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col bg-background">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <h1 className="text-sm font-medium text-foreground">
          Logs: <span className="text-muted-foreground">{serviceId}</span>
        </h1>
        <span className="text-xs text-muted-foreground">Run {runId.slice(0, 8)}</span>
      </div>
      <LogTailer serviceId={serviceId} className="flex-1" />
    </div>
  );
}
