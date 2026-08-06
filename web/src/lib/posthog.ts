/**
 * PostHog analytics client — Spec Plan §6 (Telemetry / Analytics).
 *
 * Provides opt-in anonymous telemetry for:
 * - Run count, duration, success rate
 * - Repair attempt count, agent token usage
 * - Verifier types used
 * - Desktop vs CLI usage split
 * - Error rates
 *
 * PostHog is free up to 1M events/mo. All tracking is opt-out capable.
 * The NEXT_PUBLIC_POSTHOG_KEY env var must be set for tracking to be active.
 */

import posthog from 'posthog-js';

let initialized = false;

export function initPostHog() {
  if (initialized) return;
  if (typeof window === 'undefined') return;

  const key = process.env.NEXT_PUBLIC_POSTHOG_KEY;
  if (!key) return;

  posthog.init(key, {
    api_host: process.env.NEXT_PUBLIC_POSTHOG_HOST ?? 'https://us.i.posthog.com',
    person_profiles: 'identified_only',
    capture_pageview: false,
    capture_pageleave: true,
    persistence: 'localStorage+cookie',
    autocapture: false,
    disable_session_recording: true,
    opt_out_capturing_by_default: false,
    loaded: (ph) => {
      // Respect Do Not Track browser setting
      if (navigator.doNotTrack === '1') {
        ph.opt_out_capturing();
      }
    },
  });

  initialized = true;
}

export { posthog };
