'use client';

import { useEffect } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { initPostHog, posthog } from '@/lib/posthog';

/**
 * PostHog analytics provider — initializes on mount and tracks page views.
 * Spec Plan §6: Telemetry / Analytics.
 *
 * Only active when NEXT_PUBLIC_POSTHOG_KEY is set.
 * Respects Do Not Track browser setting.
 */
export function PostHogProvider({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const searchParams = useSearchParams();

  useEffect(() => {
    initPostHog();
  }, []);

  useEffect(() => {
    if (pathname && posthog.__loaded) {
      let url = window.origin + pathname;
      const search = searchParams?.toString();
      if (search) url += '?' + search;
      posthog.capture('$pageview', { $current_url: url });
    }
  }, [pathname, searchParams]);

  return <>{children}</>;
}
