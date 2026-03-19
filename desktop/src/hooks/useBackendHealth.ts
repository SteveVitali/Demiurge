import { useEffect, useRef } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { useAppStore } from '@/stores/app.store';
import { HEALTH_POLL_INTERVAL_MS } from '@/lib/constants';

export function useBackendHealth() {
  const setBackendStatus = useAppStore((s) => s.setBackendStatus);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const startTriggered = useRef(false);
  const hasConnectedRef = useRef(false);

  useEffect(() => {
    const check = async () => {
      try {
        // Health check via Tauri invoke (Rust-side HTTP) — bypasses webview restrictions
        const healthy = await invoke<boolean>('check_backend_health');
        if (healthy) {
          setBackendStatus('connected');
          hasConnectedRef.current = true;
        } else {
          setBackendStatus(hasConnectedRef.current ? 'disconnected' : 'connecting');
          if (!startTriggered.current) {
            startTriggered.current = true;
            invoke('start_backend').catch(() => {});
          }
        }
      } catch {
        setBackendStatus(hasConnectedRef.current ? 'disconnected' : 'connecting');
        if (!startTriggered.current) {
          startTriggered.current = true;
          invoke('start_backend').catch(() => {});
        }
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
