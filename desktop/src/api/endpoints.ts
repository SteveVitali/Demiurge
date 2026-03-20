import { get, post } from './client';
import type {
  TaskRun,
  Attempt,
  RequirementVerdict,
  ArtifactRecord,
  HealthResponse,
  PaginatedResponse,
  RunFilters,
} from './types';

// --- Health ---

export function getHealth(): Promise<HealthResponse> {
  return get<HealthResponse>('/health');
}

// --- Runs ---

export function getRuns(filters?: RunFilters): Promise<PaginatedResponse<TaskRun>> {
  const params = new URLSearchParams();
  if (filters?.status) params.set('status', filters.status);
  if (filters?.limit !== undefined) params.set('limit', String(filters.limit));
  if (filters?.offset !== undefined) params.set('offset', String(filters.offset));
  if (filters?.sort) params.set('sort', filters.sort);
  if (filters?.order) params.set('order', filters.order);
  const qs = params.toString();
  return get<PaginatedResponse<TaskRun>>(`/runs${qs ? `?${qs}` : ''}`);
}

export function getRun(runId: string): Promise<TaskRun> {
  return get<TaskRun>(`/runs/${runId}`);
}

export function getActiveRun(): Promise<TaskRun> {
  return get<TaskRun>('/runs/active');
}

// --- Attempts ---

export function getAttempts(runId: string): Promise<Attempt[]> {
  return get<Attempt[]>(`/runs/${runId}/attempts`);
}

// --- Verdicts ---

export function getVerdicts(runId: string, attemptNumber: number): Promise<RequirementVerdict[]> {
  return get<RequirementVerdict[]>(`/runs/${runId}/attempts/${attemptNumber}/verdicts`);
}

// --- Artifacts ---

export function getArtifacts(runId: string): Promise<PaginatedResponse<ArtifactRecord>> {
  return get<PaginatedResponse<ArtifactRecord>>(`/runs/${runId}/artifacts`);
}

// --- Run Actions ---

export function cancelRun(runId: string): Promise<{ runId: string; status: string }> {
  return post<{ runId: string; status: string }>(`/runs/${runId}/cancel`);
}

export function resumeRun(runId: string): Promise<{ runId: string; status: string }> {
  return post<{ runId: string; status: string }>(`/runs/${runId}/resume`);
}
