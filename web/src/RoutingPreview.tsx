import { useEffect, useRef, useState } from 'react';
import { api, type RoutingCandidate, type RoutingDecision } from './api';

const outcomeLabels: Record<RoutingDecision['outcome'], string> = {
  EVIDENCE_SELECTED: 'Evidence-backed selection', FALLBACK_LOCAL: 'Local fallback', NO_ROUTE: 'No route',
};
const reasons: Record<string, string> = {
  ONLY_ELIGIBLE_CANDIDATE: 'Only one candidate met the policy requirements.',
  CORRECTNESS_BAND_THEN_LATENCY: 'The policy chose the fastest candidate within its correctness band.',
  CUTOFF_REQUIRED: 'Set an evidence cutoff before saved reports can qualify.',
  NO_EVIDENCE: 'No benchmark reports were configured.',
  NO_ELIGIBLE_EVIDENCE: 'No candidate met the policy requirements with the available evidence.',
  EVIDENCE_UNAVAILABLE: 'Saved benchmark evidence could not be read.',
  STALE_EVIDENCE: 'The benchmark evidence is too old.',
  CLOUD_NOT_VERIFIED: 'Cloud pricing has not been verified.',
  INSUFFICIENT_REPORTS: 'Too few comparable reports or attempts.',
  INSUFFICIENT_SCORED: 'Too few attempts could be scored.',
  LOW_PROVIDER_RELIABILITY: 'Provider reliability was below the threshold.',
  REPORT_NOT_FOUND: 'Saved report was not found.',
};
const explain = (code: string) => reasons[code] ?? code.toLowerCase().replaceAll('_', ' ');
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 });

function CandidateMetrics({ candidate }: { candidate: RoutingCandidate }) {
  const { metrics } = candidate;
  const stats: [string, string | number][] = [
    ['Reports', metrics.reports], ['Distinct tasks', metrics.distinctTasks],
    ['Attempts', metrics.attempted], ['Scored', metrics.scored], ['Passed', metrics.passed],
    ['Incorrect', metrics.incorrect], ['Provider failures', metrics.providerFailures],
    ['Correctness', metrics.correctness === null ? 'Unavailable' : percent.format(metrics.correctness)],
    ['Provider reliability', metrics.reliability === null ? 'Unavailable' : percent.format(metrics.reliability)],
    ['Median latency', metrics.medianLatencyMs === null ? 'Unavailable' : `${metrics.medianLatencyMs} ms`],
  ];
  return <article className="card result routing-evidence" aria-label={candidate.model}>
    <h3>{candidate.model} <small>{candidate.provider}</small></h3>
    <dl className="routing-stats">
      {stats.map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}
    </dl>
    {candidate.reasons.length > 0
      ? <ul>{candidate.reasons.map(reason => <li key={reason}>{explain(reason)}</li>)}</ul>
      : <p className="cohort-note">Meets the preview requirements.</p>}
    {candidate.pricingObservedAt && <small>Pricing checked{' '}
      <time dateTime={candidate.pricingObservedAt}>{new Date(candidate.pricingObservedAt).toLocaleString()}</time>
    </small>}
  </article>;
}

function DecisionDetails({ decision }: { decision: RoutingDecision }) {
  const limits = decision.thresholds;
  return <>
    <section className="output card result routing-evidence" aria-label="Routing decision">
      <h2>{outcomeLabels[decision.outcome]}</h2>
      <p><strong>{decision.selectedModel ?? 'No model selected'}</strong></p>
      <p>{explain(decision.reason)}</p>
      <p className="cohort-note">Evaluated <time dateTime={decision.asOf}>{new Date(decision.asOf).toLocaleString()}</time>
        {' · '}{decision.datasetId} v{decision.datasetVersion} · {decision.policyVersion}</p>
      <p className="cohort-note">Evidence cutoff {decision.evidenceCutoff
        ? <time dateTime={decision.evidenceCutoff}>{new Date(decision.evidenceCutoff).toLocaleString()}</time>
        : 'not set'}</p>
    </section>
    <section className="output" aria-label="Candidate evidence">
      <h2>Candidate evidence</h2>
      <p className="cohort-note">Requires {limits.minReports} reports, {limits.minAttempts} attempts, {limits.minScored} scored,
        and {limits.minReliabilityPercent}% reliability. Maximum evidence age {limits.maxAgeDays} days.
        Correctness band {limits.correctnessBandPoints} percentage points.</p>
      {decision.candidates.length > 0
        ? <div className="two-columns">{decision.candidates.map(candidate =>
          <CandidateMetrics key={candidate.model} candidate={candidate} />)}</div>
        : <p className="empty card">There are no candidates for this preview.</p>}
    </section>
    <section className="output card result routing-evidence" aria-label="Benchmark reports">
      <h2>Benchmark reports</h2>
      {decision.reportIds.length > 0
        ? <ul>{decision.reportIds.map(id => <li key={id}>
          <a href={api.savedReportUrl(id)} target="_blank" rel="noopener">{id}</a>
        </li>)}</ul>
        : <p className="cohort-note">No saved reports qualified for this preview.</p>}
      {decision.exclusions.length > 0 && <>
        <h3>Exclusions</h3>
        <ul>{decision.exclusions.map(({ reportId, reason }) =>
          <li key={`${reportId}-${reason}`}>{reportId && <><code>{reportId}</code> · </>}{explain(reason)}</li>)}</ul>
      </>}
    </section>
  </>;
}

export function RoutingPreview({ online }: { online: boolean }) {
  const [decision, setDecision] = useState<RoutingDecision>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef<AbortController | null>(null);
  useEffect(() => () => inFlight.current?.abort(), []);

  async function preview() {
    if (!online || inFlight.current) return;
    const controller = new AbortController();
    inFlight.current = controller;
    setBusy(true);
    setError('');
    setDecision(undefined);
    try {
      const result = await api.routingPreview(AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]));
      if (!controller.signal.aborted) setDecision(result);
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      inFlight.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return <>
    <section className="card composer" aria-label="Preview controls">
      <h2>Basic task profile</h2>
      <p className="cohort-note">The preview explains a recommendation using saved benchmark evidence. It sends no prompt or generation request.</p>
      <button className="primary" type="button" onClick={preview} disabled={!online || busy}>
        {busy ? 'Loading preview…' : 'Preview route'}
      </button>
    </section>
    {busy && <p className="notice" role="status">Checking routing evidence…</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {decision && <DecisionDetails decision={decision} />}
  </>;
}
