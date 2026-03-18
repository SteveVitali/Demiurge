// Spec §10.1: JSON-RPC 2.0 message types for stdio worker protocol

export interface JsonRpcRequest {
  jsonrpc: '2.0';
  id: number | string;
  method: string;
  params?: Record<string, unknown>;
}

export interface JsonRpcNotification {
  jsonrpc: '2.0';
  method: string;
  params?: Record<string, unknown>;
}

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  id: number | string | null;
  result?: unknown;
  error?: JsonRpcError;
}

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

// Spec §10.1: Standard JSON-RPC error codes
export const ErrorCodes = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  // Application-specific codes
  TASK_CANCELLED: -32000,
  BROWSER_ERROR: -32001,
  ARTIFACT_ERROR: -32002,
  NOT_INITIALIZED: -32003,
} as const;

// Spec §10.2: Worker capability advertisement
export interface InitializeParams {
  artifactRoot: string;
  worktreePath: string;
  runId: string;
  workerRestartBudget?: number;
}

export interface InitializeResult {
  capabilities: {
    browserFlow: boolean;
    apiRequest: boolean;
    authBootstrap: boolean;
    pageSnapshot: boolean;
  };
  browserVersion: string;
}

// Spec §10.3: Browser flow execution
export interface ExecuteBrowserFlowParams {
  taskId: string;
  entryUrl: string;
  actions: BrowserActionSpec[];
  assertions: AssertionSpec[];
  artifactPlan: ArtifactCaptureSpec[];
  storageStatePath?: string;
  timeoutMs?: number;
}

export interface BrowserActionSpec {
  actionType: string;
  selector?: SelectorRefSpec;
  value?: string;
  url?: string;
  timeoutMs?: number;
  description: string;
}

export interface SelectorRefSpec {
  strategy: string;
  value: string;
  roleName?: string;
}

export interface AssertionSpec {
  assertionType: string;
  selector?: SelectorRefSpec;
  expected?: string;
  jsonPath?: string;
  tolerance?: number;
  description: string;
}

export interface ArtifactCaptureSpec {
  artifactType: string;
  trigger: string;
  label?: string;
}

export interface BrowserFlowResult {
  status: 'pass' | 'fail' | 'error' | 'timeout';
  observations: ObservationResult[];
  artifacts: ArtifactMetadata[];
  errorMessage?: string;
  durationMs: number;
}

export interface ObservationResult {
  observationType: string;
  message: string;
  selector?: string;
  expected?: string;
  actual?: string;
  timestamp: string;
}

export interface ArtifactMetadata {
  artifactType: string;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  checksumSha256: string;
  label?: string;
}

// Spec §10.4: Auth bootstrap
export interface ExecuteAuthBootstrapParams {
  taskId: string;
  mode: string;
  loginUrl?: string;
  credentials: Record<string, string>;
  storageStateOutput: string;
  timeoutMs?: number;
}

export interface AuthBootstrapResult {
  success: boolean;
  storageStatePath?: string;
  apiHeaders?: Record<string, string>;
  errorMessage?: string;
  artifacts: ArtifactMetadata[];
}

// Spec §10.5: API request execution
export interface ExecuteApiRequestParams {
  taskId: string;
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  storageStatePath?: string;
  timeoutMs?: number;
}

export interface ApiRequestResult {
  status: number;
  headers: Record<string, string>;
  body: string;
  durationMs: number;
  artifacts: ArtifactMetadata[];
}

// Spec §10.6: Page snapshot
export interface CapturePageSnapshotParams {
  taskId: string;
  url: string;
  storageStatePath?: string;
  waitForSelector?: string;
  timeoutMs?: number;
}

export interface PageSnapshotResult {
  artifacts: ArtifactMetadata[];
  errorMessage?: string;
}

// Progress notification
export interface ProgressNotification {
  taskId: string;
  step: string;
  message: string;
  percent?: number;
}
