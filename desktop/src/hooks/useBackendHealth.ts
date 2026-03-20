import { useEffect, useRef } from 'react';
import { useAppStore } from '@/stores/app.store';
import { getHealth } from '@/api/endpoints';
import { HEALTH_POLL_INTERVAL_MS } from '@/lib/constants';

export function useBackendHealth() {
  const setBackendStatus = useAppStore((s) => s.setBackendStatus);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const check = async () => {
      try {
        await getHealth();
        setBackendStatus('connected');
      } catch {
        setBackendStatus('disconnected');
      }
    };

    void check();
    intervalRef.current = setInterval(() => void check(), HEALTH_POLL_INTERVAL_MS);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [setBackendStatus]);
}
