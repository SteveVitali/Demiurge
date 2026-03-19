import { Outlet } from '@tanstack/react-router';

/**
 * Minimal layout for auth routes (/auth, /auth-callback).
 * No sidebar, no auth guard — just renders the child route.
 */
export function AuthLayout() {
  return <Outlet />;
}
