import { useEffect } from 'react';
import { Outlet, useNavigate, useLocation } from '@tanstack/react-router';
import { Loader2 } from 'lucide-react';
import { Sidebar } from './Sidebar';
import { useAuthStore } from '@/stores/auth.store';
import { useBackendHealth } from '@/hooks/useBackendHealth';
import { useKeyboardShortcuts } from '@/hooks/useKeyboardShortcuts';
import { useNotifications } from '@/hooks/useNotifications';
import { useTraySync } from '@/hooks/useTraySync';
import { NewRunDialog } from '@/components/dialogs/NewRunDialog';
import { BuildDialog } from '@/components/dialogs/BuildDialog';
import { SmartInitWizard } from '@/components/dialogs/SmartInitWizard';
import { CommandPalette } from '@/components/dialogs/CommandPalette';
import { WelcomeWizard } from '@/components/onboarding/WelcomeWizard';

export function AppLayout() {
  const { isAuthenticated, isLoading, loadCredentials } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => { void loadCredentials(); }, [loadCredentials]);

  useBackendHealth();
  useKeyboardShortcuts();
  useNotifications();
  useTraySync();

  // Auth routes are handled by AuthLayout — just render them through
  const isAuthRoute = location.pathname.startsWith('/auth');
  if (isAuthRoute) {
    return <Outlet />;
  }

  // Show loading spinner while checking auth state
  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-background">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    );
  }

  // Redirect to auth if not authenticated
  if (!isAuthenticated) {
    void navigate({ to: '/auth' });
    return null;
  }

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

      {/* Phase 5: First-run onboarding */}
      <WelcomeWizard />
    </div>
  );
}
