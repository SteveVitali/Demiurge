import type { RunFilters, ArtifactFilters } from '@/api/types';

export const queryKeys = {
  runs: {
    all: ['runs'] as const,
    list: (filters: RunFilters) => ['runs', 'list', filters] as const,
    detail: (runId: string) => ['runs', runId] as const,
    active: ['runs', 'active'] as const,
  },
  attempts: {
    list: (runId: string) => ['runs', runId, 'attempts'] as const,
  },
  verdicts: {
    list: (runId: string, attemptNum: number) =>
      ['runs', runId, 'attempts', attemptNum, 'verdicts'] as const,
  },
  artifacts: {
    list: (runId: string, filters?: ArtifactFilters) =>
      ['runs', runId, 'artifacts', filters] as const,
    content: (runId: string, artifactId: string) =>
      ['runs', runId, 'artifacts', artifactId, 'content'] as const,
  },
  inspection: {
    report: (runId: string) => ['runs', runId, 'inspection'] as const,
    graph: (runId: string) => ['runs', runId, 'requirement-graph'] as const,
    featurePlan: (runId: string) => ['runs', runId, 'feature-plan'] as const,
  },
  environment: {
    snapshot: (runId: string) => ['runs', runId, 'environment'] as const,
    services: (runId: string) => ['runs', runId, 'services'] as const,
  },
  config: {
    resolved: (repoPath: string) => ['config', repoPath] as const,
  },
  system: {
    doctor: ['system', 'doctor'] as const,
    preferences: ['system', 'preferences'] as const,
    repos: ['system', 'repos'] as const,
  },
} as const;
