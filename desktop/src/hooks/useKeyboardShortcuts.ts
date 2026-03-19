import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useAppStore } from '@/stores/app.store';
import { useRunStore } from '@/stores/run.store';
import { cancelRun, resumeRun } from '@/api/endpoints';

// Desktop Phase 4 — §12.7: Global keyboard shortcuts (all 10 from spec)
export function useKeyboardShortcuts() {
  const navigate = useNavigate();
  const setCommandPaletteOpen = useAppStore((s) => s.setCommandPaletteOpen);
  const setNewRunDialogOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const setBuildDialogOpen = useAppStore((s) => s.setBuildDialogOpen);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Don't intercept shortcuts when typing in form fields
      const target = e.target as HTMLElement;
      if (
        target.tagName === 'INPUT' ||
        target.tagName === 'TEXTAREA' ||
        target.tagName === 'SELECT' ||
        target.isContentEditable
      ) {
        // Only allow Cmd+K (command palette) through from inputs
        if (!(e.key === 'k' && (e.metaKey || e.ctrlKey))) return;
      }

      const meta = e.metaKey || e.ctrlKey;

      // Cmd+K → Command Palette
      if (meta && e.key === 'k') {
        e.preventDefault();
        setCommandPaletteOpen(true);
        return;
      }

      // Cmd+N → New Run Dialog
      if (meta && e.key === 'n') {
        e.preventDefault();
        setNewRunDialogOpen(true);
        return;
      }

      // Cmd+B → Build Dialog
      if (meta && e.key === 'b') {
        e.preventDefault();
        setBuildDialogOpen(true);
        return;
      }

      // Cmd+, → Settings
      if (meta && e.key === ',') {
        e.preventDefault();
        void navigate({ to: '/settings' });
        return;
      }

      // Cmd+1-6 → Switch tabs in Run Detail (§12.7)
      if (meta && e.key >= '1' && e.key <= '6') {
        e.preventDefault();
        const tabIndex = parseInt(e.key, 10) - 1;
        window.dispatchEvent(new CustomEvent('demiurge:switch-tab', { detail: tabIndex }));
        return;
      }

      // Cmd+R → Resume interrupted run (§12.7)
      if (meta && e.key === 'r') {
        e.preventDefault();
        const activeRunId = useAppStore.getState().activeRunId;
        const status = useRunStore.getState().currentStatus;
        if (activeRunId && (status === 'Interrupted' || status === 'ReadyToVerify')) {
          void resumeRun(activeRunId);
        }
        return;
      }

      // Cmd+. → Cancel active run (§12.7)
      if (meta && e.key === '.') {
        e.preventDefault();
        const activeRunId = useAppStore.getState().activeRunId;
        if (activeRunId) {
          void cancelRun(activeRunId);
        }
        return;
      }

      // Cmd+L → Focus log search (§12.7)
      if (meta && !e.shiftKey && e.key === 'l') {
        e.preventDefault();
        const searchInput = document.querySelector<HTMLInputElement>('[data-log-search]');
        searchInput?.focus();
        return;
      }

      // Cmd+Shift+L → Toggle log auto-scroll (§12.7)
      if (meta && e.shiftKey && e.key === 'L') {
        e.preventDefault();
        window.dispatchEvent(new CustomEvent('demiurge:toggle-auto-scroll'));
        return;
      }
    };

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [navigate, setCommandPaletteOpen, setNewRunDialogOpen, setBuildDialogOpen]);
}
