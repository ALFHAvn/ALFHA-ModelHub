import type { Model } from './api';

export function ModelSelect({ label, models, value, onChange, disabled, placeholder }: {
  label: string; models: Model[]; value: string; onChange: (value: string) => void; disabled: boolean;
  placeholder?: string;
}) {
  return <label className="field">
    <span>{label}</span>
    <span className="select-wrap">
      <select value={value} onChange={event => onChange(event.target.value)} disabled={disabled}>
        {(placeholder || !models.length) && <option value="">{placeholder ?? 'No configured models'}</option>}
        {models.map(model => <option key={model.model} value={model.model}>
          {model.model} · {model.provider}
        </option>)}
      </select>
      <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </span>
  </label>;
}
