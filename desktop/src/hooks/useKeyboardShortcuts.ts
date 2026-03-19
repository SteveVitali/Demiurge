import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useAppStore } from '@/stores/app.store';

// Desktop Phase 4 — §12.7: Global keyboard shortcuts
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

      // Escape → Close modals (handled by individual dialog components)
    };

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [navigate, setCommandPaletteOpen, setNewRunDialogOpen, setBuildDialogOpen]);
}
