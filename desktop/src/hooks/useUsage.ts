import { useQuery } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/auth.store';
import { get } from '@/api/client';
import { useAppStore } from '@/stores/app.store';

// Spec 05 §7.4: Usage data hook for the desktop app
export interface UsageData {
  runs: { used: number; limit: number; periodEnd: string | null };
  tokens: { used: number; limit: number; periodEnd: string | null };
  account?: { email: string; planTier: string; entitlements: string[] };
  offline?: boolean;
}

export function useUsage() {
  const licenseKey = useAuthStore((s) => s.licenseKey);
  const backendStatus = useAppStore((s) => s.backendStatus);

  return useQuery<UsageData>({
    queryKey: ['usage', licenseKey],
    queryFn: () => get<UsageData>('/usage'),
    enabled: !!licenseKey && backendStatus === 'connected',
    refetchInterval: 60_000, // Refresh every minute
    staleTime: 30_000,
  });
}
