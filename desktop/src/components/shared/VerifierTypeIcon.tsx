import {
  ServerCog,
  Globe,
  Monitor,
  Database,
  Layers,
  Terminal,
  Wifi,
  RefreshCw,
  GitBranch,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { VerifierType } from '@/api/types';

interface VerifierTypeIconProps {
  type: VerifierType;
  size?: number;
  className?: string;
}

const config: Record<VerifierType, { icon: LucideIcon; color: string }> = {
  EnvironmentReadiness: { icon: ServerCog, color: 'text-blue-400' },
  HttpApiContract: { icon: Globe, color: 'text-emerald-400' },
  BrowserFlow: { icon: Monitor, color: 'text-purple-400' },
  StateAssertion: { icon: Database, color: 'text-orange-400' },
  QueueJob: { icon: Layers, color: 'text-teal-400' },
  ConsoleLogSanity: { icon: Terminal, color: 'text-zinc-400' },
  NetworkExpectation: { icon: Wifi, color: 'text-indigo-400' },
  PersistenceReload: { icon: RefreshCw, color: 'text-amber-400' },
  TargetedRegression: { icon: GitBranch, color: 'text-red-400' },
};

export function VerifierTypeIcon({ type, size = 16, className }: VerifierTypeIconProps) {
  const { icon: Icon, color } = config[type] ?? { icon: ServerCog, color: 'text-zinc-400' };

  return <Icon size={size} className={cn(color, className)} aria-label={type} />;
}
