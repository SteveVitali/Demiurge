import {
  Layout,
  Server,
  Database,
  Zap,
  Layers,
  Cog,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ServiceKind } from '@/api/types';

interface ServiceKindIconProps {
  kind: ServiceKind;
  size?: number;
  className?: string;
}

const config: Record<ServiceKind, LucideIcon> = {
  Frontend: Layout,
  Api: Server,
  Database: Database,
  Cache: Zap,
  Queue: Layers,
  Worker: Cog,
};

export function ServiceKindIcon({ kind, size = 16, className }: ServiceKindIconProps) {
  const Icon = config[kind] ?? Server;
  return <Icon size={size} className={cn('text-muted-foreground', className)} aria-label={kind} />;
}
