import {
  createRouter,
  createRoute,
  createRootRoute,
} from '@tanstack/react-router';
import { AppLayout } from '@/components/layout/AppLayout';
import { DashboardScreen } from '@/screens/DashboardScreen';
import { RunDetailScreen } from '@/screens/RunDetailScreen';
import { ConfigScreen } from '@/screens/ConfigScreen';
import { SettingsScreen } from '@/screens/SettingsScreen';
import { DetachedLogScreen } from '@/screens/DetachedLogScreen';

const rootRoute = createRootRoute({
  component: AppLayout,
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
