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
  runs: (page: number, signal: AbortSignal) => request<RunPage>(`runs?page=${page}&size=10`, { signal }),
};
