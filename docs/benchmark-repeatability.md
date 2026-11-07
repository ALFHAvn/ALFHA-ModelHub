# Milestone 10A · Benchmark coverage and repeatability

## Coverage audit

| Suite | What it measures | Main gaps |
| --- | --- | --- |
| Basics v1 and v2 | The same 10 task IDs. Four exact answers cover arithmetic, capital recall, a Boolean answer, and sorting. Six JSON tasks cover syntax, nesting, types, arrays, and explicit null. V2 clarifies the null-field prompt. | The small fixed set mainly measures short answers and format compliance. |
| Software engineering v1 and v2 | V1 has one item per category. V2 retains those six and grows to four each in Java, SQL, debugging, code reasoning, API contracts, and structured output. V2 uses eight exact and sixteen JSON field checks. | Java and SQL mostly ask for output prediction. Debugging asks for small replacements with the cause already given. API tasks ask models to follow specified contracts. |
| Java coding v1 | One median-of-three program with six sandboxed cases. | A single standalone algorithm cannot measure repository edits, test quality, concurrency, or maintainability. |

Prioritize test writing and multi-file bug fixes, then SQL query authoring with
edge cases and stateful API behavior under retries or concurrent writes. Cover
authorization, input handling, and diagnosis from logs after those task types
have objective graders. Reserve independent scenario families for evaluation
when authoring each group.

`EXACT` strips surrounding whitespace but can reject equivalent SQL or
code. `JSON_OBJECT` checks object syntax alone. `JSON_FIELDS` compares named
values with Jackson node equality and permits extra top-level fields. The
objective scorer enables trailing-token rejection and leaves duplicate-key
handling to Jackson defaults. These rules test the requested output format
alongside content. Future scorer versions can add strict schema checks and
behavioral tests where the task calls for them. Keep existing dataset snapshots,
scorers, API contracts, and saved reports readable.

The checked-in live evidence covers basics. The three local v2 reports each
passed 10/10. The three unpaced paired reports recorded 16 cloud passes from
17 returned answers across 30 task slots. The single paced report had four
first-attempt cloud passes and nine terminal passes. These runs informed the
current routing policy, so classify them as development evidence. They provide
no held-out policy evaluation and no live engineering-suite baseline.

## Collection protocol

1. Before collecting data, record the campaign ID, purpose, split, dataset
   version and hash, scorer version, application revision, deployment ID,
   model IDs and revisions when available, provider settings, timeouts,
   sampling settings, retry schedule, and runtime details. Record unknown
   settings explicitly. Exclude credentials from the record.
2. Use paired reports for comparisons. Give both models the same tasks and
   keep the execution mode and schedule fixed within each cohort. Run fresh
   serial repetitions across at least two collection windows. Record any
   warm-up and keep it outside the measured cohort. Keep paced and unpaced
   cohorts distinct.
3. Preserve all planned slots, terminal scores, attempt IDs, and failure
   codes. Report scored correctness as passes divided by scored answers and
   end-to-end yield as passes divided by planned slots. Report first-attempt
   provider success over provider attempts, with operational errors and
   skipped slots counted separately. Report retries and recoveries separately.
4. Report median successful first-attempt latency, sample count, and per-run
   range. Report elapsed benchmark time separately. Show per-run pass and
   failure counts and per-task pass/incorrect/unscored counts across repeats
   so aggregate results expose inconsistent tasks. Treat multiple attempts,
   repeated tasks, and simultaneous model calls as observations within their
   run, with shared conditions.
5. Freeze report IDs, source execution IDs, and task-family assignments.
   Reject repeated report IDs, reused attempt IDs, overlapping collection
   intervals, and repackaged exports of a source run. Inspect provenance for
   rewritten IDs and shared upstream incidents. Same-window repeats may
   describe repeatability, while multiple windows give broader operational
   coverage. Neither establishes statistical independence.

## Policy development and evaluation

Use development reports for threshold selection and routing design. Freeze the
policy, thresholds, candidate pool, baseline, and analysis rules before opening
the evaluation results. Evaluate the frozen policy against a predeclared
local-only baseline on fresh paired reports with disjoint execution IDs and
held-out scenario families. Record model agreement, correctness, provider
failures, latency, and fallback rate. When evaluation results drive policy
changes, move those results into development and collect a new evaluation set.

Use three complete paired reports across at least two windows as a starting
minimum per split and cohort. For a 10-task profile, require 30 planned slots
and at least 24 scored responses per candidate. Require at least 80% first
attempt provider reliability before considering a candidate for latency
comparisons. The sample floor exposes run-to-run variation, the response floor
keeps correctness from relying on a handful of replies, and the reliability
floor limits the influence of retries. Mark smaller or operationally incomplete
cohorts exploratory. These are planning gates and use the existing 10-task
routing thresholds as a reference. They provide neither statistical confidence
nor a measure of general engineering ability. Expand scenario coverage before
interpreting small differences.

## First implementation step

Milestone 10B starts with an offline check for split leakage and reused
executions in saved reports. Run it from the repository root in PowerShell:

```powershell
./scripts/check-benchmark-splits.ps1 -DevelopmentReportPath @('./dev-1.json', './dev-2.json') -EvaluationReportPath './eval-1.json'
```

It rejects repeated report or execution IDs, overlapping report intervals,
and shared prompt text after trimming and normalizing line endings. Older
reports with a terminal execution and no attempt list are supported. The
check assumes a serial collection protocol and reads only exported reports.

For a reviewed campaign, add `-CampaignPath ./campaign.json`. The manifest
contains these fields.

| Field | Contents |
| --- | --- |
| `id` | Campaign identifier. |
| `policyFrozenAt` | Time the policy and analysis plan were frozen. |
| `windows` | Array of objects with `id`, `split` (`development` or `evaluation`), `startedAt`, and `completedAt`. Use ISO 8601 timestamps with an explicit offset. |
| `datasets` | Array of objects with `id`, `version`, `sha256` from the report, and `families`, an object mapping each task ID to a reviewed family slug. |

Assign the same family slug to paraphrases and variants that test the same
underlying scenario, including across dataset versions. For example, use
`api-idempotency-replay` for variants of a replay scenario. Each split must
contain reports from at least two nonoverlapping declared windows. Development
windows end by the policy freeze and evaluation windows start at or after it.
Every report must fit within one window for its split. The checker rejects a
family present in both splits.

The manifest records operator assertions. Review assignments and source
provenance for misclassified scenarios, rewritten identities, and correlated
runs. The checker matches the recorded dataset hash and does not authenticate
it. Sample size, model reliability, and scorer checks belong to cohort review.

## Scenario family inventory

The [family map](../benchmarks/scenario-families.json) covers all five
published dataset versions. Its array entries can be copied into a
campaign manifest's `datasets` field. The hashes come from the benchmark
catalog's parsed snapshots. Repeated tasks keep their family across versions.
Related tasks can also share a family across suites. For example, the basics
null-field task and the engineering incident task both require an explicit
JSON null.

Treat the published families as development inventory. Reserve these new
families for held-out evaluation and assign separate families when designing
any development counterparts.

| Reserved family | Proposed skill and scoring |
| --- | --- |
| `test-regression-fixture` | Write a regression test that fails against a seeded bug and passes against the reference implementation. |
| `multi-file-config-propagation` | Repair a configuration value lost across module boundaries. Run trusted integration tests against the submitted patch in isolation. |
| `sql-window-tiebreaker` | Author a window query and compare result rows across fixtures containing ties and sparse groups. |
| `api-concurrent-capacity` | Implement or diagnose a capacity invariant under concurrent reservations. Grade the resulting state and response contract with a controlled schedule. |

The regression fixture now has an offline Java pilot. Give the prompt to the
candidate and save its Java source as `Main.java`. Run the grader from the
repository root with the same locally available Docker image used by the
Java coding pilot.

```powershell
./scripts/run-java-coding-pilot.ps1 -RegressionTest -SourcePath ./Main.java
```

The grader runs the submitted test in separate restricted containers against
the reference and a boundary mutant. Both reported cases must pass. The
reference expects the test to return normally, and the mutant expects an
`AssertionError`. Compilation errors and other exceptions fail the case.
The JSON fixture includes the prompt and both implementations, so share
only the prompt with candidates during collection. The sandbox limits host
access. The fixture and harness share the candidate's process, so the result
assumes cooperative submissions. This pilot measures one seeded bug and
currently produces a local grader report outside the benchmark API.

[Regression fixture](../benchmarks/held-out/regression-test-v1.json)

The configuration propagation pilot asks for a replacement Java composition
root that connects the supplied configuration parser and retry client. The
trusted harness checks the resulting client behavior for the default, zero,
and a positive override. It also checks that invalid values reach the
caller as exceptions. All five cases must pass.

```powershell
./scripts/run-java-coding-pilot.ps1 -ConfigPropagation -SourcePath ./Main.java
```

This pilot exercises integration across Java files with a single submitted
file. It produces an offline report and assumes cooperative submissions.
[Configuration propagation fixture](../benchmarks/held-out/config-propagation-v1.json)

The SQL pilot grades a PostgreSQL query that returns the top two sales per
team with a deterministic tie-breaker. The two fixtures cover ties at the
cutoff and groups with fewer than two sales. The grader compares ordered
rows and column names. A query may use any equivalent SQL formulation.

```powershell
./scripts/run-sql-window-pilot.ps1 -SourcePath ./answer.sql
./scripts/test-sql-window-pilot.ps1
```

The grader uses a locally available `postgres:17-alpine` image and creates
a disposable container for each fixture. It runs PostgreSQL without network
access and evaluates the query as a restricted role in a read-only session.
The container caps resources and output. Both cases must pass. Share only
the prompt with candidates during collection.
[SQL fixture](../benchmarks/held-out/sql-window-v1.json)

The concurrent API pilot grades the reservation method behind a capacity
endpoint. A barrier forces two callers to observe the same initial capacity.
The grader checks response statuses and remaining slots with an exhausted
store, one slot under contention, and two slots where a failed compare and
set must be retried. All three cases must pass.

```powershell
./scripts/run-java-coding-pilot.ps1 -ConcurrentCapacity -SourcePath ./Main.java
```

The pilot uses the restricted Java runner and an offline report. It measures
one lock-free contract under a controlled schedule and assumes cooperative
submissions. Share only the prompt during collection.
[Concurrent capacity fixture](../benchmarks/held-out/concurrent-capacity-v1.json)

Freeze family assignments and graders before collecting held-out results.

## Export held-out execution evidence

Record the request start time and save the matching execution object from
the history API. Send the task prompt verbatim. For a successful execution,
save the response exactly as UTF-8 without a BOM or an added newline, then
run the task's offline grader and save its JSON result.

```powershell
./scripts/export-held-out-evidence.ps1 -TaskPath ./benchmarks/held-out/sql-window-v1.json -ExecutionPath ./execution.json -StartedAt $requestStartedAt -GradePath ./grade.json |
    Set-Content -Encoding utf8NoBOM ./evaluation-report.json
```

For a failed execution, omit `-GradePath` and export the failure as an
unscored result. The export checks the prompt, grader case list, task hash,
and response hash. It keeps the original request ID, uses the history
timestamp as the end of the observed interval, and carries the grader
verdict without copying the model response into the campaign report.

Add a campaign dataset entry using the exported dataset's ID, version, and
`datasetSha256`, with the task ID mapped to its fixture family. Supply the
exports to the campaign checker as evaluation reports. One export covers
one model execution. Plan model pairings, check cohort thresholds, and
record model settings separately. The checker verifies split provenance
rules, while the export relies on an operator-recorded start time and
locally generated grader results.

For the v0.2.0 review, distinguish fixture verification from a held-out
model campaign. A release candidate still needs collected evidence under
the frozen protocol and a review of release notes and version metadata.

On 6 November 2026, a single local workflow smoke test used the installed
Ollama model `qwen2.5-coder:3b-instruct-q4_K_M` for the SQL task. ModelHub
saved the successful generation after 32,753 ms and the exporter preserved
its request ID. The response included prose and a Markdown fence around its
query. Both PostgreSQL cases returned `QUERY_ERROR` when graded on the exact
response, and the export recorded `FAIL`. This exploratory run exercised
the live collection path and falls outside the frozen evaluation campaign.

## First local held-out campaign

Compare the installed Qwen2.5 Coder 3B and 7B Q4_K_M models on all four
held-out tasks. Use the 3B model as the fixed baseline. Plan three paired
repetitions across two nonoverlapping windows, with two repetitions in the
first window and one in the second. Each repetition contains one response per
task from each model, for 24 model calls total. Run calls serially, alternate
which model goes first in consecutive pairs, and keep task order fixed.

Before collection, freeze the repository commit, task and grader hashes,
model digests, runtime and grader image IDs, timeout settings, and window
boundaries. Record the configured sampling settings or provider defaults.
Grade the saved response byte for byte. Preserve failed calls and score
format violations under the task's original contract. Record first-call
latency separately from any warm-up, and retain the planned slot when a
tooling error prevents grading.

With a loopback ModelHub instance configured for the requested Ollama model,
collect one slot at a time:

```powershell
./scripts/collect-held-out-run.ps1 -Task sql-window -Model qwen2.5-coder:3b-instruct-q4_K_M
```

The collector makes one generation request and verifies it against the most
recent 100 history entries. It saves artifacts in a new directory under
`target`. If grading fails to produce an export, the directory retains the
saved execution and start timestamp for manual recovery. Use a dedicated
instance or avoid concurrent requests during collection.

Record pass, fail, and unscored counts per model and per task, along with
paired outcome differences and successful generation latency. The campaign
compares explicit local models. Its findings apply to those four tasks and
cannot validate the separate local-versus-cloud routing policy. The earlier
SQL smoke test is excluded from the planned 24 calls.

The [local campaign manifest](../benchmarks/held-out/local-campaign-v1.json)
records the selected versions and scheduled windows. The SHA-256 values for
scripts and held-out tasks use UTF-8 text with LF line endings. Recheck the
installed digests and versions before collection. If a window becomes
unavailable, revise the schedule before starting that window and retain the
original manifest in Git.

Use the saved basics v2 local baseline and paired comparison directories as
development inputs to the split checker. Their separate historical windows
document which data preceded this evaluation. Keep their metrics in separate
cohorts. Start the first task pair with the 3B model and alternate the first
model for each subsequent task pair. Within the same model and task, retain
each original request ID and its repetition assignment.
