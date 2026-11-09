import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type { RoutingDecision } from './api';

const endpoint = '/api/routing/preview?profile=basic';
const localEndpoint = '/api/routing/generate-local';
const reportId = '00000000-0000-4000-8000-000000000001';
const missingId = '00000000-0000-4000-8000-000000000002';
const decision: RoutingDecision = {
  profile: 'basic', policyVersion: 'basic-preview-v2', asOf: '2026-11-09T00:00:00Z',
  datasetId: 'basics', datasetVersion: '2', evidenceCutoff: '2026-11-06T00:00:00Z',
  outcome: 'EVIDENCE_SELECTED', selectedModel: 'qwen3:4b', reason: 'ONLY_ELIGIBLE_CANDIDATE',
  thresholds: { minReports: 3, minAttempts: 30, minScored: 24, minReliabilityPercent: 80, correctnessBandPoints: 5, maxAgeDays: 14 },
  reportIds: [reportId], exclusions: [{ reportId: missingId, reason: 'REPORT_NOT_FOUND' }],
  candidates: [{ model: 'qwen3:4b', provider: 'ollama', reasons: [], metrics: {
    reports: 3, distinctTasks: 10, attempted: 30, scored: 25, passed: 20, incorrect: 5,
    providerFailures: 5, correctness: 0.8, reliability: 25 / 30, medianLatencyMs: 365,
  } }],
};
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

function backend(preview: () => Response | Promise<Response>, local?: () => Response | Promise<Response>) {
  const fetch = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === '/api/health') return json({ status: 'UP', application: 'ALFHA ModelHub' });
    if (url === '/api/models') return json([]);
    if (url === endpoint) return preview();
    if (url === localEndpoint && local) return local();
    throw new Error(`Unexpected request to ${url}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function openPreview() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Routing Preview' }));
  await screen.findByText('Backend online');
  return user;
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

test('only reads on demand and displays the recommendation, candidate evidence, and report links', async () => {
  let finish!: (value: Response) => void;
  const fetch = backend(() => new Promise(resolve => { finish = resolve; }));
  const user = await openPreview();
  expect(fetch.mock.calls.some(([url]) => url === endpoint)).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Preview route' }));
  expect(screen.getByText('Checking routing evidence…')).toBeTruthy();
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Loading preview…' }).disabled).toBe(true);
  finish(json(decision));
  const result = within(await screen.findByRole('region', { name: 'Routing decision' }));
  expect(result.getByRole('heading', { name: 'Evidence-backed selection' })).toBeTruthy();
  expect(result.getByText('qwen3:4b')).toBeTruthy();
  expect(result.getByText('Only one candidate met the policy requirements.')).toBeTruthy();
  const candidate = within(screen.getByRole('article', { name: 'qwen3:4b' }));
  expect(candidate.getByText('Provider failures')).toBeTruthy();
  expect(candidate.getByText('Incorrect')).toBeTruthy();
  expect(candidate.getByText('365 ms')).toBeTruthy();
  const evidence = within(screen.getByRole('region', { name: 'Benchmark reports' }));
  expect(evidence.getByRole('link', { name: reportId }).getAttribute('href')).toBe(`/api/benchmarks/${reportId}`);
  expect(evidence.getByText(missingId)).toBeTruthy();
  expect(evidence.getByText(/Saved report was not found/)).toBeTruthy();
  expect(fetch.mock.calls.filter(([url]) => url === endpoint)).toHaveLength(1);
  expect(fetch.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true);
});

test('distinguishes local fallback from no route and reports absent evidence', async () => {
  const preview = vi.fn().mockImplementationOnce(() => json({
    ...decision, outcome: 'FALLBACK_LOCAL', reason: 'CUTOFF_REQUIRED', evidenceCutoff: null, reportIds: [],
  })).mockImplementationOnce(() => json({
    ...decision, outcome: 'NO_ROUTE', selectedModel: null, reason: 'EVIDENCE_UNAVAILABLE', reportIds: [], candidates: [],
  }));
  backend(preview);
  const user = await openPreview();
  await user.click(screen.getByRole('button', { name: 'Preview route' }));
  expect(await screen.findByRole('heading', { name: 'Local fallback' })).toBeTruthy();
  expect(screen.getByText(/Set an evidence cutoff/)).toBeTruthy();
  expect(screen.getByText('No saved reports qualified for this preview.')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Preview route' }));
  expect(await screen.findByRole('heading', { name: 'No route' })).toBeTruthy();
  expect(screen.getByText('No model selected')).toBeTruthy();
  expect(screen.getByText('Saved benchmark evidence could not be read.')).toBeTruthy();
});

test('explains disabled preview and allows retry after a backend connection failure', async () => {
  const preview = vi.fn().mockImplementationOnce(() => json({ code: 'NOT_FOUND' }, 404))
    .mockRejectedValueOnce(new Error('Connection lost')).mockImplementationOnce(() => json(decision));
  backend(preview);
  const user = await openPreview();
  const button = screen.getByRole('button', { name: 'Preview route' });
  await user.click(button);
  expect((await screen.findByRole('alert')).textContent).toContain('MODELHUB_ROUTING_PREVIEW_ENABLED=true');
  await user.click(button);
  expect((await screen.findByRole('alert')).textContent).toContain('Cannot reach ModelHub');
  await user.click(button);
  expect(await screen.findByRole('heading', { name: 'Evidence-backed selection' })).toBeTruthy();
});

const fallback: RoutingDecision = {
  ...decision, outcome: 'FALLBACK_LOCAL', reason: 'NO_ELIGIBLE_EVIDENCE', policyVersion: 'basic-local-dispatch-v2',
};
const generated = {
  requestId: 'saved-execution', model: 'qwen3:4b', response: 'A local answer', durationMs: 200, status: 'SUCCESS', error: null,
};

test('sends an explicit local-only dispatch once and shows its actual decision and response', async () => {
  let finish!: (value: Response) => void;
  const fetch = backend(() => json(decision), () => new Promise(resolve => { finish = resolve; }));
  const user = await openPreview();
  expect(fetch.mock.calls.every(([url]) => url !== localEndpoint)).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Preview route' }));
  expect(await screen.findByRole('heading', { name: 'Evidence-backed selection' })).toBeTruthy();
  expect(fetch.mock.calls.every(([url]) => url !== localEndpoint)).toBe(true);
  await user.type(screen.getByLabelText('Prompt for local routing'), 'Explain a database index.');
  await user.click(screen.getByRole('button', { name: 'Generate locally' }));
  const busy = screen.getByRole<HTMLButtonElement>('button', { name: 'Generating locally…' });
  expect(busy.disabled).toBe(true);
  fireEvent.submit(busy.form!);
  const calls = fetch.mock.calls.filter(([, init]) => init?.method === 'POST');
  expect(calls.map(([url]) => url)).toEqual([localEndpoint]);
  expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ profile: 'basic', prompt: 'Explain a database index.' });
  finish(json({ decision: fallback, generation: generated }));
  const local = within(screen.getByRole('region', { name: 'Local routed generation' }));
  expect(await local.findByText('A local answer')).toBeTruthy();
  expect(local.getByText('200 ms')).toBeTruthy();
  expect(local.getByText('Local fallback', { selector: 'strong' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: 'Evidence-backed selection' })).toBeTruthy();
});

test('keeps the dispatch decision on provider failure and shows no-route and disabled states', async () => {
  const localResponse = vi.fn().mockImplementationOnce(() => json({ decision: fallback, generation: {
    ...generated, response: null, status: 'FAILED', error: { code: 'TIMEOUT', message: 'Timed out' },
  } }, 504)).mockImplementationOnce(() => json({
    decision: { ...fallback, outcome: 'NO_ROUTE', selectedModel: null }, generation: null,
  }, 503)).mockImplementationOnce(() => json({ code: 'NOT_FOUND' }, 404));
  backend(() => json(decision), localResponse);
  const user = await openPreview();
  const local = within(screen.getByRole('region', { name: 'Local routed generation' }));
  await user.type(local.getByLabelText('Prompt for local routing'), 'Try routing');
  await user.click(local.getByRole('button', { name: 'Generate locally' }));
  expect((await local.findByRole('alert')).textContent).toContain('provider timed out');
  expect(local.getByText('Local fallback', { selector: 'strong' })).toBeTruthy();
  await user.click(local.getByRole('button', { name: 'Generate locally' }));
  expect(await local.findByText('No model was called.')).toBeTruthy();
  await user.click(local.getByRole('button', { name: 'Generate locally' }));
  expect((await local.findByRole('alert')).textContent).toContain('Local routed generation is unavailable');
});
