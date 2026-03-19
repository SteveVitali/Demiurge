import { lazy, Suspense, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { getRequirementGraph } from '@/api/endpoints';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';
import type { RequirementGraph as RequirementGraphType } from '@/api/types';

import '@xyflow/react/dist/style.css';

const ReactFlow = lazy(() => import('@xyflow/react').then((m) => ({ default: m.ReactFlow })));
const Background = lazy(() => import('@xyflow/react').then((m) => ({ default: m.Background })));
const Controls = lazy(() => import('@xyflow/react').then((m) => ({ default: m.Controls })));

interface RequirementGraphProps {
  runId: string;
  open: boolean;
  onClose: () => void;
}

function getNodeColor(status: string | null): string {
  switch (status) {
    case 'Pass': return '#10b981';
    case 'Fail': return '#ef4444';
    case 'Flake': return '#eab308';
    case 'Inconclusive': return '#71717a';
    case 'Blocked': return '#64748b';
    case 'Timeout': return '#f97316';
    default: return '#3f3f46';
  }
}

function getPriorityBorder(priority: string): string {
  switch (priority) {
    case 'Required': return '#ef4444';
    case 'Important': return '#eab308';
    default: return '#71717a';
  }
}

function getEdgeStyle(type: string): { strokeDasharray?: string; stroke: string } {
  switch (type) {
    case 'Hard': return { stroke: '#71717a' };
    case 'Soft': return { strokeDasharray: '5 5', stroke: '#52525b' };
    case 'Ordering': return { strokeDasharray: '2 2', stroke: '#3f3f46' };
    default: return { stroke: '#3f3f46' };
  }
}

function GraphContent({ graph }: { graph: RequirementGraphType }) {
  const nodes = useMemo(
    () =>
      graph.nodes.map((node, i) => ({
        id: node.requirementId,
        position: { x: (i % 4) * 250, y: Math.floor(i / 4) * 120 },
        data: {
          label: (
            <div className="text-xs px-1">
              <div className="font-medium truncate max-w-[180px]">{node.requirementId}</div>
              <div className="text-[10px] opacity-70">{node.priority} · {node.category}</div>
            </div>
          ),
        },
        style: {
          background: getNodeColor(node.verdictStatus),
          border: `2px solid ${getPriorityBorder(node.priority)}`,
          borderRadius: '8px',
          color: 'white',
          padding: '8px',
          minWidth: 160,
        },
      })),
    [graph.nodes],
  );

  const edges = useMemo(
    () =>
      graph.edges.map((edge, i) => ({
        id: `e-${i}`,
        source: edge.from,
        target: edge.to,
        style: getEdgeStyle(edge.type),
        animated: edge.type === 'Hard',
        label: edge.type,
        labelStyle: { fontSize: 10, fill: '#71717a' },
      })),
    [graph.edges],
  );

  return (
    <div className="h-full w-full">
      <Suspense fallback={<LoadingSpinner size="lg" className="flex-1" />}>
        <ReactFlow nodes={nodes} edges={edges} fitView>
          <Background />
          <Controls />
        </ReactFlow>
      </Suspense>
    </div>
  );
}

export function RequirementGraphModal({ runId, open, onClose }: RequirementGraphProps) {
  const { data: graph, isLoading, isError } = useQuery({
    queryKey: queryKeys.inspection.graph(runId),
    queryFn: () => getRequirementGraph(runId),
    enabled: open,
  });

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="relative flex h-[80vh] w-[90vw] max-w-5xl flex-col rounded-lg border border-border bg-card shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <h3 className="text-sm font-semibold">Requirement Dependency Graph</h3>
          <button
            onClick={onClose}
            className="rounded p-1 hover:bg-muted transition-colors"
          >
            <X size={16} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-hidden">
          {isLoading && <LoadingSpinner size="lg" className="flex-1 h-full" />}
          {isError && (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
              Failed to load requirement graph
            </div>
          )}
          {graph && <GraphContent graph={graph} />}
        </div>
      </div>
    </div>
  );
}
