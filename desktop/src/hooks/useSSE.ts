import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { SSEClient } from '@/api/sse';
import { useRunStore } from '@/stores/run.store';
import { queryKeys } from '@/lib/query-keys';
import type { SystemEvent } from '@/api/types';

export function useSSE(runId: string | null) {
  const queryClient = useQueryClient();
  const handleEvent = useRunStore((s) => s.handleEvent);
  const setSSEStatus = useRunStore((s) => s.setSSEStatus);
  const clientRef = useRef<SSEClient | null>(null);

  useEffect(() => {
    if (!runId) {
      setSSEStatus('disconnected');
      return;
    }

    const onEvent = (event: SystemEvent) => {
      handleEvent(event);

      switch (event.eventType) {
        case 'state_transition':
          void queryClient.invalidateQueries({ queryKey: queryKeys.runs.detail(runId) });
          break;
        case 'verdict_produced':
          void queryClient.invalidateQueries({ queryKey: queryKeys.attempts.list(runId) });
          break;
        case 'artifact_created':
          void queryClient.invalidateQueries({ queryKey: ['runs', runId, 'artifacts'] });
          break;
        case 'service_status_changed':
          void queryClient.invalidateQueries({ queryKey: queryKeys.environment.services(runId) });
          break;
      }
    };

    const client = new SSEClient(runId, onEvent, setSSEStatus);
    clientRef.current = client;
    client.connect();

    return () => {
      client.disconnect();
      clientRef.current = null;
    };
  }, [runId, handleEvent, setSSEStatus, queryClient]);
}
