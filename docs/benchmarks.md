# Milestone 7: objective benchmarks

The bundled `basics` dataset, versions `1` and `2`, contains ten fixed prompts for simple
arithmetic, factual answers, instruction following, and structured JSON output.
This is a small regression dataset, not a validated general model-quality benchmark.
No model is automatically declared the winner.

Version 2 clarifies only the `null-field` prompt: the label must be the literal
JSON string `"empty"`, not an empty string. Expected values and scoring rules are
unchanged. V1, its hash, and all old reports are preserved. Missing or null
`datasetVersion` defaults to `"1"` for backward compatibility; explicitly select
`"2"` for new measurements. Other versions return HTTP 400 before any model call.

```powershell
$base = 'http://localhost:8080'
Invoke-RestMethod "$base/api/benchmarks/dataset?version=2"
$body = @{datasetId='basics'; datasetVersion='2'; models=@('qwen3:4b')} | ConvertTo-Json
```

Use `scripts/run-local-baselines.ps1` to run three sequential local repetitions of
v2 (30 inference calls). It forces OpenRouter off, exports every retrieved report
and runtime metadata under a timestamped `target/local-baselines-v2-*` directory,
checks all results against saved history, and stops its isolated Compose stack.
Provider defaults remain unchanged; the runner checks the model digest and Ollama
version before and after the measurement. It does not reload the model between
runs, control sampling, retry failed tasks, or make cloud calls.

```powershell
./scripts/run-local-baselines.ps1
# Optional: repeat the original dataset instead.
./scripts/run-local-baselines.ps1 -DatasetVersion 1 -Repetitions 3
```

Each repetition can take several minutes. Compare v1 and v2 as different prompt
conditions, not as a controlled measure of improved model quality. Three runs are
a small descriptive sample, not enough to establish statistical superiority.

## Run and inspect

Run PostgreSQL and the app using the existing Compose or host-Java setup.
Flyway applies V2 to add `benchmark_reports`, preserving V1 execution history.
No database reset is needed. The benchmark uses the same configured providers
and generation history as `/api/generate`.

```powershell
$base = 'http://localhost:8080'
Invoke-RestMethod "$base/api/benchmarks/dataset" | ConvertTo-Json -Depth 20
# Local-only evaluation: ten local calls, no cloud requests.
$body = @{ datasetId = 'basics'; models = @('qwen3:4b') } | ConvertTo-Json
$report = Invoke-RestMethod "$base/api/benchmarks" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
$report.summaries | Format-Table model, attempted, successful, failed, passed, scoringFailures, passRateAllAttempts, averageSuccessfulDurationMs
$saved = Invoke-RestMethod "$base/api/benchmarks/$($report.id)"
$saved | ConvertTo-Json -Depth 30 | Set-Content benchmark-report.json
```

To evaluate both providers, select a specific free model ID that appears in
your configured `/api/models` listing:

```powershell
$body = @{ datasetId = 'basics'; models = @('qwen3:4b', $env:OPENROUTER_MODEL) } | ConvertTo-Json
$report = Invoke-RestMethod "$base/api/benchmarks" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 1800
```

For host PowerShell, `$env:OPENROUTER_MODEL` must be set explicitly; Compose's
`.env` values are not automatically loaded into your shell. Alternatively use
the configured model identifier directly. `openrouter/free` is refused even if
it appears in the model registry. General generation/comparison still permit it.
Paid cloud model IDs remain prohibited by the existing adapter/registry.

## Scoring contract (`objective-v1`)

| Rule | PASS condition |
| --- | --- |
| `EXACT` | Whole response equals the expected string after trimming surrounding whitespace; comparison is case-sensitive |
| `JSON_OBJECT` | Entire response parses as one JSON object, with no markdown or trailing tokens |
| `JSON_FIELDS` | Entire response is one JSON object containing every specified field with the expected JSON value and type; additional fields are allowed |

For field checks, arrays are order-sensitive and nested objects are compared
structurally. An explicitly required null must be present; a missing field fails.
No regex extraction, markdown stripping, subjective AI judge, or generated-code
execution is used. The `/no_think` instruction is part of the saved prompt and
may be ignored by a model. Extra explanatory text fails exact/JSON rules.

Each execution has a separate operational `status` (`SUCCESS`/`FAILED`) and score
(`PASS`/`FAIL`/`NOT_SCORED`). Failed provider or history operations receive
`NOT_SCORED`, never a fabricated response score. Other executions continue.

| Summary field | Meaning |
| --- | --- |
| `attempted` | Number of dataset tasks attempted for that model |
| `successful`, `failed` | Execution statuses, independent of task scoring |
| `passed`, `scoringFailures` | Successful responses passing/failing their rule |
| `passRateAmongSuccessful` | passed / successful, or null if no successes |
| `passRateAllAttempts` | passed / attempted; operational failures stay in the denominator |
| `averageSuccessfulDurationMs` | Mean provider duration of successful executions only, or null if no successes |

Rates are fractions from 0 to 1. Failed-attempt duration and error information
remain in the per-task results. Average latency excludes database writes and
failed attempts; it is not a throughput measurement. One attempt per task gives
no confidence interval and does not establish statistical superiority.

## Saved report and repeatability

HTTP 201 returns a report and `Location: /api/benchmarks/{id}` after all attempts
and the report save complete. GET retrieves the same saved snapshot. Reports
include the complete dataset, dataset version/SHA-256, scoring version, model
identifiers, provider names, request timeouts, provider-default inference setting
description, timestamps, responses, durations, errors, scores, and summaries.
Execution UUIDs link back to the ordinary history records. Credentials and
provider URLs are not copied into the report. Treat report exports as potentially
sensitive because they contain the prompts and responses.
The dataset hash is computed from its compact serialized snapshot, so source-file
line endings do not change its identity. The example `benchmark-report.json`
export is ignored by Git.

Repeatability means the same recorded prompts and scoring rules can be rerun.
It does not guarantee identical model output. Sampling uses provider defaults;
seeds, model weights/digests, and server versions are not pinned. A fixed cloud ID
still may change over time. Compare reports with matching dataset hash and scoring
version, and inspect configuration before drawing conclusions.

## Execution limits and failures

Requests require dataset ID `basics` and one or two distinct configured model IDs.
All selections are validated before provider calls. Tasks execute sequentially;
two models for the same task execute concurrently using the existing comparison
service. At most one benchmark batch runs per application instance; overlapping
requests return HTTP 429 `BENCHMARK_BUSY`. This is not a cluster-wide limit.

The endpoint is synchronous. With default 120-second provider timeouts, ten tasks
can take about 20 minutes plus persistence overhead. Set your HTTP client/proxy
timeout accordingly. Disconnecting the client does not cancel the server batch.
There are no retries, resume support, scheduled jobs, or automatic cloud requests.
Cloud benchmarking makes ten calls per selected cloud model and consumes free
request quota. Automated tests do not use your `.env` key.

The database/report table is checked before execution. Every attempt is recorded
through the history service; the full benchmark report is saved once at the end.
If a process stops or the final report save fails, no completed report is created,
but already-saved execution history remains. A database outage returns a sanitized
503 error; callers should inspect history before rerunning a potentially completed
batch. Failed history writes are represented as failed attempts as in generation.

Invalid requests return 400, unknown report UUIDs return 404, and malformed UUIDs
return 400. This adds no authentication; keep the trusted-local deployment model.

## Verification

Unit tests exercise exact/JSON scoring, type/null/array checks, trailing-token
rejection, provider failures, aggregate denominators, selection validation, and
concurrency limits. PostgreSQL Testcontainers tests upgrade from V1, preserve
existing history, save partial-failure reports, correlate execution IDs, and
retrieve reports after an application restart. All AI calls in these tests use
fake providers or local HTTP stubs. Separately, the
[first real local baseline](../benchmarks/baselines/2026-11-05-qwen3-4b/README.md)
completed all ten tasks with nine scoring passes. Its report, runtime metadata,
history correlation evidence, and failed-task analysis are preserved alongside it.
