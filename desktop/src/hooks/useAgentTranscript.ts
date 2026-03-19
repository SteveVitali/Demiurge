import { useEffect } from 'react';
import type { DemiurgeWebSocket } from '@/api/websocket';
import { useAgentStore } from '@/stores/agent.store';

// Desktop Phase 3 — §11.3: Agent transcript streaming hook.
// Subscribes to agent messages via the shared WebSocket connection.

export function useAgentTranscript(
  wsRef: React.RefObject<DemiurgeWebSocket | null>,
  runId: string,
  active: boolean,
) {
  const setActive = useAgentStore((s) => s.setActive);

  useEffect(() => {
    const ws = wsRef.current;
    if (!ws || !active) {
      return;
    }

    setActive(true);
    ws.subscribeAgent(runId);

    return () => {
      ws.unsubscribeAgent();
      setActive(false);
    };
  }, [wsRef, runId, active, setActive]);
}
