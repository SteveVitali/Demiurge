'use client';

import { useEffect, useState } from 'react';

export type Platform = 'mac-arm' | 'mac-intel' | 'windows' | 'linux' | 'unknown';

export function useDetectedPlatform(): Platform {
  const [platform, setPlatform] = useState<Platform>('unknown');

  useEffect(() => {
    const ua = navigator.userAgent.toLowerCase();
    const plat = (navigator.platform ?? '').toLowerCase();

    if (ua.includes('mac') || plat.includes('mac')) {
      // Attempt to detect Apple Silicon via GPU or userAgentData
      const isArm =
        plat.includes('arm') ||
        ua.includes('arm') ||
        // Safari on Apple Silicon doesn't always expose "arm" in UA
        // but navigator.userAgentData might
        (typeof navigator !== 'undefined' &&
          'userAgentData' in navigator &&
          // @ts-expect-error — userAgentData not in all TS lib definitions
          navigator.userAgentData?.platform === 'macOS');
      // Default to ARM since most new Macs are Apple Silicon
      setPlatform(isArm || !ua.includes('intel') ? 'mac-arm' : 'mac-intel');
    } else if (ua.includes('win')) {
      setPlatform('windows');
    } else if (ua.includes('linux')) {
      setPlatform('linux');
    }
  }, []);

  return platform;
}
