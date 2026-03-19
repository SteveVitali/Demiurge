import { useCallback } from 'react';
import { AlertTriangle, ExternalLink, X } from 'lucide-react';
import { CLOUD_API_URL } from '@/lib/constants';
import { useAuthStore } from '@/stores/auth.store';

// Spec 05 §7.3: Upgrade prompt modal shown when a run fails due to usage limits
interface UpgradeLimitModalProps {
  open: boolean;
  onClose: () => void;
  usesCount?: number;
  planTier?: string;
}

export function UpgradeLimitModal({ open, onClose, usesCount, planTier }: UpgradeLimitModalProps) {
  const currentPlan = useAuthStore((s) => s.planTier) ?? planTier ?? 'Starter';

  const handleUpgrade = useCallback(async () => {
    try {
      const { open: shellOpen } = await import('@tauri-apps/plugin-shell');
      await shellOpen(`${CLOUD_API_URL}/pricing`);
    } catch {
      window.open(`${CLOUD_API_URL}/pricing`, '_blank');
    }
  }, []);

  if (!open) return null;

  const planLabel = currentPlan.charAt(0).toUpperCase() + currentPlan.slice(1);
  const nextPlan = currentPlan.toLowerCase() === 'starter' ? 'Pro' : 'Team';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="relative mx-4 w-full max-w-md rounded-lg border border-border bg-background p-6 shadow-xl">
        <button
          onClick={onClose}
          className="absolute right-3 top-3 rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
          aria-label="Close"
        >
          <X className="h-4 w-4" />
        </button>

        <div className="flex flex-col items-center gap-4 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-yellow-500/10">
            <AlertTriangle className="h-6 w-6 text-yellow-500" />
          </div>

          <h2 className="text-lg font-semibold">Run Limit Reached</h2>

          <p className="text-sm text-muted-foreground">
            You&apos;ve used all{usesCount ? ` ${usesCount}` : ''} runs included
            in your {planLabel} plan this month.
          </p>

          <p className="text-sm text-muted-foreground">
            Upgrade to {nextPlan} for more runs per month.
          </p>

          <div className="flex w-full gap-3 pt-2">
            <button
              onClick={onClose}
              className="flex-1 rounded-md border border-border px-4 py-2 text-sm hover:bg-accent"
            >
              Dismiss
            </button>
            <button
              onClick={() => void handleUpgrade()}
              className="flex flex-1 items-center justify-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              <ExternalLink className="h-3.5 w-3.5" />
              Upgrade to {nextPlan}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
