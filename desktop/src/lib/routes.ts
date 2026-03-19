import {
  createRouter,
  createRoute,
  createRootRoute,
} from '@tanstack/react-router';
import { AppLayout } from '@/components/layout/AppLayout';
import { AuthLayout } from '@/components/layout/AuthLayout';
import { DashboardScreen } from '@/screens/DashboardScreen';
import { RunDetailScreen } from '@/screens/RunDetailScreen';
import { ConfigScreen } from '@/screens/ConfigScreen';
import { SettingsScreen } from '@/screens/SettingsScreen';
import { DetachedLogScreen } from '@/screens/DetachedLogScreen';
import { AuthScreen } from '@/screens/AuthScreen';
import { AuthCallbackScreen } from '@/screens/AuthCallbackScreen';

const rootRoute = createRootRoute({
  component: AppLayout,
});

// Auth routes use a minimal layout (no sidebar, no auth guard)
const authLayoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'auth-layout',
  component: AuthLayout,
});

const authRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  path: '/auth',
  component: AuthScreen,
});

const authCallbackRoute = createRoute({
  getParentRoute: () => authLayoutRoute,
  path: '/auth-callback',
  component: AuthCallbackScreen,
});

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: DashboardScreen,
});

const runDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/runs/$runId',
  component: RunDetailScreen,
});

// Desktop Phase 4: Config screen with YAML editor and validation
const configRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/config',
  component: ConfigScreen,
});

// Desktop Phase 4: Settings screen with API key management and preferences
const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings',
  component: SettingsScreen,
});

// Desktop Phase 5: Detached log window route — rendered in secondary native windows
const detachedLogRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/logs/$runId/$serviceId',
  component: DetachedLogScreen,
});

const routeTree = rootRoute.addChildren([
  authLayoutRoute.addChildren([authRoute, authCallbackRoute]),
  dashboardRoute,
  runDetailRoute,
  configRoute,
  settingsRoute,
  detachedLogRoute,
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
