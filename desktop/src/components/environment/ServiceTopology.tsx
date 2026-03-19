import { useCallback, useMemo } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  type Node,
  type Edge,
  type NodeProps,
  Handle,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { ServiceSnapshot } from '@/api/types';
import { ServiceKindIcon } from '@/components/shared/ServiceKindIcon';
import { StatusBadge } from '@/components/shared/StatusBadge';
import { inferServiceKind, isInfraServiceKind } from '@/lib/service-utils';

// Desktop Phase 3 — §9.5: ReactFlow-based service topology graph.
// Nodes = services with health indicators, edges = dependency relationships.

interface ServiceTopologyProps {
  services: ServiceSnapshot[];
  selectedServiceId: string | null;
  onSelectService: (serviceId: string) => void;
}

function ServiceNode({ data }: NodeProps) {
  const svc = data.service as ServiceSnapshot;
  const isSelected = data.isSelected as boolean;
  const onSelect = data.onSelect as (id: string) => void;

  return (
    <div
      onClick={() => onSelect(svc.serviceId)}
      className={`cursor-pointer rounded-lg border-2 bg-background px-4 py-3 shadow-md transition-colors ${
        isSelected ? 'border-blue-500' : 'border-border hover:border-blue-400/50'
      }`}
      style={{ minWidth: 140 }}
    >
      <Handle type="target" position={Position.Left} className="!bg-muted-foreground" />
      <div className="flex items-center gap-2">
        <ServiceKindIcon kind={inferServiceKind(svc)} className="h-4 w-4" />
        <span className="text-sm font-medium truncate">{svc.serviceId}</span>
      </div>
      <div className="mt-1.5 flex items-center gap-2">
        <StatusBadge status={svc.status} size="sm" />
        {svc.pid && (
          <span className="text-[10px] text-muted-foreground">PID {svc.pid}</span>
        )}
      </div>
      <Handle type="source" position={Position.Right} className="!bg-muted-foreground" />
    </div>
  );
}

const nodeTypes = { serviceNode: ServiceNode };

export function ServiceTopology({ services, selectedServiceId, onSelectService }: ServiceTopologyProps) {
  const nodes: Node[] = useMemo(() => {
    return services.map((svc, i) => ({
      id: svc.serviceId,
      type: 'serviceNode',
      position: { x: (i % 3) * 220, y: Math.floor(i / 3) * 120 },
      data: {
        service: svc,
        isSelected: svc.serviceId === selectedServiceId,
        onSelect: onSelectService,
      },
    }));
  }, [services, selectedServiceId, onSelectService]);

  const edges: Edge[] = useMemo(() => {
    // Infer dependency edges: databases/caches are depended upon by API/frontend services
    const infraServices = services.filter(s => isInfraServiceKind(inferServiceKind(s)));
    const appServices = services.filter(s => !isInfraServiceKind(inferServiceKind(s)));

    const result: Edge[] = [];
    for (const app of appServices) {
      for (const infra of infraServices) {
        result.push({
          id: `${infra.serviceId}->${app.serviceId}`,
          source: infra.serviceId,
          target: app.serviceId,
          animated: app.status === 'RunningHealthy',
          style: { stroke: 'hsl(var(--muted-foreground))' },
        });
      }
    }
    return result;
  }, [services]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    onSelectService(node.id);
  }, [onSelectService]);

  if (services.length === 0) return null;

  return (
    <div className="h-64 w-full rounded-md border border-border bg-background/50">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        fitView
        proOptions={{ hideAttribution: true }}
        className="rounded-md"
      >
        <Background color="hsl(var(--muted-foreground) / 0.1)" gap={16} />
        <Controls showInteractive={false} className="!bg-background !border-border" />
      </ReactFlow>
    </div>
  );
}
