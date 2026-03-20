// Run status — mirrors RunStatus enum (23 values)
export type RunStatus =
  | 'Created' | 'InspectingRepo' | 'CompilingRequirements'
  | 'PlanningEnvironment' | 'BootstrappingEnvironment' | 'EnvironmentFailed'
  | 'SeedingFixtures' | 'BootstrappingAuth' | 'ReadyToVerify'
  | 'Verifying' | 'AnalyzingFailure' | 'PlanningRepair'
  | 'Repairing' | 'RepairFailed' | 'PlanningRerun'
  | 'SoftResettingEnvironment' | 'RebuildingEnvironment'
  | 'Succeeded' | 'Exhausted' | 'Cancelled' | 'Interrupted'
  | 'PlanningFeature' | 'GeneratingCode';

// Verdict status — mirrors VerdictStatus enum
export type VerdictStatus = 'Pass' | 'Fail' | 'Inconclusive' | 'Blocked' | 'Timeout' | 'Flake';

// Run mode — mirrors RunMode enum
export type RunMode = 'Full' | 'Build' | 'PlanOnly' | 'VerifyOnly' | 'InspectOnly';

// Service status — mirrors ServiceStatus enum
export type ServiceStatus =
  | 'Pending' | 'Starting' | 'RunningHealthy' | 'RunningUnhealthy'
  | 'Degraded' | 'Stopped' | 'Failed';

// Verifier type — mirrors VerifierType enum (9 values)
export type VerifierType =
  | 'EnvironmentReadiness' | 'HttpApiContract' | 'BrowserFlow'
  | 'StateAssertion' | 'QueueJob' | 'ConsoleLogSanity'
  | 'NetworkExpectation' | 'PersistenceReload' | 'TargetedRegression';

// Artifact type — mirrors ArtifactType enum (24 values)
export type ArtifactType =
  | 'Plan' | 'ServiceLog' | 'StartupTimeline' | 'StdoutExcerpt'
  | 'StderrExcerpt' | 'BrowserTrace' | 'Screenshot' | 'DomSnapshot'
  | 'AccessibilitySnapshot' | 'ConsoleLog' | 'NetworkSummary'
  | 'ApiRequestResponse' | 'DbQueryResult' | 'QueueObservation'
  | 'PatchDiff' | 'StructuredVerdict' | 'FailurePacketArtifact'
  | 'FinalReport' | 'RepairTranscript' | 'InferenceLog'
  | 'RepoInspectionArtifact' | 'AuthStorageState' | 'PromptPackage'
  | 'AttemptReport';

// Failure class enum
export type FailureClass =
  | 'FrontendRenderFailure' | 'BackendContractFailure' | 'AuthenticationFailure'
  | 'DataIntegrityFailure' | 'EnvironmentFailure' | 'NetworkFailure'
  | 'PerformanceFailure' | 'RegressionFailure' | 'UnknownFailure';

// Priority levels
export type Priority = 'Required' | 'Important' | 'NiceToHave';

// Attempt status
export type AttemptStatus = 'Running' | 'Passed' | 'Failed' | 'Cancelled';

// Backend status (local to the desktop app)
export type BackendStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

// SSE connection status
export type SSEStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

// Screen IDs for navigation
export type ScreenId = 'dashboard' | 'run-detail' | 'config' | 'settings';

// --- Core DTOs ---

export interface TaskRun {
  runId: string;
  repoPath: string;
  worktreePath: string;
  gitRef: string | null;
  taskText: string;
  changedFiles: string[] | null;
  status: RunStatus;
  runMode: RunMode;
  createdAt: string;     // ISO 8601
  startedAt: string | null;
  endedAt: string | null;
  maxAttempts: number;
  attemptCount: number;
  envBootAttempts: number;
  currentAttemptId: string | null;
  finalVerdict: VerdictStatus | null;
  finalSummary: string | null;
  policySnapshotId: string;
}

export interface VerdictSummary {
  totalCount: number;
  passCount: number;
  failCount: number;
  inconclusiveCount: number;
  blockedCount: number;
  timeoutCount: number;
  flakeCount: number;
}

export interface Attempt {
  attemptId: string;
  runId: string;
  attemptNumber: number;
  status: AttemptStatus;
  startedAt: string;
  endedAt: string | null;
  repairBackend: string | null;
  verdictSummary: VerdictSummary | null;
}

export interface Observation {
  label: string;
  expected: string | null;
  actual: string | null;
  severity: string;
}

export interface RequirementVerdict {
  verdictId: string;
  runId: string;
  attemptNumber: number;
  requirementId: string;
  verifierId: string;
  status: VerdictStatus;
  executionDurationMs: number;
  retryCount: number;
  observations: Observation[];
  evidenceRefs: string[];
  failureClass: string | null;
  failureMessage: string | null;
  confidence: number;
  producedAt: string;
}

export interface SystemEvent {
  eventId: string;
  runId: string;
  attemptNumber: number | null;
  eventType: string;
  component: string;
  severity: string;
  timestamp: string;
  correlationFields: Record<string, string>;
  payload: Record<string, unknown>;
  humanMessage: string;
}

export interface ArtifactRecord {
  artifactId: string;
  runId: string;
  attemptNumber: number | null;
  artifactType: ArtifactType;
  producerComponent: string;
  logicalScope: string | null;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

// --- API Envelope ---

export interface ApiEnvelope<T> {
  ok: boolean;
  data: T | null;
  error: {
    code: number;
    message: string;
  } | null;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

// --- Filter/Query Types ---

export interface RunFilters {
  status?: RunStatus;
  limit?: number;
  offset?: number;
  sort?: string;
  order?: 'asc' | 'desc';
}

export interface ArtifactFilters {
  type?: ArtifactType;
  attempt?: number;
  offset?: number;
  limit?: number;
}

// --- Health Check ---

export interface HealthResponse {
  status: string;
}
