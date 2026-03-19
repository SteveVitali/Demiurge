import { get, post, put } from './client';
import type {
  TaskRun,
  Attempt,
  RequirementVerdict,
  ArtifactRecord,
  HealthResponse,
  PaginatedResponse,
  RunFilters,
  RepoInspectionReport,
  RequirementGraph,
  FeaturePlan,
  FailurePacket,
  PatchRecord,
  RuntimeSnapshot,
  ServiceSnapshot,
  AgentTranscriptMessage,
  AgentCost,
  ResolvedConfig,
  ConfigValidationResult,
  ConfigSaveResult,
  SmartInitResult,
  DoctorResult,
  SystemPreferences,
  CreateRunRequest,
  CreateRunResponse,
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

// --- Inspection ---

export function getInspection(runId: string): Promise<RepoInspectionReport> {
  return get<RepoInspectionReport>(`/runs/${runId}/inspection`);
}

export function getRequirementGraph(runId: string): Promise<RequirementGraph> {
  return get<RequirementGraph>(`/runs/${runId}/requirement-graph`);
}

export function getFeaturePlan(runId: string): Promise<FeaturePlan> {
  return get<FeaturePlan>(`/runs/${runId}/feature-plan`);
}

// --- Failure Analysis ---

export function getFailurePacket(runId: string, attemptNumber: number): Promise<FailurePacket> {
  return get<FailurePacket>(`/runs/${runId}/attempts/${attemptNumber}/failure-packet`);
}

export function getPatches(runId: string, attemptNumber: number): Promise<PatchRecord[]> {
  return get<PatchRecord[]>(`/runs/${runId}/attempts/${attemptNumber}/patches`);
}

// --- Artifact Content ---

export function getArtifactContent(runId: string, artifactId: string): Promise<unknown> {
  return get<unknown>(`/runs/${runId}/artifacts/${artifactId}/content`);
}

// --- Phase 3: Environment ---

export function getEnvironment(runId: string): Promise<RuntimeSnapshot> {
  return get<RuntimeSnapshot>(`/runs/${runId}/environment`);
}

export function getServices(runId: string): Promise<ServiceSnapshot[]> {
  return get<ServiceSnapshot[]>(`/runs/${runId}/services`);
}

export function restartService(runId: string, serviceId: string): Promise<{ serviceId: string; status: string }> {
  return post<{ serviceId: string; status: string }>(`/runs/${runId}/services/${serviceId}/restart`);
}

// --- Phase 3: Agent ---

export function getAgentTranscript(runId: string): Promise<AgentTranscriptMessage[]> {
  return get<AgentTranscriptMessage[]>(`/runs/${runId}/agent/transcript`);
}

export function getAgentCost(runId: string): Promise<AgentCost> {
  return get<AgentCost>(`/runs/${runId}/agent/cost`);
}

// --- Phase 4: Run Creation ---

export function createRun(request: CreateRunRequest): Promise<CreateRunResponse> {
  return post<CreateRunResponse>('/runs', request);
}

// --- Phase 4: Config ---

export function getConfig(repoPath: string): Promise<ResolvedConfig> {
  return get<ResolvedConfig>(`/config?repo=${encodeURIComponent(repoPath)}`);
}

export function saveManifest(repoPath: string, yaml: string): Promise<ConfigSaveResult> {
  return put<ConfigSaveResult>('/config/manifest', { repoPath, yaml });
}

export function saveRequirements(repoPath: string, yaml: string): Promise<ConfigSaveResult> {
  return put<ConfigSaveResult>('/config/requirements', { repoPath, yaml });
}

export function validateConfig(manifest?: string, requirements?: string): Promise<ConfigValidationResult> {
  return post<ConfigValidationResult>('/config/validate', { manifest, requirements });
}

export function smartInit(repoPath: string, taskHint?: string): Promise<SmartInitResult> {
  return post<SmartInitResult>('/config/init-smart', { repoPath, taskHint });
}

// --- Phase 4: System ---

export function getDoctor(): Promise<DoctorResult> {
  return get<DoctorResult>('/system/doctor');
}

export function getPreferences(): Promise<SystemPreferences> {
  return get<SystemPreferences>('/system/preferences');
}

export function updatePreferences(prefs: Partial<SystemPreferences>): Promise<{ status: string }> {
  return put<{ status: string }>('/system/preferences', prefs);
}

export function getKnownRepos(): Promise<string[]> {
  return get<string[]>('/system/repos');
}
