import { check } from '@tauri-apps/plugin-updater';
import { relaunch } from '@tauri-apps/plugin-process';
import { useState, useEffect } from 'react';

export function useAutoUpdate() {
  const [updateAvailable, setUpdateAvailable] = useState(false);
  const [updateVersion, setUpdateVersion] = useState<string | null>(null);
  const [isUpdating, setIsUpdating] = useState(false);

  useEffect(() => {
    checkForUpdate();
  }, []);

  async function checkForUpdate() {
    try {
      const update = await check();
      if (update) {
        setUpdateAvailable(true);
        setUpdateVersion(update.version);
      }
    } catch (e) {
      console.warn('Update check failed:', e);
    }
  }

  async function installUpdate() {
    try {
      setIsUpdating(true);
      const update = await check();
      if (update) {
        await update.downloadAndInstall();
        await relaunch();
      }
    } catch (e) {
      console.error('Update failed:', e);
      setIsUpdating(false);
    }
  }

  return { updateAvailable, updateVersion, isUpdating, installUpdate };
}
