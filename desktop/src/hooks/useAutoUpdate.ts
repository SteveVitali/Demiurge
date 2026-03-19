import { check, type Update } from '@tauri-apps/plugin-updater';
import { relaunch } from '@tauri-apps/plugin-process';
import { useState, useEffect, useRef, useCallback } from 'react';

export function useAutoUpdate() {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [updateVersion, setUpdateVersion] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const pendingUpdate = useRef<Update | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function doCheck() {
      try {
        const update = await check();
        if (cancelled) return;
        if (update) {
          pendingUpdate.current = update;
          setUpdateAvailable(true);
          setUpdateVersion(update.version);
        }
      } catch (e) {
        if (!cancelled) {
          console.warn('Update check failed:', e);
        }
      }
    }

    doCheck();
    return () => { cancelled = true; };
  }, []);

  const installUpdate = useCallback(async () => {
    const update = pendingUpdate.current;
    if (!update) return;

    try {
      setIsUpdating(true);
      setUpdateError(null);
      await update.downloadAndInstall();
      await relaunch();
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      console.error('Update failed:', message);
      setUpdateError(message);
      setIsUpdating(false);
    }
  }, []);

  return { updateAvailable, updateVersion, isUpdating, updateError, installUpdate };
}
