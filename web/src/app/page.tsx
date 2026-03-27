import { redirect } from 'next/navigation';

/**
 * Root page — redirects to the marketing site.
 * The web/ backend is primarily an API layer; the marketing site
 * will be built separately (Spec 04).
 */
export default function Home() {
  redirect('https://demiurge.dev');
}
