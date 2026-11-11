# Objective benchmarks

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

The [first three real v2 repetitions](../benchmarks/baselines/2026-11-05-qwen3-4b-v2/README.md)
passed 10/10 each with zero provider failures. The saved reports, model/runtime
metadata, and comparison to the original v1 baseline are preserved alongside the analysis.

## Fixed free cloud comparison runner

To run paired local/cloud repetitions, choose a specific currently listed free
text model ID and use:

```powershell
./scripts/run-cloud-comparison.ps1 -CloudModel 'vendor/model:free' -DatasetVersion 2 -Repetitions 3
```

The model ID above is a placeholder. The runner checks the live OpenRouter catalog
for zero prompt, completion, and per-request pricing, then checks that the key
has enough remaining free-request quota before starting. It accepts only fixed
`:free` IDs, never the variable `openrouter/free` router. The default three paired
runs make 30 local and 30 cloud attempts, with no automatic retries, paid fallback,
or model substitution. A listed model can still fail at runtime; failed attempts
are retained in reports and are not scored as incorrect answers.

Pass `-MinIntervalMs 3000 -MaxRetries 1` to enable benchmark pacing and retries.
The quota preflight checks the maximum number of possible cloud attempts,
including retries. For one ten-task repetition with one retry per task,
the check requires 20 free requests remaining.

The equivalent API request includes a schedule:

```json
{"datasetId":"basics","datasetVersion":"2","models":["qwen3:4b","vendor/model:free"],
 "schedule":{"minIntervalMs":3000,"maxRetries":1}}
```

Pacing applies to cloud attempt starts within the benchmark batch. The allowed
interval is 0–30,000 milliseconds. Up to two extra attempts per task may be
requested. Only HTTP 429 is retryable. The scheduler honors the provider's
`Retry-After` delay and uses 1, 2, then 4 seconds after successive rate limits
when the header is missing or invalid. If the requested cooldown exceeds
30 seconds, the current task retains its last response and subsequent cloud
tasks appear in `skippedCloudTasks`. Local tasks continue. Other API calls and
other application instances have their own execution paths.

Each result's `attempts` contains all actual calls in order. Its `execution`
and `score` describe the terminal attempt, so the summary still counts task
outcomes. Skipped cloud tasks have no execution record and are excluded from
those summary denominators. The history endpoint preserves each attempt
separately, including the parsed `error.retryAfterMs` where available. Earlier
reports load with one attempt per recorded execution. Omitting `schedule`
preserves the original unpaced behavior.

The first live run with this policy recovered five of six rate-limited tasks
on retry. The report compares recovery, request count, and elapsed time with
the earlier unpaced runs. [Live paced comparison](../benchmarks/comparisons/2026-11-06-qwen3-vs-lfm25-v2-paced/README.md)

Inspect any saved paced objective report locally from PowerShell:

```powershell
./scripts/summarize-paced-benchmark.ps1 -ReportPath ./benchmarks/comparisons/2026-11-06-qwen3-vs-lfm25-v2-paced/run-1.json
```

The summary separates first-attempt successes and passes from terminal
outcomes, counts retry recoveries and extra provider calls, and reports
skipped tasks separately. Other first-attempt failures can include both
provider and application errors. The script reads the report from disk.

To evaluate a paced `basics` v2 cohort offline, pass the report paths as a
PowerShell array:

```powershell
./scripts/check-paced-evidence.ps1 -ReportPath @('./run-1.json', './run-2.json', './run-3.json')
./scripts/test-paced-evidence.ps1
```

The cohort must share the same schedule, dataset identity and recorded hash,
scorer, model configuration, and deployment ID. The first-attempt gates
require a deployment ID, at least three reports and 30 cloud attempts, at
least 24 first-attempt responses, at least 80% first-attempt reliability,
and full cloud coverage without first-attempt operational errors. Recovered
responses are counted separately. The checker trusts recorded scores and
metadata, does not check freshness, and does not select a route.

The ignored `.env` must contain the database password and OpenRouter API key.
The script temporarily overrides the cloud model/enabled flag and port only in
its own process, leaving `.env` unchanged. Expanded Compose configuration and
credentials are never printed or exported. Reports, the public catalog entry,
free-request counters, and local runtime metadata are saved under a timestamped
`target/cloud-comparison-*` directory. Each report is retrieved from PostgreSQL
and every attempt is matched to execution history before verification completes.
The isolated stack is stopped afterward; its database volume is preserved.

These are end-to-end observations of a local deployment and a hosted service,
not a controlled hardware comparison. Catalog pricing and quota are observations
at preflight time, not guarantees of future availability. The cloud model ID is
fixed, but provider/server revisions and backend routing are not pinned or
independently audited. Sampling defaults are recorded as defaults, not fixed seeds.

The [first measured fixed-cloud comparison](../benchmarks/comparisons/2026-11-05-qwen3-vs-lfm25-v2/README.md)
ran three paired repetitions with actual Ollama and `liquid/lfm-2.5-2.6b:free`.
It preserves all rate-limited attempts as `NOT_SCORED` and distinguishes them from
the one successful response that failed its JSON formatting rule. See the report
for complete coverage, timings, quota observations, and limitations.

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

## Software engineering suite

The `software-engineering` dataset has a six-task pilot in version `1` and a
24-task suite in version `2`. V2 keeps all six pilot tasks and adds three tasks
per topic for a total of four each in Java, PostgreSQL, debugging, code reasoning,
API design, and structured output. Inspect the expanded dataset with
`GET /api/benchmarks/dataset?datasetId=software-engineering&version=2`.

Run it with the existing benchmark endpoint and a configured local model:

```json
{"datasetId":"software-engineering","datasetVersion":"2","models":["qwen3:4b"]}
```

Both versions grade exact text and JSON fields using `objective-v1`. They
evaluate short answers about fixed code, data, and contracts, including Java
overload resolution, SQL join filters, loop repairs, and pagination cursors.
Generated responses are treated as text or JSON data. Exact tasks specify
formatting in the prompt and can reject equivalent answers in another format.
The suite provides limited evidence about code-writing ability or broader
engineering performance.

A full v2 run makes 24 attempts per selected model before retries, so
budget quota and time accordingly if adding a cloud model. The examples in the
baseline and cloud scripts target `basics`.

### Isolated Java coding benchmark

Dataset `java-coding` version `1` contains the median-of-three task and six
trusted cases covering ordering, duplicates, negatives, and signed integer
extremes. Inspect it with
`GET /api/benchmarks/dataset?datasetId=java-coding&version=1`.

Coding evaluation is disabled by default. For trusted local use, run the host
application from the repository with PowerShell 7.2+, Docker Linux containers,
and the normal database and provider settings. Use the runner script from the
same checkout as the application build. Preload the Java image and enable the
runner when starting the application.

```powershell
docker pull eclipse-temurin:21-jdk-alpine
java -jar target/modelhub-0.4.0.jar --server.address=127.0.0.1 --modelhub.coding.runner=scripts/run-java-coding-pilot.ps1
```

Submit this JSON to `POST /api/benchmarks` for a local model run.

```json
{"datasetId":"java-coding","datasetVersion":"1","models":["qwen3:4b"]}
```

The normal generation path supplies the Java source and records the model
execution. A disabled evaluator returns `503 CODING_DISABLED` before generation.
The API takes model IDs and dataset selection. The runner path and container
configuration come from the trusted operator and repository. The standard
Compose deployment has coding evaluation disabled.

#### Opt-in raw Java format check

Send the same local-model request body to
`POST /api/benchmarks/java-coding/strict-raw` to check for a Markdown wrapper
before grading. This route requires the Java coding dataset, an enabled
runner, and configured Ollama models identified in the installed model
inventory. The report uses scoring version `java-coding-raw-v1`. The standard
route still uses `java-coding-v1` with the same frozen dataset and prompt.

A first or last nonblank line beginning with a backtick or tilde fence of at
least three characters produces `codingEvaluation.outcome: FORMAT_ERROR`
and a benchmark score of `FAIL`. The generation remains successful. The
report retains the original response and its UTF-8 SHA-256 digest, with no
image ID or case results because Docker was never invoked. The existing
empty-response and size limits still apply. Provider failures remain
`NOT_SCORED` and retain their generation error codes.

The check inspects the boundary lines. Java text blocks can contain internal
fences. Source that passes the check is sent unchanged to the restricted
Docker grader for compilation and correctness checks. The route performs
no fence extraction, repair, or retry.

#### Opt-in prose-envelope grading

Send the same local-model request body to
`POST /api/benchmarks/java-coding/strict-raw-v2` to use scoring version
`java-coding-raw-v2`. This route adds the [v2 prose-envelope check](#detector-v2)
to the boundary check. It recognizes one matching Markdown fence pair after a
supported introduction, with text following the closing fence. For an
unlabeled block, the body must start with a simple `class Main` declaration.

A rejected wrapper produces `FORMAT_ERROR` before Docker starts. The raw
response and its UTF-8 hash remain in the report. Other submissions go to the
same restricted grader as the existing routes. Both Java dataset versions
are accepted. Dataset version selects the prompt, while the route selects the
scoring version. Compare these reports separately from `java-coding-raw-v1`
reports because the new check can change the verdict for the same response.

#### Raw Java prompt comparison v1

Dataset `java-coding` version `2` is a prompt-only variant. It appends an
explicit instruction to return Java source without Markdown fences, a
filename, or explanation. Its reports store the exact new prompt and a
distinct dataset hash. The task ID, scenario family, and six sandbox cases
are shared with version `1`. The strict route accepts either dataset
version and uses the same pinned v1 sandbox fixture. Use version `1` on the
standard benchmark route.

```powershell
$uri = 'http://127.0.0.1:8080/api/benchmarks/java-coding/strict-raw'
foreach ($version in '1', '2') {
  $body = @{ datasetId = 'java-coding'; datasetVersion = $version; models = @('qwen3:4b') } | ConvertTo-Json
  Invoke-RestMethod $uri -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 300
}
```

For the first local comparison, run one request per prompt and model using
the installed Qwen2.5 Coder 3B and 7B Q4_K_M variants. Run v1 first on 3B and
v2 first on 7B. Record the model digests, settings, and both dataset hashes
before collection. The budget is four sequential model calls. Verify each
report and its distinct execution ID against PostgreSQL history. Count
format rejection, sandbox verdict, provider failure, and infrastructure
failure separately. Compare raw responses without extracting source. This
design measures one Java task with one attempt per model and prompt.

The first comparison ran on 9 November 2026. All four local generations
succeeded. Both models used Markdown fences in both prompt variants, and none
of the four original responses passed strict grading.

| Model | v1 baseline | v2 raw-source instruction |
| --- | --- | --- |
| Qwen2.5 Coder 3B Q4_K_M | `COMPILATION_ERROR`, 12.33 s generation | `FORMAT_ERROR`, 1.23 s generation |
| Qwen2.5 Coder 7B Q4_K_M | `COMPILATION_ERROR`, 10.11 s generation | `FORMAT_ERROR`, 12.42 s generation |

The v1 responses had prose before and after the fenced block, so the boundary
check passed them unchanged to Docker. Both v2 responses contained only a
fenced block, so the boundary check rejected them before Docker. The explicit
instruction removed the surrounding prose in this sample. Raw-source
compliance remained zero out of two in each arm.

The comparison verified four distinct execution IDs and four saved benchmark
reports against disposable PostgreSQL. It recorded the packaged application
hash, Ollama version and model settings, dataset hashes, and grader image.
The local model digests, grader image, and pinned inputs matched before and
after collection. No responses were extracted or rewritten, and all four
calls used local Ollama. The sample is too small to estimate a reliable
format-compliance rate or latency effect.

#### Offline prose-wrapper diagnostic

The diagnostic looks for a recognized prose introduction followed by one
nonempty `java`-labeled triple-backtick or triple-tilde block, a matching
closing fence, and nonblank text after the block. It targets the gap where
prose on both sides hides a Markdown wrapper from the boundary check. The
leading introduction requirement keeps fences inside normal Java text blocks
and comments outside this classification. Unfamiliar introductions,
unlabeled fences, multiple blocks, and mismatched markers can fall through.

```powershell
./scripts/check-raw-java-envelope.ps1 -ComparisonDirectory ./target/raw-java-prompt-v1-573e5e20c24b49f4aa1eccdbcfaabe85
./scripts/test-raw-java-envelope.ps1 -VerifyDocker
```

The read-only replay matched the four original reports to their execution
records and verified the raw response hashes. It identified both v1
prose-wrapped outputs that had reached Docker as compilation failures. Both
v2 outputs retain their original boundary `FORMAT_ERROR` verdicts. The
diagnostic produces a separate classification and neither extracts nor
grades the enclosed code. It made zero model calls.

The expanded offline corpus adds six hand-written Java controls containing
fence-like text in text blocks, Javadoc, Unicode-escaped block comments,
ordinary strings, and line comments. The detector flagged zero of six
controls, and each control passed all six cases in the restricted Docker
grader. The detector caught five of ten synthetic prose-wrapped responses. The
five misses cover an unfamiliar introduction, a bolded introduction, a curly
apostrophe, an unlabeled fence, and a four-backtick fence. The ten wrappers
were chosen to exercise both supported and unsupported patterns. This result
describes the rule's coverage on those examples and does not estimate how
often a model produces each pattern.

##### Detector v2

`-DetectorVersion 2` recognizes `Below is`, curly apostrophes, and complete
bold formatting around an introduction. It accepts longer fences when both
markers use the same character and length. For an unlabeled block, the first
nonblank line must start a simple `class Main` declaration with an opening
brace. Both versions require exactly one fence pair and nonblank text after
the closing marker. The detector stays offline and version `1` remains the
default.

```powershell
./scripts/check-raw-java-envelope.ps1 -DetectorVersion 2 -ComparisonDirectory ./target/raw-java-prompt-v1-573e5e20c24b49f4aa1eccdbcfaabe85
```

V2 detected all ten synthetic wrappers, compared with five for v1. Neither
version flagged any of the six Java controls. V2 also passed seven additional
checks, including longer tilde fences and an unlabeled Java class, and left
ambiguous markers and unlabeled non-Java examples unclassified. Both
versions reproduced the four saved classifications. These are hand-written
examples and a four-response replay, so they establish coverage for the
tested patterns. The detector does not parse or compile Java.

For manual grading, save a candidate's UTF-8 source as a file and invoke the
same runner directly.

```powershell
./scripts/run-java-coding-pilot.ps1 -SourcePath ./Main.java
```

Each test case gets a fresh non-root container with networking disabled, a
read-only root filesystem, a 16 MiB temporary filesystem, one CPU, 256 MiB
of memory, and a 64-process limit. The runner sends at most 16 KiB of source
over stdin and mounts no host files. Java compiles and runs inside the
container, with annotation processing disabled. Capabilities are dropped and
privilege escalation is disabled. The host checks the exit state and bounded
standard output.
Each case has an eight-second default timeout and a 4,096-character cap
per output stream. The timeout covers the attached Docker client and the
submission. The API bounds the runner process to 90 seconds and attempts to
remove that run's containers on failure. Docker must remain responsive for
setup and cleanup.

The API stores the dataset snapshot, version, model, generation execution ID,
and optional `codingEvaluation` in the existing JSONB benchmark report.
`GET /api/benchmarks/{id}` returns the same report. The coding verdict includes
source hash, image ID, timeout, total evaluation time, and per-case outcomes
and durations when the runner returns a verified report. Model latency remains
in the generation result.

| Outcome | Benchmark score |
| --- | --- |
| Every case passes | `PASS` |
| Wrong answer, compilation error, execution error, timeout, output limit, invalid submission, or opt-in format rejection | `FAIL` |
| Provider failure or evaluator infrastructure error | `NOT_SCORED` |

The aggregate coding verdict uses the first non-passing case and retains all
individual case outcomes. An infrastructure error has an explicit coding
verdict and no verified cases. A provider failure has its normal execution
error and no coding verdict. For coding reports, summary `successful` counts
scored outcomes and `failed` counts unscored outcomes. Candidate stdout and
stderr are omitted from reports. The standalone script prints its JSON report
and exits with an error for infrastructure failures.

#### Coding report breakdown

New Java coding reports include a `coding` object in each model summary.
`sandboxVerdicts` counts submissions with a verified grader verdict.
`sandboxOutcomes` groups those verdicts by outcome, so `PASS`, `WRONG_ANSWER`,
and compilation, execution, timeout, or output-limit failures remain separate.
Outcome keys with zero observations are omitted. `invalidSubmissions` counts
empty or oversized submissions, and `graderInfrastructureFailures` counts
attempts where the grader could not return a verified verdict.

For strict raw scoring, `rawFormat.checked` counts submissions that reached
the format check after generation and size validation. It includes format
rejections and submissions that proceeded to the grader, even if the grader
later failed. `rawFormat.rejected` counts only the format rejections. The
standard route omits `rawFormat`. `executionErrors` groups terminal execution
failures by error code, including provider and history failures. Retry
attempts remain in the result details and skipped tasks remain in
`skippedCloudTasks`.

Use `rawFormat.checked` as the format-rejection denominator and
`sandboxVerdicts` as the denominator for the share of sandbox submissions
that passed. Existing summary fields retain their earlier definitions.
Historical reports and objective benchmark summaries omit the new object.
[Milestone 12A evidence](structured-output-reliability.md)

#### Compare saved coding runs

In the [local web UI](../README.md#local-web-ui-on-windows-11), open **Coding
Cohorts** to find compatible reports, compare totals, and filter the timeline.
The report picker uses `GET /api/benchmarks/coding-reports`. It returns up to
20 saved Java report references with IDs, completion times, dataset versions,
and scoring versions. It orders them newest first, breaking timestamp ties
by descending report ID. Use a saved report ID to reach older reports.

Milestone 12B provides a read-only cohort summary. Send one to twenty
distinct saved Java report IDs to `POST /api/benchmarks/coding-cohort`.

```json
{"reportIds":["00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002"]}
```

The response gives the dataset version and hash, scoring version, model
configuration, and per-model totals for the [coding metrics](#coding-report-breakdown).
The endpoint calculates metrics and the `timeline` from saved terminal
results, so historical coding reports can be included. A request reads
PostgreSQL and makes zero model calls or writes.

The `timeline` has one row per saved task and model result. Each row has
`reportId`, `completedAt`, `taskId`, `model`, `executionId`, and the saved
`executionStatus`. The order is ascending report completion time, then
report UUID (Java UUID natural order). Within a report, rows are ordered by
model ID, task ID, and execution UUID. Each row uses its report's completion
time because reports lack individual execution timestamps. Retries are
available in the original report and do not get separate timeline rows.

- `formatValidation` is `PASSED` or `REJECTED` when the saved outcome
  establishes what the strict raw Java check decided. `NOT_CHECKED` covers
  standard scoring, unavailable generation or grade data, and submissions
  rejected before the check. A format pass can still be followed by a grader
  failure or a failed sandbox verdict.
- `sandboxVerdict` records the saved sandbox outcome, such as `PASS`,
  `COMPILATION_ERROR`, `EXECUTION_ERROR`, or `WRONG_ANSWER`. It is null
  when no sandbox verdict is available.
- `error` identifies the failure stage and saved code. For example, a
  provider `TIMEOUT` has stage `PROVIDER` and a sandbox `TIMEOUT` has
  stage `SANDBOX`. Other stages include `FORMAT`, `GRADER`, `SUBMISSION`,
  `HISTORY`, and `GENERATION`. A successful generation with no recorded
  grade shows `GRADER` / `MISSING_EVALUATION`. A failed execution without a
  saved code uses a null code.
- `executionDurationMs` measures the terminal generation attempt.
  `gradingDurationMs` measures the saved evaluation, which may have ended
  before Docker ran. `sandboxTimeoutSeconds` is the saved per-case limit.
  Grading duration and timeout are null when the corresponding data is
  unavailable.

Reports must share the exact dataset snapshot and hash, scoring version,
ordered model configuration, schedule, and deployment ID. Verified sandbox
verdicts must share the grader image and timeout. Reports that only contain
format rejections or unavailable runs can contribute without grader metadata.
The endpoint rejects incompatible inputs and reused execution IDs, including
reused retries, with `INVALID_COHORT`. A missing report returns `NOT_FOUND`.

Use one cohort per compatible set. Matching metadata describes the saved
configuration. Model weights or settings that were never pinned remain
unknown, and repeats of a task remain repeats. The totals are descriptive.

##### Find compatible reports

`GET /api/benchmarks/{id}/coding-peers` uses an existing Java report as the
seed. The `limit` parameter controls the number of peers from 1 to 19 and
defaults to 19. The response includes the seed ID first, followed by the
selected peers in newest-first order. The whole `reportIds` array can be
passed to the cohort endpoint.

```powershell
$base = 'http://127.0.0.1:8080/api/benchmarks'
$seedId = '<saved-java-report-id>'
$selection = Invoke-RestMethod "$base/$seedId/coding-peers?limit=5"
$body = @{ reportIds = $selection.reportIds } | ConvertTo-Json
Invoke-RestMethod "$base/coding-cohort" -Method Post -ContentType 'application/json' -Body $body
```

Discovery checks up to 100 recent reports with matching stored metadata.
It skips a candidate if adding it would reuse an execution or make the set
incompatible. `candidatesChecked` counts the reports examined in that
window. `windowLimited` is true if the window ended before the requested
peer count was reached and older candidates exist. This read-only search
uses one saved report as its starting point and makes zero model calls.

##### Inspect saved execution evidence

Choose **Inspect evidence** on a timeline row. The dashboard reads
`GET /api/benchmarks/{id}` and matches the saved execution ID, model, and task.
It shows the original response as plain text, the saved score, any earlier
attempts in their recorded order, and grader case outcomes and durations.
Provider failures, format rejections, and older reports can lack grader
cases. The dashboard labels these absences and keeps the current filters
and full report JSON link available. Opening evidence makes no model calls
and starts no grading.

The public fixtures measure behavior on six inputs, so hard-coded answers can
pass. The coding benchmark is synchronous and limited to a single Java source
file of at most 16 KiB. Container isolation relies on the Docker runtime and
kernel. The benchmark is intended for trusted local deployments with an
operator-controlled Docker daemon and runner script.
[Docker Engine security](https://docs.docker.com/engine/security/)

Run the automated fixture checks with
`./scripts/test-java-coding-pilot.ps1`. They check grading and failure cases
along with network, filesystem, process privileges, and cgroup v2 resource
limits. The API integration test covers the disabled and unavailable runner
paths by default. To exercise real Docker grading and PostgreSQL persistence
with stub model responses, preload the image and run
`mvn verify -Dmodelhub.test.coding-docker=true`.

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

Requests require a registered dataset ID and one or two distinct configured model IDs.
The dataset inspection endpoint defaults to `basics` version `1`, and run
requests that omit `datasetVersion` use version `1` of the specified dataset.
All selections are validated before provider calls. Tasks execute sequentially;
two models for the same task execute concurrently using the existing comparison
service. At most one benchmark batch runs per application instance; overlapping
requests return HTTP 429 `BENCHMARK_BUSY`. This is not a cluster-wide limit.

The endpoint is synchronous. With default 120-second provider timeouts, ten
unpaced tasks can take about 20 minutes plus persistence overhead. With two
retries and pacing enabled, a run can take up to about 75 minutes plus overhead.
Set the client and proxy timeouts accordingly. The server can continue a batch
after the client disconnects. Cloud benchmarking uses free request quota for
the selected model. Automated tests use stub providers.

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
