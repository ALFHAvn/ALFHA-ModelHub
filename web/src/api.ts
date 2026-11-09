export type Model = { model: string; provider: string };
export type ApiError = { code: string; message: string; retryAfterMs?: number };
export type Execution = {
  requestId: string;
  model: string;
  response: string | null;
  durationMs: number;
  status: string;
  error: ApiError | null;
};
export type Run = Execution & { prompt: string; timestamp: string };
export type RunPage = { content: Run[]; page: number; totalPages: number; totalElements: number };
export type WorkflowStep = Omit<Execution, 'requestId'> & { requestId: string | null };
export type ProposalReviewResult = { proposal: WorkflowStep; review: WorkflowStep };
export type SavedCodingReport = { id: string; completedAt: string; datasetVersion: string; scoringVersion: string };
export type CodingPeers = { seedReportId: string; reportIds: string[]; candidatesChecked: number; windowLimited: boolean };
export type CodingTimelineEntry = {
  reportId: string;
  completedAt: string;
  taskId: string;
  model: string;
  executionId: string;
  executionStatus: string;
  formatValidation: 'NOT_CHECKED' | 'PASSED' | 'REJECTED';
  sandboxVerdict: string | null;
  error: { stage: string; code: string | null } | null;
  executionDurationMs: number;
  gradingDurationMs: number | null;
  sandboxTimeoutSeconds: number | null;
};
export type CodingModelSummary = {
  config: Model;
  attempted: number;
  coding: {
    sandboxVerdicts: number;
    sandboxOutcomes: Record<string, number>;
    invalidSubmissions: number;
    graderInfrastructureFailures: number;
    executionErrors: Record<string, number>;
    rawFormat?: { checked: number; rejected: number };
  };
};
export type CodingCohortReport = {
  datasetId: string;
  datasetVersion: string;
  scoringVersion: string;
  reports: { id: string; completedAt: string }[];
  models: CodingModelSummary[];
  timeline: CodingTimelineEntry[];
};
export type SavedCodingResult = {
  taskId: string;
  execution: Execution;
  score: string;
  attempts?: Execution[] | null;
  codingEvaluation?: {
    outcome: string;
    cases?: { id: string; outcome: string; durationMs: number }[] | null;
  } | null;
};
export type SavedBenchmarkReport = { id: string; results: SavedCodingResult[] };

const messages: Record<string, string> = {
  UNKNOWN_MODEL: 'This model is no longer configured. Refresh the model list and choose another.',
  TIMEOUT: 'The model provider timed out. Try again or use a shorter prompt.',
  RATE_LIMITED: 'The provider rate limit was reached. Wait before trying again.',
  CONNECTION_FAILURE: 'ModelHub could not reach the model provider. Check that the provider is running and reachable.',
  CONFIGURATION_ERROR: 'The model provider is unavailable with the current backend configuration.',
  HISTORY_UNAVAILABLE: 'Execution history is unavailable. Check the PostgreSQL service and try again.',
  INVALID_INPUT: 'Check the prompt and model selections. Comparison requires two different models.',
};

export function describeError(error: ApiError): string {
  const message = messages[error.code] ?? error.message;
  return error.code === 'RATE_LIMITED' && error.retryAfterMs != null
    ? `${message} Retry after ${Math.ceil(error.retryAfterMs / 1000)} seconds.`
    : message;
}

const backendUnavailable = 'Cannot reach ModelHub. Start the Spring Boot backend on port 8080 and try again.';

async function request<T>(path: string, init?: RequestInit, keepExecution = false): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/${path}`, init);
  } catch {
    throw new Error(backendUnavailable);
  }
  let body;
  try {
    body = await response.json();
  } catch {
    throw new Error(response.ok ? 'ModelHub returned an unreadable response.' : backendUnavailable);
  }
  // Generation returns a full execution even when its HTTP status indicates a provider failure.
  if (response.ok || (keepExecution && typeof body?.requestId === 'string')) return body as T;
  if (typeof body?.code === 'string' && typeof body?.message === 'string') {
    if (path.startsWith('benchmarks/') && body.code === 'NOT_FOUND') {
      throw new Error('The saved benchmark report could not be found. Choose a recent report or check its ID.');
    }
    if (path === 'workflows/proposal-review' && body.code === 'INVALID_INPUT') {
      throw new Error('ModelHub rejected this workflow. Check the task, use distinct models, and confirm any cloud model is fixed free on the official OpenRouter endpoint.');
    }
    throw new Error(describeError(body));
  }
  throw new Error(`ModelHub returned HTTP ${response.status}.`);
}

function json(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

export const api = {
  health: async () => {
    const result = await request<{ status: string; application: string }>('health', {
      signal: AbortSignal.timeout(4000),
    });
    if (result?.status !== 'UP' || result.application !== 'ALFHA ModelHub') throw new Error(backendUnavailable);
  },
  models: () => request<Model[]>('models', { signal: AbortSignal.timeout(5000) }),
  generate: (model: string, prompt: string) =>
    request<Execution>('generate', json({ model, prompt }), true),
  compare: (models: string[], prompt: string) =>
    request<{ results: Execution[] }>('compare', json({ models, prompt })),
  proposalReview: (proposerModel: string, reviewerModel: string, prompt: string, allowCloud: boolean) =>
    request<ProposalReviewResult>('workflows/proposal-review', json({ proposerModel, reviewerModel, prompt, allowCloud })),
  runs: (page: number, signal: AbortSignal) => request<RunPage>(`runs?page=${page}&size=10`, { signal }),
  recentCodingReports: (signal: AbortSignal) => request<SavedCodingReport[]>('benchmarks/coding-reports', { signal }),
  codingPeers: (id: string, signal: AbortSignal) =>
    request<CodingPeers>(`benchmarks/${encodeURIComponent(id)}/coding-peers`, { signal }),
  codingCohort: (reportIds: string[], signal: AbortSignal) =>
    request<CodingCohortReport>('benchmarks/coding-cohort', { ...json({ reportIds }), signal }),
  savedBenchmarkReport: (id: string, signal: AbortSignal) =>
    request<SavedBenchmarkReport>(`benchmarks/${encodeURIComponent(id)}`, { signal }),
  savedReportUrl: (id: string) => `/api/benchmarks/${encodeURIComponent(id)}`,
};
