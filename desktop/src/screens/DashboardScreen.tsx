import { SystemHealthWidget } from '@/components/dashboard/SystemHealthWidget';
import { QuickActions } from '@/components/dashboard/QuickActions';
import { RunHistoryTable } from '@/components/dashboard/RunHistoryTable';

export function DashboardScreen() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-6">
      <h1 className="text-lg font-semibold">Dashboard</h1>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <SystemHealthWidget />
        <QuickActions />
      </div>

      <RunHistoryTable />
    </div>
  );
}
