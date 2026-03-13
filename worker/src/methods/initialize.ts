// Spec §10.2: Worker initialization — launch browser, advertise capabilities

import { BrowserManager } from '../browser/manager';
import { ArtifactWriter } from '../artifacts/writer';
import { InitializeParams, InitializeResult } from '../rpc/types';

export interface WorkerState {
  initialized: boolean;
  artifactRoot: string;
  worktreePath: string;
  runId: string;
  browserManager: BrowserManager;
  artifactWriter: ArtifactWriter | null;
  activeTaskId: string | null;
  cancelled: boolean;
}

export function createInitialState(): WorkerState {
  return {
    initialized: false,
    artifactRoot: '',
    worktreePath: '',
    runId: '',
    browserManager: new BrowserManager(),
    artifactWriter: null,
    activeTaskId: null,
    cancelled: false,
  };
}

export async function handleInitialize(
  state: WorkerState,
  params: Record<string, unknown>,
): Promise<InitializeResult> {
  const p = params as unknown as InitializeParams;

  if (!p.artifactRoot || !p.runId) {
    throw Object.assign(new Error('Missing required params: artifactRoot, runId'), { code: -32602 });
  }

  state.artifactRoot = p.artifactRoot;
  state.worktreePath = p.worktreePath || '';
  state.runId = p.runId;
  state.artifactWriter = new ArtifactWriter(p.artifactRoot, p.runId);

  // Launch browser
  const browserVersion = await state.browserManager.launch();

  state.initialized = true;

  return {
    capabilities: {
      browserFlow: true,
      apiRequest: true,
      authBootstrap: true,
      pageSnapshot: true,
    },
    browserVersion,
  };
}
