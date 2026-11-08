import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, type CodingCohortReport, type CodingPeers, type SavedCodingReport } from './api';
import { CodingCohortResults } from './CodingCohortResults';

const reportIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
type Action = 'discover' | 'build';

export function CodingCohort({ online, refresh }: { online: boolean; refresh: number }) {
  const [recent, setRecent] = useState<SavedCodingReport[]>();
  const [listError, setListError] = useState('');
  const [seed, setSeed] = useState('');
  const [discovery, setDiscovery] = useState<CodingPeers>();
  const [selected, setSelected] = useState<string[]>([]);
  const [cohort, setCohort] = useState<CodingCohortReport>();
  const [busy, setBusy] = useState<Action>();
  const [error, setError] = useState('');
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!online) return;
    const controller = new AbortController();
    setRecent(undefined);
    setListError('');
    api.recentCodingReports(AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]))
      .then(reports => { if (!controller.signal.aborted) setRecent(reports); })
      .catch(failure => { if (!controller.signal.aborted) setListError(failure.message); });
    return () => controller.abort();
  }, [online, refresh]);

  useEffect(() => () => inFlight.current?.abort(), []);

  function chooseSeed(value: string) {
    setSeed(value);
    setDiscovery(undefined);
    setSelected([]);
    setCohort(undefined);
    setError('');
  }

  async function read(action: Action, load: (signal: AbortSignal) => Promise<void>) {
    if (!online || inFlight.current) return;
    const controller = new AbortController();
    inFlight.current = controller;
    setBusy(action);
    setError('');
    setCohort(undefined);
    try {
      await load(AbortSignal.any([controller.signal, AbortSignal.timeout(15000)]));
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      inFlight.current = null;
      if (!controller.signal.aborted) setBusy(undefined);
    }
  }

  function findPeers(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = seed.trim();
    if (!reportIdPattern.test(id)) {
      setError('Enter a saved benchmark report ID in UUID format.');
      return;
    }
    void read('discover', async signal => {
      setDiscovery(undefined);
      setSelected([]);
      const peers = await api.codingPeers(id, signal);
      if (signal.aborted) return;
      setDiscovery(peers);
      setSelected(peers.reportIds);
    });
  }

  function buildCohort() {
    if (!selected.length) return;
    void read('build', async signal => {
      const result = await api.codingCohort(selected, signal);
      if (!signal.aborted) setCohort(result);
    });
  }

  return <>
    <form className="card composer cohort-picker" onSubmit={findPeers} aria-label="Find compatible coding reports">
      <h2>Choose a saved report</h2>
      <p className="cohort-note">Use a saved Java benchmark as the starting report. This dashboard reads saved results only.</p>
      {online && !recent && !listError && <p className="notice" role="status">Loading recent coding reports…</p>}
      {listError && <p className="notice error" role="alert">Could not load recent reports. {listError}</p>}
      {recent?.length === 0 && <p className="notice">No saved Java coding reports are available.</p>}
      <div className="two-columns">
        <label className="field">
          <span>Recent coding report</span>
          <select disabled={!online || !!busy || !recent?.length}
            value={recent?.some(report => report.id === seed) ? seed : ''}
            onChange={event => chooseSeed(event.target.value)}>
            <option value="">Choose a recent report</option>
            {recent?.map(report => <option key={report.id} value={report.id}>
              {new Date(report.completedAt).toLocaleString()} · Dataset v{report.datasetVersion} · {report.scoringVersion} · {report.id.slice(0, 8)}
            </option>)}
          </select>
        </label>
        <label className="field">
          <span>Saved report ID</span>
          <input value={seed} onChange={event => chooseSeed(event.target.value)}
            disabled={!online || !!busy} placeholder="Paste a report UUID" autoComplete="off" spellCheck={false} />
        </label>
      </div>
      <div className="form-footer cohort-actions">
        <button className="primary" disabled={!online || !seed.trim() || !!busy} type="submit">
          {busy === 'discover' ? 'Finding peers…' : 'Find compatible peers'}
        </button>
      </div>
    </form>
    {discovery && <section className="card composer output" aria-label="Compatible reports">
      <h2>Compatible reports</h2>
      <p className="cohort-note">{discovery.reportIds.length - 1} compatible peers found after checking {discovery.candidatesChecked} candidates.
        {discovery.windowLimited && ' The search checked only the recent candidate window.'}</p>
      {discovery.reportIds.length === 1 && <p className="notice">You can build a cohort with just the starting report.</p>}
      <ul className="cohort-report-list">
        {discovery.reportIds.map(id => <li key={id}>
          <label>
            <input type="checkbox" checked={selected.includes(id)} disabled={!online || !!busy}
              onChange={event => {
                setSelected(event.target.checked ? [...selected, id] : selected.filter(value => value !== id));
                setCohort(undefined);
                setError('');
              }} />
            <span>{id === discovery.seedReportId ? 'Starting report' : 'Peer report'} <code>{id}</code></span>
          </label>
          <a href={api.savedReportUrl(id)} target="_blank" rel="noopener">Open report JSON</a>
        </li>)}
      </ul>
      <div className="form-footer">
        <span className="count">{selected.length} selected</span>
        <button className="primary" type="button" disabled={!online || !selected.length || !!busy} onClick={buildCohort}>
          {busy === 'build' ? 'Building cohort…' : 'Build cohort'}
        </button>
      </div>
    </section>}
    {busy && <p className="notice" role="status">{busy === 'discover' ? 'Checking saved reports for compatible peers…' : 'Loading cohort results…'}</p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {cohort && <CodingCohortResults cohort={cohort} />}
  </>;
}
