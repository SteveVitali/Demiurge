import { RouterProvider } from '@tanstack/react-router';
import { router } from '@/lib/routes';

export function App() {
  return <RouterProvider router={router} />;
}
