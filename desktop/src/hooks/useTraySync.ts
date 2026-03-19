import { useEffect, useRef } from 'react';
import { emit, listen } from '@tauri-apps/api/event';
import { useRunStore } from '@/stores/run.store';
import { useAppStore } from '@/stores/app.store';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { getRuns } from '@/api/endpoints';
import type { RunStatus, TaskRun } from '@/api/types';

// Desktop Phase 5 — §12.4: Sync run state to the system tray via Tauri events.
// Emits tray:run-state-changed and tray:recent-runs-updated events.
// Listens for tray:navigate-to-run, tray:open-new-run, tray:open-settings events.

const RUNNING_STATUSES: RunStatus[] = [
  'Created', 'InspectingRepo', 'CompilingRequirements', 'PlanningEnvironment',
  'BootstrappingEnvironment', 'SeedingFixtures', 'BootstrappingAuth', 'ReadyToVerify',
  'Verifying', 'AnalyzingFailure', 'PlanningRepair', 'Repairing', 'PlanningRerun',
  'SoftResettingEnvironment', 'RebuildingEnvironment', 'PlanningFeature', 'GeneratingCode',
];

const SUCCESS_STATUSES: RunStatus[] = ['Succeeded'];
const FAILURE_STATUSES: RunStatus[] = ['Exhausted', 'RepairFailed', 'EnvironmentFailed'];

function mapStatus(status: RunStatus | null): string {
  if (!status) return 'idle';
  if (RUNNING_STATUSES.includes(status)) return 'running';
  if (SUCCESS_STATUSES.includes(status)) return 'succeeded';
  if (FAILURE_STATUSES.includes(status)) return 'failed';
  return 'idle';
}

export function useTraySync() {
  const currentStatus = useRunStore((s) => s.currentStatus);
  const activeRunId = useAppStore((s) => s.activeRunId);
  const setNewRunDialogOpen = useAppStore((s) => s.setNewRunDialogOpen);
  const navigate = useNavigate();
  const prevStatusRef = useRef<string | null>(null);

  // Poll recent runs (low frequency) so tray submenu stays up to date
  const { data: runsData } = useQuery({
    queryKey: ['runs', 'tray-sync'],
    queryFn: () => getRuns({ limit: 5 }),
    refetchInterval: 15_000,
    staleTime: 10_000,
  });

  // Resolve the active run's taskText for the tray tooltip
  const activeRun: TaskRun | undefined = runsData?.items?.find(
    (r) => r.runId === activeRunId,
  );

  // Sync run state to tray
  useEffect(() => {
    const mapped = mapStatus(currentStatus);
    if (mapped === prevStatusRef.current) return;
    prevStatusRef.current = mapped;

    emit('tray:run-state-changed', {
      status: mapped,
      task: activeRun?.taskText ?? null,
      elapsed: null,
      run_id: activeRunId,
    }).catch(() => {});
  }, [currentStatus, activeRunId, activeRun?.taskText]);

  // Sync recent runs to tray when query data changes
  useEffect(() => {
    if (!runsData?.items) return;

    const recentRuns = runsData.items.slice(0, 5).map((r) => ({
      run_id: r.runId,
      task: r.taskText,
      verdict: r.finalVerdict ?? r.status,
    }));

    emit('tray:recent-runs-updated', { runs: recentRuns }).catch(() => {});
  }, [runsData]);

  // Listen for tray menu events
  useEffect(() => {
    const unlisteners: Array<() => void> = [];

    listen<string>('tray:navigate-to-run', (event) => {
      const runId = event.payload;
      if (runId) {
        navigate({ to: '/runs/$runId', params: { runId } });
      }
    }).then((unlisten) => unlisteners.push(unlisten));

    listen('tray:open-new-run', () => {
      setNewRunDialogOpen(true);
    }).then((unlisten) => unlisteners.push(unlisten));

    listen('tray:open-settings', () => {
      navigate({ to: '/settings' });
    }).then((unlisten) => unlisteners.push(unlisten));

    return () => {
      unlisteners.forEach((fn) => fn());
    };
  }, [navigate, setNewRunDialogOpen]);
}
