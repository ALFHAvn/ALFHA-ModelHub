import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type { CodingCohortReport, CodingTimelineEntry } from './api';

const seedId = '00000000-0000-4000-8000-000000000001';
const peerId = '00000000-0000-4000-8000-000000000002';
const base = '/api/benchmarks';
const date = '2026-11-06T00:00:00Z';
const local = 'qwen3:4b';
const cloud = 'vendor/model:free';
const recent = [{ id: seedId, completedAt: date, datasetVersion: '1', scoringVersion: 'java-coding-v1' }];
const discovery = { seedReportId: seedId, reportIds: [seedId, peerId], candidatesChecked: 3, windowLimited: false };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

const savedRow: CodingTimelineEntry = {
  reportId: seedId, completedAt: date, taskId: 'median-of-three', model: local,
  executionId: 'execution-1', executionStatus: 'SUCCESS', formatValidation: 'NOT_CHECKED',
  sandboxVerdict: 'WRONG_ANSWER', error: { stage: 'SANDBOX', code: 'WRONG_ANSWER' },
  executionDurationMs: 420, gradingDurationMs: 125, sandboxTimeoutSeconds: 8,
};
const cohort: CodingCohortReport = {
  datasetId: 'java-coding', datasetVersion: '1', scoringVersion: 'java-coding-v1', reports: recent,
  models: [{
    config: { model: local, provider: 'ollama' }, attempted: 3,
    coding: { sandboxVerdicts: 2, sandboxOutcomes: { WRONG_ANSWER: 1, TIMEOUT: 1 }, invalidSubmissions: 0,
      graderInfrastructureFailures: 0, executionErrors: {}, rawFormat: { checked: 3, rejected: 1 } },
  }],
  timeline: [
    savedRow,
    { ...savedRow, model: cloud, taskId: 'provider-timeout-task', executionId: 'execution-2', executionStatus: 'FAILED',
      sandboxVerdict: null, error: { stage: 'PROVIDER', code: 'TIMEOUT' }, gradingDurationMs: null, sandboxTimeoutSeconds: null },
    { ...savedRow, taskId: 'sandbox-timeout-task', executionId: 'execution-3',
      sandboxVerdict: 'TIMEOUT', error: { stage: 'SANDBOX', code: 'TIMEOUT' } },
    { ...savedRow, taskId: 'format-task', executionId: 'execution-4', formatValidation: 'REJECTED',
      sandboxVerdict: null, error: { stage: 'FORMAT', code: 'FORMAT_ERROR' } },
  ],
};

function backend(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/health') return json({ status: 'UP', application: 'ALFHA ModelHub' });
    if (url === '/api/models') return json([]);
    return handler(url, init);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function openDashboard() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Coding Cohorts' }));
  await screen.findByText('Backend online');
  return user;
}

async function chooseRecent(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: /Dataset v1/ });
  await user.selectOptions(screen.getByLabelText('Recent coding report'), seedId);
  await user.click(screen.getByRole('button', { name: 'Find compatible peers' }));
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

test('loads saved reports, discovers peers once, and builds from selected IDs', async () => {
  let finishList!: (response: Response) => void;
  let finishDiscovery!: (response: Response) => void;
  let finishCohort!: (response: Response) => void;
  const fetch = backend(url => {
    if (url === `${base}/coding-reports`) return new Promise(resolve => { finishList = resolve; });
    if (url === `${base}/${seedId}/coding-peers`) return new Promise(resolve => { finishDiscovery = resolve; });
    if (url === `${base}/coding-cohort`) return new Promise(resolve => { finishCohort = resolve; });
    throw new Error(`Unexpected request to ${url}`);
  });
  const user = await openDashboard();
  expect(screen.getByText('Loading recent coding reports…')).toBeTruthy();
  finishList(json(recent));
  await chooseRecent(user);
  const busy = screen.getByRole<HTMLButtonElement>('button', { name: 'Finding peers…' });
  expect(busy.disabled).toBe(true);
  fireEvent.submit(busy.form!);
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/coding-peers'))).toHaveLength(1);
  finishDiscovery(json(discovery));
  await user.click(await screen.findByRole('checkbox', { name: /Peer report/ }));
  await user.click(screen.getByRole('button', { name: 'Build cohort' }));
  expect(screen.getByText('Loading cohort results…')).toBeTruthy();
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Building cohort…' }).disabled).toBe(true);
  const request = fetch.mock.calls.find(([url]) => url === `${base}/coding-cohort`)?.[1];
  expect(request?.method).toBe('POST');
  expect(JSON.parse(String(request?.body))).toEqual({ reportIds: [seedId] });
  finishCohort(json(cohort));
  const timeline = within(await screen.findByRole('region', { name: 'Coding timeline' }));
  expect(timeline.getAllByRole('listitem').map(row => within(row).getByRole('heading').textContent)).toEqual(
    cohort.timeline.map(row => `${row.taskId} ${row.model}`),
  );
  const reportLink = within(timeline.getAllByRole('listitem')[0]).getByRole('link', { name: 'Open report JSON' });
  expect(reportLink.getAttribute('href')).toBe(`${base}/${seedId}`);
  expect(reportLink.getAttribute('target')).toBe('_blank');
  const compatible = within(screen.getByRole('region', { name: 'Compatible reports' }));
  expect(compatible.getAllByRole('link').map(link => link.getAttribute('href'))).toEqual([`${base}/${seedId}`, `${base}/${peerId}`]);
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST').map(([url]) => url)).toEqual([`${base}/coding-cohort`]);
});

test('combines client-side filters while keeping whole-cohort counts visible', async () => {
  const fetch = backend(url => json(url.endsWith('/coding-reports') ? recent : url.endsWith('/coding-peers') ? discovery : cohort));
  const user = await openDashboard();
  await chooseRecent(user);
  await user.click(await screen.findByRole('button', { name: 'Build cohort' }));
  const timeline = within(await screen.findByRole('region', { name: 'Coding timeline' }));
  expect(within(timeline.getAllByRole('listitem')[0]).getByText('Incorrect answer')).toBeTruthy();
  expect(within(timeline.getAllByRole('listitem')[3]).getByText('Format rejection')).toBeTruthy();
  await user.selectOptions(timeline.getByLabelText('Outcome filter'), 'TIMEOUT');
  expect(timeline.getAllByRole('listitem')).toHaveLength(2);
  await user.selectOptions(timeline.getByLabelText('Failure stage filter'), 'PROVIDER');
  expect(timeline.getAllByRole('listitem')).toHaveLength(1);
  expect(within(timeline.getByRole('listitem')).getByText('Provider failure')).toBeTruthy();
  await user.selectOptions(timeline.getByLabelText('Model filter'), local);
  expect(timeline.getByText('No results match these filters.')).toBeTruthy();
  await user.selectOptions(timeline.getByLabelText('Failure stage filter'), 'SANDBOX');
  expect(timeline.getByRole('heading', { name: /sandbox-timeout-task/ })).toBeTruthy();
  expect(timeline.getByText('Grading 125 ms')).toBeTruthy();
  expect(within(screen.getByRole('region', { name: 'Cohort totals' })).getByText('Attempted')).toBeTruthy();
  expect(fetch.mock.calls.filter(([url]) => url === `${base}/coding-cohort`)).toHaveLength(1);
});

test('explains missing reports and invalid cohorts, and supports a single saved report', async () => {
  let findMissing = true;
  let rejectCohort = true;
  backend(url => {
    if (url.endsWith('/coding-reports')) return json([]);
    if (url.endsWith('/coding-peers')) return findMissing
      ? json({ code: 'NOT_FOUND', message: 'Benchmark report does not exist' }, 404)
      : json({ ...discovery, reportIds: [seedId] });
    return rejectCohort ? json({ code: 'INVALID_COHORT', message: 'Reports contain missing or reused execution IDs' }, 400)
      : json({ ...cohort, models: [], timeline: [] });
  });
  const user = await openDashboard();
  expect(await screen.findByText('No saved Java coding reports are available.')).toBeTruthy();
  await user.type(screen.getByLabelText('Saved report ID'), seedId);
  await user.click(screen.getByRole('button', { name: 'Find compatible peers' }));
  expect((await screen.findByRole('alert')).textContent).toContain('saved benchmark report could not be found');
  findMissing = false;
  await user.click(screen.getByRole('button', { name: 'Find compatible peers' }));
  expect(await screen.findByText(/cohort with just the starting report/)).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Build cohort' }));
  expect((await screen.findByRole('alert')).textContent).toContain('missing or reused execution IDs');
  rejectCohort = false;
  await user.click(screen.getByRole('button', { name: 'Build cohort' }));
  expect(await screen.findByText('No saved results in this cohort.')).toBeTruthy();
});

test('reports a failed backend request while loading recent reports', async () => {
  backend(() => Promise.reject(new Error('Connection lost')));
  await openDashboard();
  expect((await screen.findByRole('alert')).textContent).toContain('Could not load recent reports. Cannot reach ModelHub.');
});

test('opens the exact saved execution, earlier attempts and grader cases without resetting filters', async () => {
  const response = ' `raw source`\n<script>original text</script>\n';
  const execution = { requestId: savedRow.executionId, model: local, response, durationMs: 420, status: 'SUCCESS', error: null };
  const earlier = { ...execution, requestId: 'retry-1', response: null, durationMs: 150, status: 'FAILED',
    error: { code: 'TIMEOUT', message: 'Timed out' } };
  let finishEvidence!: (value: Response) => void;
  const fetch = backend(url => {
    if (url === `${base}/coding-reports`) return json(recent);
    if (url.endsWith('/coding-peers')) return json(discovery);
    if (url === `${base}/coding-cohort`) return json(cohort);
    if (url === `${base}/${seedId}`) return new Promise(resolve => { finishEvidence = resolve; });
    throw new Error(`Unexpected request to ${url}`);
  });
  const user = await openDashboard();
  await chooseRecent(user);
  await user.click(await screen.findByRole('button', { name: 'Build cohort' }));
  const timeline = within(await screen.findByRole('region', { name: 'Coding timeline' }));
  await user.selectOptions(timeline.getByLabelText('Outcome filter'), 'WRONG_ANSWER');
  await user.click(timeline.getByRole('button', { name: 'Inspect evidence' }));
  expect(timeline.getByText('Loading saved evidence…')).toBeTruthy();
  finishEvidence(json({ id: seedId, results: [
    { taskId: savedRow.taskId, score: 'PASS', execution: { ...execution, requestId: 'other', response: 'Other run' } },
    { taskId: savedRow.taskId, score: 'FAIL', execution, attempts: [earlier, execution],
      codingEvaluation: { outcome: 'WRONG_ANSWER', cases: [{ id: 'ascending', outcome: 'WRONG_ANSWER', durationMs: 14 }] } },
  ] }));
  const evidence = within(timeline.getByRole('region', { name: 'Saved coding evidence' }));
  await evidence.findByText('Final attempt and original response');
  expect(evidence.getByText(/original text/).textContent).toBe(response);
  expect(evidence.queryByText('Other run')).toBeNull();
  await user.click(evidence.getByText(/Earlier attempt 1/));
  expect(evidence.getByText('retry-1')).toBeTruthy();
  expect(evidence.getByRole('alert').textContent).toContain('provider timed out');
  expect(evidence.getByText('ascending')).toBeTruthy();
  expect(evidence.getByText('14 ms')).toBeTruthy();
  await user.click(timeline.getByRole('button', { name: 'Close evidence' }));
  expect(timeline.queryByRole('region', { name: 'Saved coding evidence' })).toBeNull();
  expect(timeline.getByText('Showing 1 of 4 results')).toBeTruthy();
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST').map(([url]) => url)).toEqual([`${base}/coding-cohort`]);
});

test('recovers from a connection error and handles missing evidence and older reports', async () => {
  const report = vi.fn().mockRejectedValueOnce(new Error('Connection lost'))
    .mockResolvedValueOnce(json({ code: 'NOT_FOUND', message: 'Missing report' }, 404))
    .mockResolvedValueOnce(json({ id: seedId, results: [] }))
    .mockResolvedValueOnce(json({ id: seedId, results: [{
      taskId: savedRow.taskId, score: 'NOT_SCORED',
      execution: { requestId: savedRow.executionId, model: local, durationMs: 10, status: 'FAILED', response: null, error: null },
    }] }));
  backend(url => {
    if (url === `${base}/coding-reports`) return json(recent);
    if (url.endsWith('/coding-peers')) return json(discovery);
    if (url === `${base}/coding-cohort`) return json({ ...cohort, timeline: [savedRow] });
    return report();
  });
  const user = await openDashboard();
  await chooseRecent(user);
  await user.click(await screen.findByRole('button', { name: 'Build cohort' }));
  await user.click(await screen.findByRole('button', { name: 'Inspect evidence' }));
  const evidence = within(screen.getByRole('region', { name: 'Saved coding evidence' }));
  expect((await evidence.findByRole('alert')).textContent).toContain('Cannot reach ModelHub');
  await user.click(evidence.getByRole('button', { name: 'Retry loading evidence' }));
  expect((await evidence.findByRole('alert')).textContent).toContain('saved benchmark report could not be found');
  await user.click(evidence.getByRole('button', { name: 'Retry loading evidence' }));
  expect((await evidence.findByRole('alert')).textContent).toContain('selected execution was not found');
  await user.click(evidence.getByRole('button', { name: 'Retry loading evidence' }));
  expect(await evidence.findByText('No response was saved for this attempt.')).toBeTruthy();
  expect(evidence.getByText('No earlier attempts were recorded.')).toBeTruthy();
  expect(evidence.getByText('No grader cases were recorded for this execution.')).toBeTruthy();
});
