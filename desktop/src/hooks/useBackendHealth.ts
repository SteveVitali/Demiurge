import { useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { useAppStore } from '@/stores/app.store';
import { getHealth } from '@/api/endpoints';
import { HEALTH_POLL_INTERVAL_MS } from '@/lib/constants';

export function useBackendHealth() {
  const setBackendStatus = useAppStore((s) => s.setBackendStatus);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startingRef = useRef(false);
  const hasConnectedRef = useRef(false);

  useEffect(() => {
    const ensureBackendStarted = async () => {
      if (startingRef.current) return;
      startingRef.current = true;
      try {
        await invoke('start_backend');
      } catch {
        // start_backend returns Err when backend is unreachable — that's fine,
        // the health poll will keep retrying
      } finally {
        startingRef.current = false;
      }
    };

    const check = async () => {
      try {
        await getHealth();
        setBackendStatus('connected');
        hasConnectedRef.current = true;
      } catch {
        // Show 'connecting' until we've connected at least once, then 'disconnected'
        setBackendStatus(hasConnectedRef.current ? 'disconnected' : 'connecting');
        void ensureBackendStarted();
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
