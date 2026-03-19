import { useState, useCallback } from 'react';
import { KeyRound, ExternalLink, Loader2, AlertCircle } from 'lucide-react';
import { useAuthStore } from '@/stores/auth.store';
import { CLOUD_API_URL } from '@/lib/constants';
import { cn } from '@/lib/utils';

type AuthMode = 'choose' | 'license-key' | 'validating';

export function AuthScreen() {
  const [mode, setMode] = useState<AuthMode>('choose');
  const [licenseKey, setLicenseKey] = useState('');
  const [error, setError] = useState<string | null>(null);

  const setCredentials = useAuthStore((s) => s.setCredentials);

  const handleBrowserSignIn = useCallback(async () => {
    try {
      const { open } = await import('@tauri-apps/plugin-shell');
      await open(`${CLOUD_API_URL}/sign-in?redirect_uri=demiurge://auth-callback`);
    } catch {
      // Fallback: open in a new browser tab (dev mode)
      window.open(`${CLOUD_API_URL}/sign-in?redirect_uri=demiurge://auth-callback`, '_blank');
    }
  }, []);

  const handleLicenseKeySubmit = useCallback(async () => {
    if (!licenseKey.trim()) return;
    setError(null);
    setMode('validating');

    try {
      // Validate against cloud backend
      const resp = await fetch(`${CLOUD_API_URL}/api/license/validate`, {
        method: 'GET',
        headers: {
          'X-License-Key': licenseKey.trim(),
          'X-Machine-Fingerprint': 'desktop-app',
        },
      });

      if (resp.ok) {
        const data = await resp.json() as {
          valid: boolean;
          planTier?: string;
          code?: string;
        };
        if (data.valid) {
          await setCredentials(licenseKey.trim(), undefined, data.planTier);
          return; // Auth guard will redirect to dashboard
        } else {
          setError(`License validation failed: ${data.code ?? 'Unknown error'}`);
        }
      } else if (resp.status === 404) {
        setError('License key not found.');
      } else {
        setError(`Validation failed (HTTP ${resp.status})`);
      }
    } catch {
      // Network error — allow offline entry (store key, validate later)
      await setCredentials(licenseKey.trim());
      return;
    }

    setMode('license-key');
  }, [licenseKey, setCredentials]);

  return (
    <div className="flex h-screen items-center justify-center bg-background">
      <div className="flex w-full max-w-md flex-col items-center gap-8 px-6">
        {/* Logo / Title */}
        <div className="flex flex-col items-center gap-3">
          <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-600 text-2xl font-bold text-white">
            D
          </div>
          <h1 className="text-2xl font-bold">Welcome to Demiurge</h1>
          <p className="text-center text-sm text-muted-foreground">
            Last-mile web development automation platform.
            <br />
            Sign in to get started.
          </p>
        </div>

        {/* Auth Options */}
        {mode === 'choose' && (
          <div className="flex w-full flex-col gap-3">
            <button
              onClick={() => void handleBrowserSignIn()}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-blue-600 px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            >
              <ExternalLink className="h-4 w-4" />
              Sign in with browser
            </button>

            <div className="flex items-center gap-3">
              <div className="h-px flex-1 bg-border" />
              <span className="text-xs text-muted-foreground">or</span>
              <div className="h-px flex-1 bg-border" />
            </div>

            <button
              onClick={() => setMode('license-key')}
              className="flex w-full items-center justify-center gap-2 rounded-lg border border-border px-4 py-3 text-sm font-medium transition-colors hover:bg-accent"
            >
              <KeyRound className="h-4 w-4" />
              Enter license key
            </button>
          </div>
        )}

        {/* License Key Entry */}
        {mode === 'license-key' && (
          <div className="flex w-full flex-col gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium">License Key</label>
              <input
                type="text"
                value={licenseKey}
                onChange={(e) => setLicenseKey(e.target.value)}
                placeholder="DEMI-XXXX-XXXX-XXXX"
                className="w-full rounded-lg border border-border bg-background px-4 py-3 text-sm font-mono placeholder:text-muted-foreground focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                autoFocus
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void handleLicenseKeySubmit();
                }}
              />
            </div>

            {error && (
              <div className="flex items-center gap-2 text-sm text-red-400">
                <AlertCircle className="h-4 w-4 shrink-0" />
                {error}
              </div>
            )}

            <div className="flex gap-2">
              <button
                onClick={() => { setMode('choose'); setError(null); }}
                className="flex-1 rounded-lg border border-border px-4 py-2.5 text-sm transition-colors hover:bg-accent"
              >
                Back
              </button>
              <button
                onClick={() => void handleLicenseKeySubmit()}
                disabled={!licenseKey.trim()}
                className={cn(
                  'flex flex-1 items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium',
                  licenseKey.trim()
                    ? 'bg-blue-600 text-white hover:bg-blue-700'
                    : 'cursor-not-allowed bg-blue-500/30 text-blue-400/50',
                )}
              >
                Validate
              </button>
            </div>

            <p className="text-center text-xs text-muted-foreground">
              Get a license key at{' '}
              <a
                href={`${CLOUD_API_URL}/pricing`}
                target="_blank"
                rel="noopener noreferrer"
                className="text-blue-400 hover:underline"
              >
                demiurge.dev/pricing
              </a>
            </p>
          </div>
        )}

        {/* Validating State */}
        {mode === 'validating' && (
          <div className="flex flex-col items-center gap-3">
            <Loader2 className="h-8 w-8 animate-spin text-blue-400" />
            <p className="text-sm text-muted-foreground">Validating license key...</p>
          </div>
        )}
      </div>
    </div>
  );
}
