import { useEffect, useState } from 'react';
import { api, type CodingTimelineEntry, type Execution, type SavedCodingResult } from './api';
import { ExecutionDetails } from './ExecutionDetails';

function SavedExecution({ execution }: { execution: Execution }) {
  return <>
    <small>Execution <code>{execution.requestId}</code></small>
    <ExecutionDetails result={execution} />
    {execution.response === null && <p className="cohort-note">No response was saved for this attempt.</p>}
    {execution.response === '' && <p className="cohort-note">The saved response is empty.</p>}
  </>;
}

export function CodingEvidence({ row, id }: { row: CodingTimelineEntry; id: string }) {
  const { reportId, executionId, taskId, model } = row;
  const [result, setResult] = useState<SavedCodingResult>();
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setResult(undefined);
    setError('');
    api.savedBenchmarkReport(reportId, AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]))
      .then(report => {
        if (controller.signal.aborted) return;
        const match = report.id === reportId && report.results.find(entry =>
          entry.execution.requestId === executionId && entry.taskId === taskId && entry.execution.model === model);
        if (!match) throw new Error('The selected execution was not found in this saved report.');
        setResult(match);
      })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [reportId, executionId, taskId, model, reload]);

  const earlier = result?.attempts?.filter(attempt => attempt.requestId !== executionId) ?? [];
  const grade = result?.codingEvaluation;
  return <section className="coding-evidence" id={id} aria-label="Saved coding evidence">
    <h4>Saved coding evidence</h4>
    {!result && !error && <p className="notice" role="status">Loading saved evidence…</p>}
    {error && <>
      <p className="notice error" role="alert">{error}</p>
      <button className="secondary" type="button" onClick={() => setReload(value => value + 1)}>Retry loading evidence</button>
    </>}
    {result && <>
      <p className="cohort-note">Report <code>{reportId}</code> · Saved score <code>{result.score}</code></p>
      <h5>Final attempt and original response</h5>
      <SavedExecution execution={result.execution} />
      <h5>Retries and earlier attempts</h5>
      {!earlier.length && <p className="cohort-note">No earlier attempts were recorded.</p>}
      {earlier.map((attempt, index) => <details key={attempt.requestId}>
        <summary>Earlier attempt {index + 1} · {attempt.status} · {attempt.durationMs} ms</summary>
        <SavedExecution execution={attempt} />
      </details>)}
      <h5>Grader case results</h5>
      {grade && <p className="cohort-note">Saved evaluation outcome <code>{grade.outcome}</code></p>}
      {!!grade?.cases?.length ? <ul className="coding-cases">
        {grade.cases.map(testCase => <li key={testCase.id}>
          <strong>{testCase.id}</strong> <code>{testCase.outcome}</code> <span className="latency">{testCase.durationMs} ms</span>
        </li>)}
      </ul> : <p className="cohort-note">No grader cases were recorded for this execution.</p>}
    </>}
  </section>;
}
