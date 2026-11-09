import { describeError, type Execution } from './api';

export function ExecutionDetails({ result }: { result: Omit<Execution, 'requestId'> }) {
  const skipped = result.status === 'SKIPPED';
  return <>
    <div className="result-meta">
      <span className={`badge ${skipped ? 'neutral' : result.status === 'SUCCESS' ? 'success' : 'failed'}`}>
        {skipped ? 'Skipped' : result.status === 'SUCCESS' ? 'Success' : 'Failed'}
      </span>
      <span className="latency">{skipped ? 'No execution' : `${result.durationMs.toLocaleString()} ms`}</span>
    </div>
    {result.error && <div className="notice error" role="alert">
      <strong>{result.error.code}</strong><p>{describeError(result.error)}</p>
    </div>}
    {result.response !== null && <pre className="response">{result.response}</pre>}
  </>;
}
