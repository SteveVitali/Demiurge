import { ActivateForm } from './activate-form';

export const dynamic = 'force-dynamic';

/**
 * /activate — Device code activation page (server component wrapper).
 * Renders the client-side form that handles Clerk auth + device code entry.
 */
export default function ActivatePage() {
  return <ActivateForm />;
}
