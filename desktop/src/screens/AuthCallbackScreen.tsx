import { useEffect, useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { Loader2, AlertCircle } from 'lucide-react';
import { useAuthStore } from '@/stores/auth.store';

/**
 * Handles the demiurge://auth-callback deep link.
 * Extracts license_key, plan_tier, user_email from URL search params,
 * stores credentials, and redirects to the dashboard.
 */
export function AuthCallbackScreen() {
  const navigate = useNavigate();
  const setCredentials = useAuthStore((s) => s.setCredentials);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const licenseKey = params.get('license_key');
    const planTier = params.get('plan_tier') ?? undefined;
    const userEmail = params.get('user_email') ?? undefined;

    if (licenseKey) {
      void setCredentials(licenseKey, userEmail, planTier).then(() => {
        void navigate({ to: '/' });
      });
    } else {
      setError('No license key received from auth callback.');
    }
  }, [navigate, setCredentials]);

  return (
    <div className="flex h-screen items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-4">
        {error ? (
          <>
            <AlertCircle className="h-8 w-8 text-red-400" />
            <p className="text-sm text-red-400">{error}</p>
            <button
              onClick={() => void navigate({ to: '/auth' })}
              className="rounded-lg border border-border px-4 py-2 text-sm hover:bg-accent"
            >
              Back to sign in
            </button>
          </>
        ) : (
          <>
            <Loader2 className="h-8 w-8 animate-spin text-blue-400" />
            <p className="text-sm text-muted-foreground">Completing sign in...</p>
          </>
        )}
      </div>
    </div>
  );
}
