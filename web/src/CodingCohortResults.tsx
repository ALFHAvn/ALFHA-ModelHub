import { useState } from 'react';
import { api, type CodingCohortReport, type CodingModelSummary, type CodingTimelineEntry } from './api';

const outcomeLabels: Record<string, string> = {
  PASS: 'Passed', WRONG_ANSWER: 'Incorrect answer', FORMAT_ERROR: 'Format rejection',
  COMPILATION_ERROR: 'Compilation failure', EXECUTION_ERROR: 'Execution failure',
  TIMEOUT: 'Timed out', NO_VERDICT: 'No verdict',
};
const stageLabels: Record<string, string> = {
  PROVIDER: 'Provider failure', FORMAT: 'Formatting failure', SANDBOX: 'Sandbox',
  GRADER: 'Grader failure', GENERATION: 'Generation failure', HISTORY: 'History failure',
  SUBMISSION: 'Invalid submission', NONE: 'No recorded failure',
};
const formatLabels = { PASSED: 'Passed', REJECTED: 'Rejected', NOT_CHECKED: 'Not checked' };
const label = (value: string) => outcomeLabels[value] ?? value.toLowerCase().replaceAll('_', ' ');
const outcome = (row: CodingTimelineEntry) => row.sandboxVerdict ?? row.error?.code ?? 'NO_VERDICT';

function ModelCounts({ summary }: { summary: CodingModelSummary }) {
  const { config, attempted, coding } = summary;
  const stats: [string, number][] = [
    ['Attempted', attempted],
    ['Sandbox verdicts', coding.sandboxVerdicts],
    ['Passed', coding.sandboxOutcomes.PASS ?? 0],
    ['Invalid submissions', coding.invalidSubmissions],
    ['Grader failures', coding.graderInfrastructureFailures],
  ];
  if (coding.rawFormat) stats.push(['Format checked', coding.rawFormat.checked], ['Format rejected', coding.rawFormat.rejected]);
  return <article className="card result cohort-model">
    <h3>{config.model} <small>{config.provider}</small></h3>
    <dl className="cohort-stats">
      {stats.map(([name, count]) => <div key={name}><dt>{name}</dt><dd>{count}</dd></div>)}
    </dl>
    {Object.keys(coding.sandboxOutcomes).length > 0 && <p>
      <strong>Sandbox outcomes</strong>{' '}
      {Object.entries(coding.sandboxOutcomes).map(([name, count]) => `${label(name)} (${count})`).join(' · ')}
    </p>}
    {Object.keys(coding.executionErrors).length > 0 && <p>
      <strong>Generation and history errors</strong>{' '}
      {Object.entries(coding.executionErrors).map(([name, count]) => `${label(name)} (${count})`).join(' · ')}
    </p>}
  </article>;
}

function TimelineRow({ row }: { row: CodingTimelineEntry }) {
  return <li className="card result cohort-run">
    <div className="cohort-run-title">
      <h3>{row.taskId} <small>{row.model}</small></h3>
      <time dateTime={row.completedAt}>{new Date(row.completedAt).toLocaleString()}</time>
    </div>
    <div className="cohort-run-meta">
      <span className={`badge ${row.sandboxVerdict === 'PASS' ? 'success' : row.error ? 'failed' : 'cohort-neutral'}`}>
        {label(outcome(row))}
      </span>
      {row.error && <span>{stageLabels[row.error.stage] ?? row.error.stage}</span>}
      <span>Format {formatLabels[row.formatValidation].toLowerCase()}</span>
    </div>
    <div className="cohort-run-meta latency">
      <span>Generation {row.executionDurationMs} ms</span>
      {row.gradingDurationMs != null && <span>Grading {row.gradingDurationMs} ms</span>}
      {row.sandboxTimeoutSeconds != null && <span>Case limit {row.sandboxTimeoutSeconds} s</span>}
    </div>
    <div className="cohort-run-meta">
      <a href={api.savedReportUrl(row.reportId)} target="_blank" rel="noopener">Open report JSON</a>
      <small>Execution <code>{row.executionId}</code></small>
    </div>
  </li>;
}

export function CodingCohortResults({ cohort }: { cohort: CodingCohortReport }) {
  const [modelFilter, setModelFilter] = useState('');
  const [outcomeFilter, setOutcomeFilter] = useState('');
  const [stageFilter, setStageFilter] = useState('');
  const models = [...new Set(cohort.timeline.map(row => row.model))].sort();
  const outcomes = [...new Set(cohort.timeline.map(outcome))].sort();
  const stages = [...new Set(cohort.timeline.map(row => row.error?.stage ?? 'NONE'))].sort();
  const filtered = cohort.timeline.filter(row =>
    (!modelFilter || row.model === modelFilter) &&
    (!outcomeFilter || outcome(row) === outcomeFilter) &&
    (!stageFilter || (row.error?.stage ?? 'NONE') === stageFilter));

  return <>
    <section className="output" aria-label="Cohort totals">
      <h2>Cohort totals</h2>
      <p className="cohort-note">{cohort.reports.length} saved reports · {cohort.datasetId} v{cohort.datasetVersion} · {cohort.scoringVersion}</p>
      <div className="two-columns">{cohort.models.map(summary => <ModelCounts key={summary.config.model} summary={summary} />)}</div>
    </section>
    <section className="output" aria-label="Coding timeline">
      <h2>Coding timeline</h2>
      <p className="cohort-note">Oldest report first. Rows use the report’s completion time. Filters affect only the timeline.</p>
      {!!cohort.timeline.length && <div className="cohort-filters">
        <label className="field"><span>Model filter</span>
          <select value={modelFilter} onChange={event => setModelFilter(event.target.value)}>
            <option value="">All models</option>{models.map(model => <option key={model}>{model}</option>)}
          </select>
        </label>
        <label className="field"><span>Outcome filter</span>
          <select value={outcomeFilter} onChange={event => setOutcomeFilter(event.target.value)}>
            <option value="">All outcomes</option>{outcomes.map(code => <option key={code} value={code}>{label(code)}</option>)}
          </select>
        </label>
        <label className="field"><span>Failure stage filter</span>
          <select value={stageFilter} onChange={event => setStageFilter(event.target.value)}>
            <option value="">All stages</option>{stages.map(stage =>
              <option key={stage} value={stage}>{stageLabels[stage] ?? stage}</option>)}
          </select>
        </label>
      </div>}
      <p className="count" role="status">Showing {filtered.length} of {cohort.timeline.length} results</p>
      {!filtered.length && <p className="empty card">
        {cohort.timeline.length ? 'No results match these filters.' : 'No saved results in this cohort.'}
      </p>}
      <ol className="cohort-timeline">
        {filtered.map(row => <TimelineRow key={row.executionId} row={row} />)}
      </ol>
    </section>
  </>;
}
