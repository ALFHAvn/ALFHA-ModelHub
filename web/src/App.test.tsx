import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';

const local = { model: 'qwen3:4b', provider: 'ollama' };
const cloud = { model: 'vendor/model:free', provider: 'openrouter' };
const answer = {
  requestId: 'local-id', model: local.model, response: 'Fixture answer',
  durationMs: 123, status: 'SUCCESS', error: null,
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });

function backend(handler: (url: string, init?: RequestInit) => Response | Promise<Response>, models = [local, cloud]) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/health') return json({ status: 'UP', application: 'ALFHA ModelHub' });
    if (url === '/api/models') return json(models);
    return handler(url, init);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

test('uses the configured model, blocks duplicate sends, and shows returned text and latency', async () => {
  let complete!: (response: Response) => void;
  const fetch = backend(() => new Promise(resolve => { complete = resolve; }));
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('option', { name: 'qwen3:4b · ollama' });
  await user.type(screen.getByRole('textbox', { name: 'Prompt' }), 'Hello');
  await user.click(screen.getByRole('button', { name: 'Send prompt' }));
  const busy = screen.getByRole('button', { name: 'Running…' }) as HTMLButtonElement;
  expect(busy.disabled).toBe(true);
  fireEvent.submit(busy.form!);
  const calls = fetch.mock.calls.filter(([url]) => url === '/api/generate');
  expect(calls).toHaveLength(1);
  expect(JSON.parse(calls[0][1]!.body as string)).toEqual({ model: local.model, prompt: 'Hello' });
  complete(json(answer));
  expect(await screen.findByText('Fixture answer')).toBeTruthy();
  expect(screen.getByText('123 ms')).toBeTruthy();
});

test('shows both comparison results independently, including a rate limit', async () => {
  const fetch = backend(() => json({ results: [answer, {
    ...answer, requestId: 'cloud-id', model: cloud.model, response: null, status: 'FAILED',
    error: { code: 'RATE_LIMITED', message: 'Limited', retryAfterMs: 2000 },
  }] }));
  const user = userEvent.setup();
  render(<App />);
  await screen.findByText('Backend online');
  await user.click(screen.getByRole('button', { name: 'Compare' }));
  await screen.findByText('This selection sends the prompt to OpenRouter.');
  await user.type(screen.getByRole('textbox', { name: 'Prompt' }), 'Compare this');
  await user.click(screen.getByRole('button', { name: 'Compare models' }));
  const call = fetch.mock.calls.find(([url]) => url === '/api/compare')!;
  expect(JSON.parse(call[1]!.body as string)).toEqual({
    models: [local.model, cloud.model], prompt: 'Compare this',
  });
  expect(await screen.findByText('Fixture answer')).toBeTruthy();
  expect(screen.getByText(/Retry after 2 seconds/)).toBeTruthy();
  expect(screen.getByText('Failed')).toBeTruthy();
});

test('shows generation timeout execution details even on HTTP 504', async () => {
  backend(() => json({ ...answer, status: 'FAILED', response: null,
    error: { code: 'TIMEOUT', message: 'Timed out' } }, 504), [local]);
  const user = userEvent.setup();
  render(<App />);
  await screen.findByRole('option', { name: 'qwen3:4b · ollama' });
  await user.type(screen.getByRole('textbox', { name: 'Prompt' }), 'Hello');
  await user.click(screen.getByRole('button', { name: 'Send prompt' }));
  expect((await screen.findByRole('alert')).textContent).toContain('model provider timed out');
  expect(screen.getByText('123 ms')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Compare' }));
  expect((screen.getByRole('button', { name: 'Compare models' }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByText(/Configure a second model/)).toBeTruthy();
});

test('reports an offline backend and handles unknown-model API errors', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Connection refused')));
  const user = userEvent.setup();
  render(<App />);
  expect(await screen.findByText('Backend offline')).toBeTruthy();
  expect(screen.getByRole('alert').textContent).toContain('Start the Spring Boot backend');
  backend(() => json({ code: 'UNKNOWN_MODEL', message: 'Model is not configured' }, 400), [local]);
  await user.click(screen.getByRole('button', { name: 'Refresh connection and models' }));
  await screen.findByRole('option', { name: 'qwen3:4b · ollama' });
  await user.type(screen.getByRole('textbox', { name: 'Prompt' }), 'Hello');
  await user.click(screen.getByRole('button', { name: 'Send prompt' }));
  expect(await screen.findByText(/no longer configured/)).toBeTruthy();
});

test('loads saved executions and requests the next history page', async () => {
  const run = { ...answer, prompt: 'Saved prompt', timestamp: '2026-11-06T00:00:00Z' };
  const fetch = backend(url => json({ content: url.includes('page=0') ? [run] : [],
    totalElements: 11, page: 0, totalPages: 2 }));
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole('button', { name: 'History' }));
  await screen.findByText('Page 1 of 2');
  const history = within(screen.getByRole('region', { name: 'Saved executions' }));
  await user.click(history.getByText(local.model));
  expect(history.getByText('Saved prompt')).toBeTruthy();
  await user.click(history.getByRole('button', { name: 'Next' }));
  expect(await history.findByText('Page 2 of 2')).toBeTruthy();
  expect(fetch.mock.calls.map(([url]) => url)).toContain('/api/runs?page=1&size=10');
});
