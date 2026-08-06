'use client';

import { useEffect } from 'react';
import { useUser } from '@clerk/nextjs';
import { Copy, Check } from 'lucide-react';
import { useCopyToClipboard } from '@/hooks/useCopyToClipboard';

/**
 * Client component for OAuth callback. After Clerk auth completes:
 * 1. Reads the user's license key from Clerk metadata
 * 2. Deep-links back to the Demiurge desktop app with the auth token
 * 3. Shows fallback with license key copy if deep link doesn't work
 */
export function AuthCallbackClient() {
  const { user, isLoaded } = useUser();
  const { copied, copy } = useCopyToClipboard();

  const licenseKey = isLoaded
    ? ((user?.publicMetadata as Record<string, string>)?.license_key ?? '')
    : '';

  useEffect(() => {
    if (!isLoaded || !user) return;

    const key = (user.publicMetadata as Record<string, string>)?.license_key ?? '';
    const planTier = (user.publicMetadata as Record<string, string>)?.plan_tier ?? '';
    const email = user.emailAddresses[0]?.emailAddress ?? '';
    const scheme = process.env.NEXT_PUBLIC_DESKTOP_DEEP_LINK_SCHEME ?? 'demiurge';

    const deepLinkUrl = `${scheme}://auth-callback?license_key=${encodeURIComponent(key)}&plan_tier=${encodeURIComponent(planTier)}&email=${encodeURIComponent(email)}`;
    window.location.href = deepLinkUrl;
  }, [isLoaded, user]);

  function handleCopy() {
    if (licenseKey) copy(licenseKey);
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-[60vh] p-8">
      <h1 className="text-2xl font-bold text-text-primary mb-2">Redirecting to Demiurge...</h1>
      <p className="text-text-secondary mt-4 text-center max-w-md">
        If the app didn&apos;t open automatically, copy your license key and paste it in the app:
      </p>
      {licenseKey && (
        <div className="mt-4 flex items-center gap-3">
          <code className="rounded-lg bg-surface border border-border px-4 py-2.5 text-sm font-mono text-text-secondary">
            {licenseKey}
          </code>
          <button
            onClick={handleCopy}
            className="shrink-0 rounded-lg border border-border p-2.5 text-text-muted hover:text-text-primary hover:border-border-light transition-colors cursor-pointer"
            aria-label="Copy license key"
          >
            {copied ? <Check className="h-4 w-4 text-success" /> : <Copy className="h-4 w-4" />}
          </button>
        </div>
      )}
      <a
        href="/"
        className="mt-6 text-sm text-primary hover:text-primary-light transition-colors"
      >
        Return to website
      </a>
    </div>
  );
}
