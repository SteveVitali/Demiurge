import { Outlet } from '@tanstack/react-router';
import { Sidebar } from './Sidebar';
import { useBackendHealth } from '@/hooks/useBackendHealth';

export function AppLayout() {
  useBackendHealth();

  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <main className="flex flex-1 flex-col overflow-y-auto">
        <Outlet />
      </main>
    </div>
  );
}
