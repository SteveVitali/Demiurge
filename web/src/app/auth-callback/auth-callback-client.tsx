'use client';

import { useEffect } from 'react';
import { useUser } from '@clerk/nextjs';

/**
 * Client component for OAuth callback. After Clerk auth completes:
 * 1. Reads the user's license key from Clerk metadata
 * 2. Deep-links back to the Demiurge desktop app with the auth token
 */
export function AuthCallbackClient() {
  const { user, isLoaded } = useUser();

  useEffect(() => {
    if (!isLoaded || !user) return;

    const licenseKey = (user.publicMetadata as Record<string, string>)?.license_key;
    const scheme = process.env.NEXT_PUBLIC_DESKTOP_DEEP_LINK_SCHEME ?? 'demiurge';

    const deepLinkUrl = `${scheme}://auth-callback?user_id=${encodeURIComponent(user.id)}&license_key=${encodeURIComponent(licenseKey ?? '')}`;
    window.location.href = deepLinkUrl;
  }, [isLoaded, user]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', fontFamily: 'system-ui, sans-serif' }}>
      <h1>Redirecting to Demiurge...</h1>
      <p style={{ color: '#666', marginTop: '1rem' }}>
        If the app doesn&apos;t open automatically,{' '}
        <a href="/" style={{ color: '#0066cc' }}>
          click here to return to the website
        </a>
        .
      </p>
    </div>
  );
}
