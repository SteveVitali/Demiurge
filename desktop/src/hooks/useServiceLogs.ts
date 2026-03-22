import { useEffect, useRef } from 'react';
import type { DemiurgeWebSocket } from '@/api/websocket';
import { useLogsStore } from '@/stores/logs.store';

// Desktop Phase 3 — §11.2: Service log tailing hook.
// Subscribes to a service's log stream via the shared WebSocket connection.

export function useServiceLogs(
  wsRef: React.RefObject<DemiurgeWebSocket | null>,
  runId: string,
  serviceId: string | null,
) {
  const prevServiceRef = useRef<string | null>(null);
  const setActiveService = useLogsStore((s) => s.setActiveService);

  useEffect(() => {
    const ws = wsRef.current;
    if (!ws || !serviceId) {
      setActiveService(null);
      return;
    }

    // Unsubscribe from previous service
    if (prevServiceRef.current && prevServiceRef.current !== serviceId) {
      ws.unsubscribeLogs(prevServiceRef.current);
    }

    setActiveService(serviceId);
    ws.subscribeLogs(runId, serviceId);
    prevServiceRef.current = serviceId;

    return () => {
      if (serviceId) {
        ws.unsubscribeLogs(serviceId);
      }
      prevServiceRef.current = null;
    };
  }, [wsRef, runId, serviceId, setActiveService]);
}
