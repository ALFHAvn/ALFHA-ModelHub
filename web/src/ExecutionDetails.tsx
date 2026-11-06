import { describeError, type Execution } from './api';

export function ExecutionDetails({ result }: { result: Execution }) {
  return <>
    <div className="result-meta">
      <span className={`badge ${result.status === 'SUCCESS' ? 'success' : 'failed'}`}>
        {result.status === 'SUCCESS' ? 'Success' : 'Failed'}
      </span>
      <span className="latency">{result.durationMs.toLocaleString()} ms</span>
    </div>
    {result.error && <div className="notice error" role="alert">
      <strong>{result.error.code}</strong><p>{describeError(result.error)}</p>
    </div>}
    {result.response !== null && <pre className="response">{result.response}</pre>}
  </>;
}
