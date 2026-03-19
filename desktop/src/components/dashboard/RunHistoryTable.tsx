import { useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronUp, Inbox } from 'lucide-react';
import { useState } from 'react';
import { queryKeys } from '@/lib/query-keys';
import { getRuns } from '@/api/endpoints';
import { useAppStore } from '@/stores/app.store';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { ElapsedTimer } from '@/components/shared/ElapsedTimer';
import { RUNS_PAGE_SIZE, RUNS_STALE_TIME_MS } from '@/lib/constants';
import { formatRelativeTime } from '@/lib/utils';
import type { RunFilters } from '@/api/types';

type SortField = 'created_at' | 'status' | 'task_text' | 'run_mode';
type SortOrder = 'asc' | 'desc';

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

  const SortIcon = ({ field }: { field: SortField }) => {
    if (sortField !== field) return null;
    return sortOrder === 'asc' ? (
      <ChevronUp className="h-3 w-3" />
    ) : (
      <ChevronDown className="h-3 w-3" />
    );
  };

  if (backendStatus !== 'connected') {
    return (
      <div className="rounded-lg border border-border bg-card p-4">
        <h3 className="mb-3 text-sm font-medium text-muted-foreground">Recent Runs</h3>
        <div className="flex flex-col items-center gap-2 py-8 text-muted-foreground">
          <Inbox className="h-8 w-8" />
          <p className="text-sm">Connect to backend to view runs</p>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="border-b border-border px-4 py-3">
        <h3 className="text-sm font-medium text-muted-foreground">Recent Runs</h3>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted-foreground border-t-transparent" />
        </div>
      )}

      {isError && (
        <div className="flex flex-col items-center gap-2 py-8 text-red-400">
          <p className="text-sm">Failed to load runs</p>
        </div>
      )}

      {data && data.items.length === 0 && (
        <div className="flex flex-col items-center gap-2 py-8 text-muted-foreground">
          <Inbox className="h-8 w-8" />
          <p className="text-sm">No runs yet</p>
        </div>
      )}

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
                      Status <SortIcon field="status" />
                    </span>
                  </th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('task_text')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Task <SortIcon field="task_text" />
                    </span>
                  </th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('run_mode')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Mode <SortIcon field="run_mode" />
                    </span>
                  </th>
                  <th className="px-4 py-2">Duration</th>
                  <th
                    className="cursor-pointer px-4 py-2 hover:text-foreground"
                    onClick={() => toggleSort('created_at')}
                  >
                    <span className="inline-flex items-center gap-1">
                      Created <SortIcon field="created_at" />
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
