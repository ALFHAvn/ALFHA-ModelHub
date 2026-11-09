import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type { WorkflowStep } from './api';

const proposer = { model: 'local:small', provider: 'ollama' };
const reviewer = { model: 'local:medium', provider: 'ollama' };
const cloud = { model: 'vendor/reviewer:free', provider: 'openrouter' };
const endpoint = '/api/workflows/proposal-review';
const task = 'Design a bounded cache.';
const proposal: WorkflowStep = {
  requestId: 'proposal-id', model: proposer.model, response: 'Use a bounded map.',
  durationMs: 120, status: 'SUCCESS', error: null,
};
const review: WorkflowStep = {
  requestId: 'review-id', model: reviewer.model, response: 'Define the eviction order.',
  durationMs: 340, status: 'SUCCESS', error: null,
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
type User = ReturnType<typeof userEvent.setup>;

function backend(workflow: () => Response | Promise<Response>, models = [proposer, reviewer]) {
  const fetch = vi.fn(async (url: string, _init?: RequestInit) => {
    if (url === '/api/health') return json({ status: 'UP', application: 'ALFHA ModelHub' });
    if (url === '/api/models') return json(models);
    if (url === endpoint) return workflow();
    throw new Error(`Unexpected request to ${url}`);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function openWorkflow() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'Proposal & Review' }));
  await within(screen.getByLabelText('Proposer model')).findByRole('option', { name: 'local:small · ollama' });
  return user;
}

async function fillTask(user: User) {
  await user.selectOptions(screen.getByLabelText('Proposer model'), proposer.model);
  await user.type(screen.getByLabelText('Task or question'), task);
}

const runButton = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Run proposal and review' });

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

test('requires distinct selections, submits once, and displays both steps with model and timing', async () => {
  let complete!: (value: Response) => void;
  const fetch = backend(() => new Promise(resolve => { complete = resolve; }));
  const user = await openWorkflow();
  await fillTask(user);
  expect(runButton().disabled).toBe(true);
  const reviewerSelect = within(screen.getByLabelText('Reviewer model'));
  expect(reviewerSelect.queryByRole('option', { name: /local:small/ })).toBeNull();
  await user.selectOptions(screen.getByLabelText('Reviewer model'), reviewer.model);
  await user.click(runButton());
  const busy = screen.getByRole<HTMLButtonElement>('button', { name: 'Running workflow…' });
  expect(busy.disabled).toBe(true);
  expect(screen.getByText(/The reviewer runs after the proposer/)).toBeTruthy();
  fireEvent.submit(busy.form!);
  const calls = fetch.mock.calls.filter(([url]) => url === endpoint);
  expect(calls).toHaveLength(1);
  expect(calls[0][1]?.method).toBe('POST');
  expect(JSON.parse(String(calls[0][1]?.body))).toEqual({
    proposerModel: proposer.model, reviewerModel: reviewer.model, prompt: task, allowCloud: false,
  });
  complete(json({ proposal, review }));
  const first = within(await screen.findByRole('article', { name: 'Proposal' }));
  expect(first.getByText(proposer.model)).toBeTruthy();
  expect(first.getByText('Use a bounded map.')).toBeTruthy();
  expect(first.getByText('120 ms')).toBeTruthy();
  expect(first.getByText('Success')).toBeTruthy();
  const second = within(screen.getByRole('article', { name: 'Reviewer feedback' }));
  expect(second.getByText(reviewer.model)).toBeTruthy();
  expect(second.getByText('Define the eviction order.')).toBeTruthy();
  expect(second.getByText('340 ms')).toBeTruthy();
});

test('keeps the successful proposal visible after a reviewer timeout', async () => {
  backend(() => json({ proposal, review: {
    ...review, status: 'FAILED', response: null, error: { code: 'TIMEOUT', message: 'Timed out' },
  } }));
  const user = await openWorkflow();
  await fillTask(user);
  await user.selectOptions(screen.getByLabelText('Reviewer model'), reviewer.model);
  await user.click(runButton());
  expect(await screen.findByText('The proposal is available. The review did not complete.')).toBeTruthy();
  expect(screen.getByText('Use a bounded map.')).toBeTruthy();
  const second = within(screen.getByRole('article', { name: 'Reviewer feedback' }));
  expect(second.getByText('Failed')).toBeTruthy();
  expect(second.getByRole('alert').textContent).toContain('model provider timed out');
  expect(second.getByText('340 ms')).toBeTruthy();
});

test('shows the reviewer was skipped when the proposer fails', async () => {
  backend(() => json({
    proposal: { ...proposal, status: 'FAILED', response: null, error: { code: 'PROVIDER_ERROR', message: 'Provider rejected the task' } },
    review: { ...review, requestId: null, durationMs: 0, status: 'SKIPPED', response: null,
      error: { code: 'PROPOSAL_FAILED', message: 'Review requires a successful proposal' } },
  }));
  const user = await openWorkflow();
  await fillTask(user);
  await user.selectOptions(screen.getByLabelText('Reviewer model'), reviewer.model);
  await user.click(runButton());
  expect(await screen.findByText('Provider rejected the task')).toBeTruthy();
  const second = within(screen.getByRole('article', { name: 'Reviewer feedback' }));
  expect(second.getByText('Skipped')).toBeTruthy();
  expect(second.getByText('No execution')).toBeTruthy();
  expect(second.getByRole('alert').textContent).toContain('Review requires a successful proposal');
});

test('requires cloud opt-in and only offers configured fixed free OpenRouter models', async () => {
  const fetch = backend(() => json({ proposal, review: { ...review, model: cloud.model } }), [
    proposer, cloud, { model: 'openrouter/free', provider: 'openrouter' }, { model: 'vendor/paid', provider: 'openrouter' },
  ]);
  const user = await openWorkflow();
  await fillTask(user);
  const select = screen.getByLabelText('Reviewer model');
  const options = within(select);
  expect(options.getAllByRole('option')).toHaveLength(1);
  const optIn = screen.getByRole('checkbox', { name: 'Allow a configured free OpenRouter model' });
  await user.click(optIn);
  expect(options.getAllByRole('option').map(option => option.textContent)).toEqual([
    'Choose a reviewer', 'vendor/reviewer:free · openrouter',
  ]);
  await user.selectOptions(select, cloud.model);
  await user.click(optIn);
  expect(runButton().disabled).toBe(true);
  await user.click(optIn);
  expect(runButton().disabled).toBe(true);
  await user.selectOptions(select, cloud.model);
  await user.click(runButton());
  expect(await screen.findByText('Define the eviction order.')).toBeTruthy();
  const request = fetch.mock.calls.find(([url]) => url === endpoint)?.[1];
  expect(JSON.parse(String(request?.body))).toEqual({
    proposerModel: proposer.model, reviewerModel: cloud.model, prompt: task, allowCloud: true,
  });
});

test('shows request-level workflow errors and permits retry', async () => {
  const workflow = vi.fn().mockRejectedValueOnce(new Error('Connection lost'))
    .mockResolvedValueOnce(json({ code: 'INVALID_INPUT', message: 'Invalid input' }, 400));
  backend(workflow);
  const user = await openWorkflow();
  await fillTask(user);
  await user.selectOptions(screen.getByLabelText('Reviewer model'), reviewer.model);
  await user.click(runButton());
  expect((await screen.findByRole('alert')).textContent).toContain('Cannot reach ModelHub');
  await user.click(runButton());
  expect(await screen.findByText(/ModelHub rejected this workflow/)).toBeTruthy();
  expect(runButton().disabled).toBe(false);
});
