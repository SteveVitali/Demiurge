import {
  FileText,
  Terminal,
  Camera,
  Monitor,
  Globe,
  Database,
  GitBranch,
  CheckCircle,
  AlertTriangle,
  BarChart,
  Brain,
  Settings,
  Clock,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ArtifactType } from '@/api/types';

interface ArtifactTypeIconProps {
  type: ArtifactType;
  size?: number;
  className?: string;
}

const config: Record<ArtifactType, { icon: LucideIcon; color: string }> = {
  // Plans
  Plan: { icon: FileText, color: 'text-blue-400' },
  // Logs
  ServiceLog: { icon: Terminal, color: 'text-zinc-400' },
  StdoutExcerpt: { icon: Terminal, color: 'text-zinc-400' },
  StderrExcerpt: { icon: Terminal, color: 'text-red-400' },
  ConsoleLog: { icon: Terminal, color: 'text-zinc-400' },
  // Browser
  Screenshot: { icon: Camera, color: 'text-purple-400' },
  BrowserTrace: { icon: Monitor, color: 'text-purple-400' },
  DomSnapshot: { icon: Monitor, color: 'text-purple-400' },
  AccessibilitySnapshot: { icon: Monitor, color: 'text-purple-400' },
  // Network
  NetworkSummary: { icon: Globe, color: 'text-indigo-400' },
  ApiRequestResponse: { icon: Globe, color: 'text-green-400' },
  // Data
  DbQueryResult: { icon: Database, color: 'text-orange-400' },
  QueueObservation: { icon: Database, color: 'text-orange-400' },
  // Code
  PatchDiff: { icon: GitBranch, color: 'text-emerald-400' },
  // Verdicts
  StructuredVerdict: { icon: CheckCircle, color: 'text-green-400' },
  FailurePacketArtifact: { icon: AlertTriangle, color: 'text-red-400' },
  // Reports
  FinalReport: { icon: BarChart, color: 'text-blue-400' },
  AttemptReport: { icon: BarChart, color: 'text-blue-400' },
  // LLM
  RepairTranscript: { icon: Brain, color: 'text-violet-400' },
  InferenceLog: { icon: Brain, color: 'text-violet-400' },
  PromptPackage: { icon: Brain, color: 'text-violet-400' },
  // Config
  RepoInspectionArtifact: { icon: Settings, color: 'text-zinc-400' },
  AuthStorageState: { icon: Settings, color: 'text-zinc-400' },
  // Timeline
  StartupTimeline: { icon: Clock, color: 'text-amber-400' },
};

export function ArtifactTypeIcon({ type, size = 16, className }: ArtifactTypeIconProps) {
  const { icon: Icon, color } = config[type] ?? { icon: FileText, color: 'text-zinc-400' };
  return <Icon size={size} className={cn(color, className)} aria-label={type} />;
}
