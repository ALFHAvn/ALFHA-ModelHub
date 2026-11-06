import { useEffect, useState } from 'react';
import { api, type RunPage } from './api';
import { ExecutionDetails } from './ExecutionDetails';

export function History({ online }: { online: boolean }) {
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [data, setData] = useState<RunPage>();
  const [error, setError] = useState('');

  useEffect(() => {
    if (!online) return;
    const controller = new AbortController();
    setData(undefined);
    setError('');
    api.runs(page, AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]))
      .then(result => { if (!controller.signal.aborted) setData(result); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [online, page, refresh]);

  return <section aria-label="Saved executions">
    <div className="section-toolbar">
      <h2>Saved executions {data && <small>({data.totalElements})</small>}</h2>
      <button className="secondary" disabled={!online} onClick={() => setRefresh(value => value + 1)}>Refresh</button>
    </div>
    {online && !data && !error && <p className="notice" role="status">Loading history…</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {data?.content.length === 0 && <div className="empty card">No executions saved on this page.</div>}
    <div className="history-list">
      {data?.content.map(run => <details className="card history-item" key={run.requestId}>
        <summary>
          <span className="history-model">{run.model}</span>
          <span className={`badge ${run.status === 'SUCCESS' ? 'success' : 'failed'}`}>
            {run.status === 'SUCCESS' ? 'Success' : 'Failed'}
          </span>
          <span className="latency">{run.durationMs.toLocaleString()} ms</span>
          <time dateTime={run.timestamp}>{new Date(run.timestamp).toLocaleString()}</time>
        </summary>
        <div className="history-detail">
          <h3>Prompt</h3><pre className="response">{run.prompt}</pre>
          <h3>Result</h3><ExecutionDetails result={run} />
        </div>
      </details>)}
    </div>
    {data && (data.totalPages > 1 || page > 0) && <div className="pagination">
      <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
      <span>Page {page + 1} of {Math.max(data.totalPages, page + 1)}</span>
      <button className="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
    </div>}
  </section>;
}
