import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { api, type Execution, type Model } from './api';
import { CodingCohort } from './CodingCohort';
import { ExecutionDetails } from './ExecutionDetails';
import { History } from './History';
import { ModelSelect } from './ModelSelect';
import { ProposalReview } from './ProposalReview';
import { RoutingPreview } from './RoutingPreview';

const navigation = [
  { id: 'chat', label: 'Chat', icon: 'chat', description: 'Send a prompt to a configured model.' },
  { id: 'compare', label: 'Compare', icon: 'compare', description: 'Send the same prompt to two models and compare their responses.' },
  { id: 'review', label: 'Proposal & Review', icon: 'review', description: 'Ask one model to draft a response and a second model to review it.' },
  { id: 'history', label: 'History', icon: 'history', description: 'Review saved prompts, responses, and execution outcomes.' },
  { id: 'coding', label: 'Coding Cohorts', icon: 'coding', description: 'Compare saved Java benchmark results and inspect what happened in each run.' },
  { id: 'routing', label: 'Routing Preview', icon: 'routing', description: 'See which model the basic routing policy recommends and why.' },
] as const;
type Tab = typeof navigation[number]['id'];
type IconName = 'logo' | 'chat' | 'compare' | 'review' | 'history' | 'coding' | 'routing' | 'refresh' | 'send';
const paths: Record<IconName, ReactNode> = {
  logo: <><path d="m12 2 9 5v10l-9 5-9-5V7z"/><path d="m3 7 9 5 9-5m-9 5v10M7.5 4.5l9 5"/></>,
  chat: <path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H4l1.5-4A7.5 7.5 0 1 1 20 11.5ZM8 11.5h8m-8 3h5"/>,
  compare: <path d="M3 5h18v14H3zM12 5v14M6 9h3m6 0h3m-12 4h3m6 0h3"/>,
  review: <path d="M4 3h11l4 4v14H4zM14 3v5h5m-11 7 2 2 5-5"/>,
  history: <path d="M3 12a9 9 0 1 0 3-6.7L3 8m0-5v5h5m4-1v5l3 2"/>,
  coding: <path d="M5 4h14v16H5zM10 8l-3 4 3 4m4-8 3 4-3 4"/>,
  routing: <path d="M4 5h4v4H4zM16 3h4v4h-4zM16 17h4v4h-4zM8 7h3v12h5m-5-14h5"/>,
  refresh: <path d="M20 11a8 8 0 1 0-2.3 6M20 5v6h-6"/>,
  send: <path d="m22 2-7 20-4-9-9-4 20-7ZM22 2 11 13"/>,
};

function Icon({ name }: { name: IconName }) {
  return <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function Workspace({ mode, models, online }: { mode: 'chat' | 'compare'; models: Model[]; online: boolean }) {
  const comparing = mode === 'compare';
  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  const [prompt, setPrompt] = useState('');
  const [results, setResults] = useState<Execution[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const firstModel = models.find(model => model.model === first) ?? models[0];
  const secondChoices = models.filter(model => model.model !== firstModel?.model);
  const secondModel = secondChoices.find(model => model.model === second) ?? secondChoices[0];
  const canSubmit = online && !!firstModel && (!comparing || !!secondModel) && !!prompt.trim() && !busy;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit || !firstModel || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    setResults([]);
    try {
      setResults(comparing && secondModel
        ? (await api.compare([firstModel.model, secondModel.model], prompt)).results
        : [await api.generate(firstModel.model, prompt)]);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
      inFlight.current = false;
    }
  }

  return <>
    <form className="card composer" onSubmit={submit} aria-busy={busy}>
      <div className={`model-fields ${comparing ? 'two-columns' : ''}`}>
        <ModelSelect label={comparing ? 'First model' : 'Model'} models={models} value={firstModel?.model ?? ''}
          onChange={setFirst} disabled={busy || !models.length} />
        {comparing && <ModelSelect label="Second model" models={secondChoices} value={secondModel?.model ?? ''}
          onChange={setSecond} disabled={busy || !secondChoices.length} />}
      </div>
      {comparing && models.length < 2 && <p className="notice">Configure a second model on the backend to enable comparison.</p>}
      {(firstModel?.provider === 'openrouter' || (comparing && secondModel?.provider === 'openrouter')) &&
        <p className="notice">This selection sends the prompt to OpenRouter.</p>}
      <label className="field prompt-field">
        <span>Prompt</span>
        <textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={5} maxLength={32000}
          placeholder="What would you like to ask?" disabled={busy} />
      </label>
      <div className="form-footer">
        <span className="count">{prompt.length.toLocaleString()} / 32,000</span>
        <button className="primary" disabled={!canSubmit} type="submit">
          {busy ? <span className="spinner" aria-hidden="true" /> : <Icon name={comparing ? 'compare' : 'send'} />}
          {busy ? 'Running…' : comparing ? 'Compare models' : 'Send prompt'}
        </button>
      </div>
    </form>
    <section className="output" aria-label={comparing ? 'Comparison results' : 'Model response'}>
      <h2>{comparing ? 'Comparison results' : 'Response'}</h2>
      {error && <p className="notice error" role="alert">{error}</p>}
      {busy && <div className="empty card" role="status">Waiting for {comparing ? 'both models' : 'the model'} to respond…</div>}
      {!busy && !error && !results.length && <div className="empty card">
        <span className="empty-icon"><Icon name={comparing ? 'compare' : 'chat'} /></span>
        <strong>{comparing ? 'No comparison yet' : 'No response yet'}</strong>
        <span>{comparing ? 'Responses will appear side by side here.' : 'Send a prompt to see the model’s response here.'}</span>
      </div>}
      <div className={comparing ? 'two-columns' : undefined}>
        {results.map(result => <article key={result.requestId} className="card result">
          <h3>{result.model}</h3>
          <ExecutionDetails result={result} />
        </article>)}
      </div>
    </section>
  </>;
}

export function App() {
  const [tab, setTab] = useState<Tab>('chat');
  const [status, setStatus] = useState<'checking' | 'online' | 'offline'>('checking');
  const [models, setModels] = useState<Model[]>([]);
  const [modelsError, setModelsError] = useState('');
  const [loadingModels, setLoadingModels] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const online = status === 'online';
  const current = navigation.find(item => item.id === tab)!;

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function check() {
      try {
        await api.health();
        if (active) setStatus('online');
      } catch {
        if (active) setStatus('offline');
      } finally {
        if (active) timer = setTimeout(check, 10000);
      }
    }
    void check();
    return () => { active = false; clearTimeout(timer); };
  }, [refresh]);

  useEffect(() => {
    if (!online) return;
    let active = true;
    setLoadingModels(true);
    setModelsError('');
    api.models().then(list => { if (active) setModels(list); })
      .catch(error => {
        if (active) { setModels([]); setModelsError(error.message); }
      }).finally(() => { if (active) setLoadingModels(false); });
    return () => { active = false; };
  }, [online, refresh]);

  return <div className="shell">
    <aside className="sidebar">
      <div className="brand"><span className="brand-icon"><Icon name="logo" /></span>
        <div><strong>ModelHub</strong><span>ALFHA WORKSPACE</span></div>
      </div>
      <nav aria-label="Workspace">
        <p className="nav-label">WORKSPACE</p>
        {navigation.map(item => <button key={item.id} onClick={() => setTab(item.id)}
          aria-current={tab === item.id ? 'page' : undefined}>
          <Icon name={item.icon} />{item.label}
        </button>)}
      </nav>
      <div className="sidebar-footer"><span className="local-dot" />Local development</div>
    </aside>
    <main>
      <div className="topbar">
        <span className="breadcrumb">Workspace <span>/</span> <strong>{current.label}</strong></span>
        <div className="connection">
          <span className={`connection-status ${status}`} role="status">
            <span />{status === 'online' ? 'Backend online' : status === 'offline' ? 'Backend offline' : 'Checking backend'}
          </span>
          <button className="icon-button" type="button" aria-label="Refresh connection and models"
            onClick={() => setRefresh(value => value + 1)}><Icon name="refresh" /></button>
        </div>
      </div>
      <div className="content">
        <header className="page-header">
          <p className="eyebrow">MODEL WORKSPACE</p>
          <h1>{current.label}</h1>
          <p>{current.description}</p>
        </header>
        {status === 'offline' && <p className="notice error" role="alert">
          Cannot reach ModelHub. Start the Spring Boot backend on port 8080, then refresh the connection.
        </p>}
        {(tab === 'chat' || tab === 'compare' || tab === 'review') && online && (modelsError
          ? <p className="notice error" role="alert">{modelsError}</p>
          : loadingModels && !models.length
            ? <p className="notice" role="status">Loading configured models…</p>
            : !models.length && <p className="notice">No models are configured on the backend.</p>)}
        <div hidden={tab !== 'chat'}><Workspace mode="chat" models={models} online={online} /></div>
        <div hidden={tab !== 'compare'}><Workspace mode="compare" models={models} online={online} /></div>
        <div hidden={tab !== 'review'}><ProposalReview models={models} online={online} /></div>
        {tab === 'history' && <History online={online} />}
        {tab === 'coding' && <CodingCohort online={online} refresh={refresh} />}
        {tab === 'routing' && <RoutingPreview online={online} />}
      </div>
    </main>
  </div>;
}
