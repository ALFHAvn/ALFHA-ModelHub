import { useRef, useState, type FormEvent } from 'react';
import { api, type Model, type ProposalReviewResult, type WorkflowStep } from './api';
import { ExecutionDetails } from './ExecutionDetails';
import { ModelSelect } from './ModelSelect';

const fixedFreeModel = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+:free$/;
const maxPromptLength = 32000;

function StepResult({ title, step }: { title: string; step: WorkflowStep }) {
  return <article className="card result" aria-label={title}>
    <h3>{title}</h3>
    <p className="workflow-model">{step.model}</p>
    <ExecutionDetails result={step} />
  </article>;
}

export function ProposalReview({ models, online }: { models: Model[]; online: boolean }) {
  const [proposerId, setProposer] = useState('');
  const [reviewerId, setReviewer] = useState('');
  const [allowCloud, setAllowCloud] = useState(false);
  const [prompt, setPrompt] = useState('');
  const [result, setResult] = useState<ProposalReviewResult>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const cloudModels = models.filter(model => model.provider === 'openrouter' && fixedFreeModel.test(model.model));
  const eligible = models.filter(model => model.provider === 'ollama').concat(allowCloud ? cloudModels : []);
  const proposer = eligible.find(model => model.model === proposerId);
  const reviewer = eligible.find(model => model.model === reviewerId);
  const usesCloud = proposer?.provider === 'openrouter' || reviewer?.provider === 'openrouter';
  const canSubmit = online && proposer && reviewer && proposer.model !== reviewer.model &&
    prompt.trim() && prompt.length <= maxPromptLength && !busy;

  function changeCloud(checked: boolean) {
    setAllowCloud(checked);
    if (!checked) {
      if (proposer?.provider === 'openrouter') setProposer('');
      if (reviewer?.provider === 'openrouter') setReviewer('');
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    setResult(undefined);
    try {
      setResult(await api.proposalReview(proposer.model, reviewer.model, prompt, allowCloud && usesCloud));
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setBusy(false);
      inFlight.current = false;
    }
  }

  return <>
    <form className="card composer" onSubmit={submit} aria-busy={busy}>
      <div className="two-columns">
        <ModelSelect label="Proposer model" models={eligible.filter(model => model.model !== reviewer?.model)}
          value={proposer?.model ?? ''} onChange={setProposer} disabled={busy || !online} placeholder="Choose a proposer" />
        <ModelSelect label="Reviewer model" models={eligible.filter(model => model.model !== proposer?.model)}
          value={reviewer?.model ?? ''} onChange={setReviewer} disabled={busy || !online} placeholder="Choose a reviewer" />
      </div>
      {online && eligible.length < 2 && <p className="notice">
        Choose two distinct models. Configure a second Ollama model or opt in to an eligible OpenRouter model if one is configured.
      </p>}
      {!!cloudModels.length && <div className="notice">
        <label className="workflow-opt-in">
          <input type="checkbox" checked={allowCloud} onChange={event => changeCloud(event.target.checked)} disabled={busy || !online} />
          Allow a configured free OpenRouter model
        </label>
        <p>A cloud proposer receives your task. A cloud reviewer receives your task and the proposal.</p>
      </div>}
      <label className="field prompt-field">
        <span>Task or question</span>
        <textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={5} maxLength={maxPromptLength}
          placeholder="What should the proposer work on?" disabled={busy} />
      </label>
      <div className="form-footer">
        <span className="count">{prompt.length.toLocaleString()} / 32,000</span>
        <button className="primary" type="submit" disabled={!canSubmit}>
          {busy && <span className="spinner" aria-hidden="true" />}
          {busy ? 'Running workflow…' : 'Run proposal and review'}
        </button>
      </div>
    </form>
    <section className="output" aria-label="Workflow results">
      <h2>Workflow results</h2>
      {busy && <p className="notice" role="status">Waiting for the workflow. The reviewer runs after the proposer.</p>}
      {error && <p className="notice error" role="alert">{error}</p>}
      {!busy && !error && !result && <p className="empty card">The proposal and feedback will appear here.</p>}
      {result && <>
        {result.proposal.status === 'SUCCESS' && result.review.status !== 'SUCCESS' &&
          <p className="notice" role="status">The proposal is available. The review did not complete.</p>}
        <div className="two-columns">
          <StepResult title="Proposal" step={result.proposal} />
          <StepResult title="Reviewer feedback" step={result.review} />
        </div>
      </>}
    </section>
  </>;
}
