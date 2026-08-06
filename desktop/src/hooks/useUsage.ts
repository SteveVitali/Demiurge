import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/auth.store';
import { useAppStore } from '@/stores/app.store';
import { get } from '@/api/client';
import { queryKeys } from '@/lib/query-keys';
import type { UsageData } from '@/lib/usage';

// Re-export for convenience
export type { UsageData } from '@/lib/usage';

// Spec 05 §7.4: Usage data hook for the desktop app
export function useUsage() {
  const licenseKey = useAuthStore((s) => s.licenseKey);
  const backendStatus = useAppStore((s) => s.backendStatus);

  return useQuery<UsageData>({
    queryKey: queryKeys.usage.current(licenseKey),
    queryFn: () => get<UsageData>('/usage'),
    enabled: !!licenseKey && backendStatus === 'connected',
    refetchInterval: 60_000,
    staleTime: 30_000,
  });
}
