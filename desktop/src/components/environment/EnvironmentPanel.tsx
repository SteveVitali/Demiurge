import { useState, Suspense, lazy } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Server } from 'lucide-react';
import { queryKeys } from '@/lib/query-keys';
import { getEnvironment, getServices, restartService } from '@/api/endpoints';
import { useServiceLogs } from '@/hooks/useServiceLogs';
import type { DemiurgeWebSocket } from '@/api/websocket';
import type { ServiceSnapshot } from '@/api/types';
import { ServiceCard } from './ServiceCard';
import { LogTailer } from './LogTailer';
import { BootTimeline } from './BootTimeline';
import { LoadingSpinner } from '@/components/shared/LoadingSpinner';

// Desktop Phase 3 — §9.5: Environment panel with service topology, cards, and log tailing.

const ServiceTopology = lazy(() =>
  import('./ServiceTopology').then((mod) => ({ default: mod.ServiceTopology }))
);

interface EnvironmentPanelProps {
  runId: string;
  wsRef: React.RefObject<DemiurgeWebSocket | null>;
}

export function EnvironmentPanel({ runId, wsRef }: EnvironmentPanelProps) {
  const [selectedServiceId, setSelectedServiceId] = useState<string | null>(null);

  const { data: environment, isLoading: envLoading } = useQuery({
    queryKey: queryKeys.environment.snapshot(runId),
    queryFn: () => getEnvironment(runId),
    refetchInterval: 10_000,
  });

  const { data: services, isLoading: svcLoading } = useQuery({
    queryKey: queryKeys.environment.services(runId),
    queryFn: () => getServices(runId),
    refetchInterval: 5_000,
  });

  // Subscribe to log stream for selected service
  useServiceLogs(wsRef, runId, selectedServiceId);

  const handleRestart = async (serviceId: string) => {
    try {
      await restartService(runId, serviceId);
    } catch {
      // TODO: toast notification
    }
  };

  if (envLoading || svcLoading) {
    return <LoadingSpinner size="md" className="flex-1 p-8" />;
  }

  const serviceList: ServiceSnapshot[] = services ?? environment?.services ?? [];

  if (serviceList.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center p-12">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Server className="h-10 w-10" />
          <p className="text-sm font-medium">No Services</p>
          <p className="text-xs">Environment has not been bootstrapped yet</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      {/* Service Topology Graph (§9.5) + Boot Timeline */}
      <div className="shrink-0 border-b border-border p-3 space-y-3">
        <Suspense fallback={<LoadingSpinner size="sm" className="h-64" />}>
          <ServiceTopology
            services={serviceList}
            selectedServiceId={selectedServiceId}
            onSelectService={setSelectedServiceId}
          />
        </Suspense>
        <BootTimeline services={serviceList} />
      </div>

      {/* Service Detail + Log Viewer */}
      <div className="flex flex-1 overflow-hidden">
        {/* Service List Sidebar */}
        <div className="w-64 shrink-0 border-r border-border overflow-y-auto p-3 space-y-2">
          <h3 className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">
            Services ({serviceList.length})
          </h3>
          {serviceList.map((svc) => (
            <ServiceCard
              key={svc.serviceId}
              service={svc}
              isSelected={selectedServiceId === svc.serviceId}
              onSelect={() => setSelectedServiceId(svc.serviceId)}
              onRestart={() => handleRestart(svc.serviceId)}
            />
          ))}
        </div>

        {/* Log Viewer */}
        <div className="flex-1 flex flex-col min-w-0">
          {selectedServiceId ? (
            <>
              <div className="flex items-center gap-2 border-b border-border px-4 py-2">
                <Server className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium">{selectedServiceId}</span>
                <span className="text-xs text-muted-foreground">— Logs</span>
              </div>
              <LogTailer serviceId={selectedServiceId} className="flex-1" />
            </>
          ) : (
            <div className="flex flex-1 items-center justify-center text-muted-foreground">
              <p className="text-sm">Select a service to view logs</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
