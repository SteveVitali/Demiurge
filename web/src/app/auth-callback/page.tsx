import { AuthCallbackClient } from './auth-callback-client';

export const dynamic = 'force-dynamic';

/**
 * OAuth callback page (server component wrapper).
 * After Clerk auth, deep-links back to the Demiurge desktop app.
 * For CLI device-code flow, users are redirected to /activate instead.
 */
export default function AuthCallbackPage() {
  return <AuthCallbackClient />;
}
