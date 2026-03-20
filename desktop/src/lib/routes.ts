import {
  createRouter,
  createRoute,
  createRootRoute,
} from '@tanstack/react-router';
import { AppLayout } from '@/components/layout/AppLayout';
import { DashboardScreen } from '@/screens/DashboardScreen';
import { RunDetailScreen } from '@/screens/RunDetailScreen';
import { PlaceholderScreen } from '@/screens/PlaceholderScreen';

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

// Coming in Phase 4
const configRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/config',
  component: () => PlaceholderScreen({ title: 'Configuration', phase: 4 }),
});

// Coming in Phase 4
const settingsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/settings',
  component: () => PlaceholderScreen({ title: 'Settings', phase: 4 }),
});

const routeTree = rootRoute.addChildren([
  dashboardRoute,
  runDetailRoute,
  configRoute,
  settingsRoute,
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
