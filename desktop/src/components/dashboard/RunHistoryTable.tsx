import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { useState } from 'react';
import { queryKeys } from '@/lib/query-keys';
import { getRuns } from '@/api/endpoints';
import { useAppStore } from '@/stores/app.store';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { ElapsedTimer } from '@/components/shared/ElapsedTimer';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import { ErrorState } from '@/components/shared/ErrorState';
import { EmptyState } from '@/components/shared/EmptyState';
import { RUNS_PAGE_SIZE, RUNS_STALE_TIME_MS } from '@/lib/constants';
import { formatRelativeTime } from '@/lib/utils';
import type { RunFilters } from '@/api/types';

type SortField = 'created_at' | 'status' | 'task_text' | 'run_mode';
type SortOrder = 'asc' | 'desc';

function SortIcon({ field, activeField, order }: { field: SortField; activeField: SortField; order: SortOrder }) {
  if (activeField !== field) return null;
  return order === 'asc' ? (
    <ChevronUp className="h-3 w-3" />
  ) : (
    <ChevronDown className="h-3 w-3" />
  );
}

export function RunHistoryTable() {
  const navigate = useNavigate();
  const backendStatus = useAppStore((s) => s.backendStatus);
  const [sortField, setSortField] = useState<SortField>('created_at');
  const [sortOrder, setSortOrder] = useState<SortOrder>('desc');
  const [offset, setOffset] = useState(0);

  const filters: RunFilters = {
    limit: RUNS_PAGE_SIZE,
    offset,
    sort: sortField,
    order: sortOrder,
  };

  const { data, isLoading, isError } = useQuery({
    queryKey: queryKeys.runs.list(filters),
    queryFn: () => getRuns(filters),
    staleTime: RUNS_STALE_TIME_MS,
    enabled: backendStatus === 'connected',
  });

  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortOrder('desc');
    }
    setOffset(0);
  };

  if (backendStatus !== 'connected') {
    return (
      <div className="rounded-lg border border-border bg-card p-4">
        <h3 className="mb-3 text-sm font-medium text-muted-foreground">Recent Runs</h3>
        <EmptyState message="Connect to backend to view runs" />
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-medium text-muted-foreground">Recent Runs</h3>
      </div>

      {isLoading && <LoadingSpinner className="py-12" />}

      {isError && <ErrorState message="Failed to load runs" className="py-8" />}

      {data && data.items.length === 0 && <EmptyState message="No runs yet" />}

      {data && data.items.length > 0 && (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('status')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Status <SortIcon field="status" activeField={sortField} order={sortOrder} />
                    </span>
                  </th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('task_text')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Task <SortIcon field="task_text" activeField={sortField} order={sortOrder} />
                    </span>
                  </th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('run_mode')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Mode <SortIcon field="run_mode" activeField={sortField} order={sortOrder} />
                    </span>
                  </th>
                  <th className="px-4 py-2">Duration</th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('created_at')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Created <SortIcon field="created_at" activeField={sortField} order={sortOrder} />
                    </span>
                  </th>
                  <th className="px-4 py-2">Verdict</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((run) => (
                  <tr
                    key={run.runId}
                    onClick={() => void navigate({ to: '/runs/$runId', params: { runId: run.runId } })}
                    className="cursor-pointer border-b border-border/50 transition-colors hover:bg-accent/50"
                  >
                    <td className="px-4 py-2.5">
                      <StatusBadge status={run.status} size="sm" animated />
                    </td>
                    <td className="max-w-[200px] truncate px-4 py-2.5 font-medium">
                      {run.taskText}
                    </td>
                    <td className="px-4 py-2.5 text-muted-foreground">{run.runMode}</td>
                    <td className="px-4 py-2.5">
                      <ElapsedTimer
                        startedAt={run.startedAt}
                        endedAt={run.endedAt}
                        showIcon={false}
                        className="text-xs"
                      />
                    </td>
                    <td className="px-4 py-2.5 text-xs text-muted-foreground">
                      {formatRelativeTime(run.createdAt)}
                    </td>
                    <td className="px-4 py-2.5">
                      {run.finalVerdict ? (
                        <StatusBadge status={run.finalVerdict} size="sm" />
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between border-t border-border px-4 py-2 text-xs text-muted-foreground">
            <span>
              Showing {offset + 1}–{Math.min(offset + RUNS_PAGE_SIZE, data.total)} of {data.total} runs
            </span>
            <div className="flex gap-2">
              <button
                disabled={offset === 0}
                onClick={() => setOffset(Math.max(0, offset - RUNS_PAGE_SIZE))}
                className="rounded px-2 py-1 hover:bg-accent disabled:opacity-50"
              >
                Previous
              </button>
              <button
                disabled={offset + RUNS_PAGE_SIZE >= data.total}
                onClick={() => setOffset(offset + RUNS_PAGE_SIZE)}
                className="rounded px-2 py-1 hover:bg-accent disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
