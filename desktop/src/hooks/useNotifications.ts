import { useEffect, useRef } from 'react';
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from '@tauri-apps/plugin-notification';
import { useRunStore } from '@/stores/run.store';
import { usePreferencesStore } from '@/stores/preferences.store';
import { useAppStore } from '@/stores/app.store';
import type { RunStatus } from '@/api/types';

// Desktop Phase 5 — §14.5.3: OS notifications on run completion, failure, and backend crash.
// Only fires if PreferencesStore.showSystemTrayNotifications is true.
// Requests notification permission on first use.

const TERMINAL_STATUSES: RunStatus[] = ['Succeeded', 'Exhausted', 'Cancelled'];

export function useNotifications() {
  const currentStatus = useRunStore((s) => s.currentStatus);
  const backendStatus = useAppStore((s) => s.backendStatus);
  const notificationsEnabled = usePreferencesStore((s) => s.showSystemTrayNotifications);
  const prevStatusRef = useRef<RunStatus | null>(null);
  const prevBackendRef = useRef<string | null>(null);
  const permissionChecked = useRef(false);

  // Request permission on mount (once)
  useEffect(() => {
    if (permissionChecked.current) return;
    permissionChecked.current = true;

    (async () => {
      try {
        const granted = await isPermissionGranted();
        if (!granted) {
          await requestPermission();
        }
      } catch {
        // Notification API not available (e.g. dev mode in browser)
      }
    })();
  }, []);

  // Watch for run status changes → fire notifications
  useEffect(() => {
    if (!notificationsEnabled) {
      prevStatusRef.current = currentStatus;
      return;
    }

    const prev = prevStatusRef.current;
    prevStatusRef.current = currentStatus;

    // Only fire when transitioning TO a terminal status
    if (!currentStatus || !prev) return;
    if (prev === currentStatus) return;
    if (!TERMINAL_STATUSES.includes(currentStatus)) return;

    (async () => {
      try {
        const granted = await isPermissionGranted();
        if (!granted) return;

        if (currentStatus === 'Succeeded') {
          sendNotification({
            title: 'Demiurge',
            body: '✓ Run completed successfully',
          });
        } else if (currentStatus === 'Exhausted') {
          sendNotification({
            title: 'Demiurge',
            body: '✗ Run failed — all attempts exhausted',
          });
        } else if (currentStatus === 'Cancelled') {
          sendNotification({
            title: 'Demiurge',
            body: '⊘ Run cancelled',
          });
        }
      } catch {
        // Notification send failed — ignore
      }
    })();
  }, [currentStatus, notificationsEnabled]);

  // Watch for backend crash
  useEffect(() => {
    if (!notificationsEnabled) {
      prevBackendRef.current = backendStatus;
      return;
    }

    const prev = prevBackendRef.current;
    prevBackendRef.current = backendStatus;

    if (prev === 'connected' && (backendStatus === 'disconnected' || backendStatus === 'error')) {
      (async () => {
        try {
          const granted = await isPermissionGranted();
          if (!granted) return;

          sendNotification({
            title: 'Demiurge',
            body: '⚠ Backend connection lost',
          });
        } catch {
          // ignore
        }
      })();
    }
  }, [backendStatus, notificationsEnabled]);
}
