import { Outlet } from '@tanstack/react-router';
import { Sidebar } from './Sidebar';
import { useBackendHealth } from '@/hooks/useBackendHealth';
import { useKeyboardShortcuts } from '@/hooks/useKeyboardShortcuts';
import { NewRunDialog } from '@/components/dialogs/NewRunDialog';
import { BuildDialog } from '@/components/dialogs/BuildDialog';
import { SmartInitWizard } from '@/components/dialogs/SmartInitWizard';
import { CommandPalette } from '@/components/dialogs/CommandPalette';

export function AppLayout() {
  useBackendHealth();
  useKeyboardShortcuts();

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <main className="flex flex-1 flex-col overflow-y-auto">
        <Outlet />
      </main>

      {/* Phase 4: Global dialogs */}
      <NewRunDialog />
      <BuildDialog />
      <SmartInitWizard />
      <CommandPalette />
    </div>
  );
}
