import type { RunStatus } from '@/api/types';

export const TERMINAL_STATUSES: RunStatus[] = [
  'Succeeded',
  'Exhausted',
  'Cancelled',
  'Interrupted',
];

export function isTerminalStatus(status: RunStatus | null | undefined): boolean {
  if (!status) return false;
  return TERMINAL_STATUSES.includes(status);
}
