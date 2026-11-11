# Milestone 9A · Evidence-based routing

## Current evidence

ModelHub's registry exposes configured model IDs and providers. Generation
requires a model ID and resolves it through that registry. Ollama and OpenRouter
share the generation interface. OpenRouter currently allows fixed `:free` IDs
and `openrouter/free`. Benchmarks already reject the variable free router.

The catalog contains `basics` v1/v2, `software-engineering` v1/v2, and
`java-coding` v1. The first two suites use the objective scorer. Java coding
uses Docker case results, with evaluator infrastructure errors distinguished
from provider errors. PostgreSQL stores complete JSONB reports, indexed by
report UUID. Reports record the dataset snapshot and hash, scorer version,
model and provider configuration, task scores, attempt records, and latency.
The repository supports exact ID lookup. There is no queryable category index,
verified live-versus-stub provenance marker, or provider revision fingerprint.

The checked-in reports demonstrate the need for separate metrics. In three
unpaced `basics` v2 comparisons, Ollama passed 30/30 and LFM2.5 free passed
16/17 scored responses. LFM2.5 also hit 13 rate limits in 30 attempts. Its
successful-response mean was about 0.94 s compared with 14.53 s for Ollama.
The paced run recovered five tasks by retry and is a different measurement
condition. Milestone 8 verified software engineering and coding through stubs
and trusted fixtures, which establish pipeline correctness. [Unpaced evidence](../benchmarks/comparisons/2026-11-05-qwen3-vs-lfm25-v2/README.md) · [Paced evidence](../benchmarks/comparisons/2026-11-06-qwen3-vs-lfm25-v2-paced/README.md) · [Evaluation setup](benchmarks.md)

## Proposed decision policy

### Profiles and eligible evidence

A caller supplies a task profile explicitly. A versioned profile maps a name
to a dataset ID, version, hash, scorer, and fixed task IDs. Start with
`basic` mapped to all ten `basics` v2 tasks. A later profile can map Java,
SQL, debugging, reasoning, API design, or structured output to the corresponding
four task IDs in `software-engineering` v2. Keep code-writing separate from
code reasoning and compare its case results only against the same coding
fixtures, scorer and execution policy. A profile describes evidence coverage.
It does not establish that an arbitrary user prompt resembles its benchmark.

The operator supplies a bounded list of up to 20 trusted, live report UUIDs
per profile. The app resolves them by primary key from PostgreSQL. A checked-in
JSON artifact or a request-supplied report is insufficient provenance.
Before selecting reports, the operator checks that they used the current
models and deployment configuration. Since historical reports lack model
digests and provenance, configuration changes require clearing the selection
and advancing an operator-controlled evidence cutoff before gathering new data.

Deduplicate report IDs and verify dataset ID, version, hash, scorer, model
IDs, provider, and recorded request timeout. Compare the decoded dataset
snapshot with the registered dataset and check the stored hash separately.
This accounts for JSONB reordering object keys during storage.
Use only complete paired reports
with one terminal row per profile task per candidate. For this policy version,
use the same unpaced, single-attempt condition with no skipped tasks. Keep
scheduled reports separate until a policy explicitly supports them. Reject
ambiguous rows, duplicate execution IDs across reports, incomplete reports,
and future completion times. Filter out reports completed before the
configuration cutoff or more than 14 days before the decision clock.

Paced evidence needs a separate policy. Compare reports with the same
schedule, and measure first-attempt availability and correctness separately
from retry recovery. A retry response contributes to terminal benchmark
metrics only. A skipped task records missing coverage without counting as a
provider attempt. Keep application errors outside the provider reliability
denominator. First-attempt measurements still reflect the paced workload and
cannot be pooled with the unpaced profile. The current routing policy keeps
excluding scheduled reports. [Offline paced diagnostics](benchmarks.md)

The offline `paced-cohort-v1` checker tests the cloud first-attempt gates
on matched `basics` v2 reports. This is a diagnostic for planning evidence
collection. Runtime routing eligibility continues to use the unpaced policy.

Routing policy v2 re-scores successful responses against the registered
task using the benchmark's objective scorer. A disagreement with a stored
PASS or FAIL excludes the paired report as `SCORE_MISMATCH`. The check leaves
the stored report intact. Report provenance still relies on operator curation.

Set `--modelhub.benchmark.deployment-id=local-qwen-openrouter-r1` on the
Spring Boot process that creates and consumes reports, or set
`MODELHUB_BENCHMARK_DEPLOYMENT_ID` in that process's environment. Use a
non-secret ID of 1–64 letters, digits, dots, underscores or hyphens, starting
with a letter or digit. New reports record it as `deploymentId` in the
existing PostgreSQL JSON snapshot. When configured, routing excludes untagged
reports as `DEPLOYMENT_ID_MISSING` and different IDs as
`DEPLOYMENT_ID_MISMATCH`. History and report retrieval still read older
reports. Leaving the ID unset preserves existing routing eligibility.

Change the ID and advance the cutoff when the provider endpoint, model
revision, or serving configuration changes. The ID records the operator's
deployment boundary. It cannot establish model weights, distinguish a live
provider from a stub, or authenticate report contents.

### Metrics and ranking

For each model, use the same report set and profile task IDs. Compute the
metrics from individual results rather than whole-dataset summaries.

| Signal | Definition |
| --- | --- |
| Correctness | PASS / (PASS + FAIL) for scored answers. FAIL is a returned answer that failed evaluation. |
| Provider reliability | Scored responses / valid attempted slots. HTTP 429, timeouts, connection failures, rejected upstream requests, and invalid upstream responses count as provider failures. |
| Latency | Median generation duration over scored responses. Keep failure durations, benchmark wall time, and coding evaluation time separate. |
| Sample size | Number of distinct reports, distinct task IDs, attempted slots, and scored answers. Expose all four. |

For this objective-only profile, accept a scored row only with a successful
generation and PASS or FAIL. Accept a provider-failure row only with a failed
generation, NOT_SCORED, and an expected provider error code. Exclude the pair
for a report with a history write error, invalid configuration, interruption,
unexpected application failure, or evaluator infrastructure failure. This
keeps operational faults visible as exclusions and out of both the model
correctness and provider failure denominators.

Use these initial, versioned policy defaults:

1. Require at least three distinct comparable reports covering all profile
   tasks. Require at least 80% provider reliability and at least 80% of the
   minimum required slots to have scored answers. For `basic`, that means at
   least 30 attempts and 24 scored responses. Repetition adds observations
   of the same tasks, so it does not expand task coverage.
2. Find the highest correctness among eligible candidates. Keep candidates
   within five percentage points of that value. Compare exact count ratios
   before rounding display values.
3. Among that group, select the lowest median generation latency. Break ties
   with higher correctness, then higher provider reliability, then more scored
   answers, then a stable ordering by provider and model ID. Use the midpoint
   of the two middle values for an even-sized latency sample.

These thresholds are engineering defaults to test and revise using evidence.
They are not confidence bounds or a general quality ranking. A gate plus a
correctness band makes the latency trade-off visible, at the cost of abrupt
changes near a threshold. A fixed sample and median reduce sensitivity to
single slow responses, while still missing tail latency and correlated outages.

### Cost and failure boundaries

Filter against the active registry before considering evidence. Local models
are allowed. Any later cloud dispatch requires explicit operator enablement,
the official OpenRouter endpoint, an exact configured fixed `:free` ID, and a fresh official catalog check
showing zero prompt and completion pricing with no nonzero per-request charge.
Reject variable routers, unknown or missing pricing, unavailable catalog,
nonzero prices, and paid IDs. Use the verified ID as the single generation
target. A rate limit or execution failure is returned to the caller with the
route explanation. The policy does not retry against a different model.
Catalog verification is a safeguard, and remote service behavior and price
changes remain outside the application's control.

When evidence is missing, stale, untrusted, invalid, or unavailable, return
`FALLBACK_LOCAL` with the configured Ollama model if one is eligible. That
decision is an explicit default and carries the evidence failure reason.
An unknown profile is an invalid request. When valid evidence exists but no
candidate passes the gates, return the same local fallback with
`NO_ELIGIBLE_EVIDENCE`. If a local fallback is unavailable, return `NO_ROUTE`.
No database failure or stale result may grant cloud eligibility.

## Decision explanation

A preview returns the profile and policy version, as-of timestamp, dataset hash,
outcome (`EVIDENCE_SELECTED`, `FALLBACK_LOCAL`, or `NO_ROUTE`), selected model
if any, and a structured explanation for each configured candidate. Include
the used report UUIDs, exclusions and reason codes, task coverage, raw counts,
computed ratios and latency, gate thresholds, and the final ranking step.
Example reasons include `STALE_EVIDENCE`, `INSUFFICIENT_REPORTS`,
`LOW_PROVIDER_RELIABILITY`, `CONFIGURATION_MISMATCH`, and
`CLOUD_NOT_VERIFIED`. Return counts and IDs without copying prompts, raw
responses, credentials, or provider error bodies. Fixed policy, clock,
configuration, and report snapshots must yield the same decision regardless
of report insertion or candidate ordering.

## Incremental implementation and acceptance

**9A** records the design. Existing providers, benchmark execution, scoring,
Docker restrictions, and API contracts remain as implemented.

**9B** adds a pure decision function, a small adapter for exact PostgreSQL
report lookup, and an opt-in read-only routing preview for the `basic` profile.
Use the operator's allowlisted report UUIDs and cutoff with the existing
repository. Do not add a schema migration, report upload, catalog polling,
prompt classifier, or routed generation call. Preview can calculate metrics
for configured fixed-free cloud candidates, but returns local fallback or
`NO_ROUTE` where current cloud verification is unavailable. Keep automatic
selection local-only in this first slice. The preview must distinguish a
local default from a model selected on evidence. With today's one-local-model
registry, the practical value is explaining readiness and blockers for cloud
routing rather than switching providers.

Acceptance checks for 9B:

- Fixed report fixtures with PASS, FAIL, and 429 prove that incorrect answers
  affect correctness and rate limits affect provider reliability. Fixture
  reports also verify the median, exact band boundary, and each tie-break.
- Duplicate IDs, mixed hashes/scorers, paced reports, stale/future dates,
  model or configuration drift, too few paired reports, and missing tasks
  cannot qualify. Missing or unreadable evidence returns the documented
  fallback reason. Tests inject a fixed clock and permute candidate/report order.
- Paid or unknown model IDs, `openrouter/free`, and unverified cloud candidates
  cannot be selected. Preview makes no provider calls. Normal generation,
  compare, benchmark report retrieval, and legacy report deserialization pass
  regression checks. Use stubs and disposable PostgreSQL in automated tests.

**After 9B**, verify current free pricing before any cloud dispatch and add
an explicit routed-generation endpoint that returns the explanation with the
normal generation result. Collect controlled paired live reports only within
the approved quota. Add category profiles when their evidence qualifies and
evaluate paced reports under a separate versioned policy. Prefer provenance
and config/revision tracking before automatic report discovery.

## Remaining risks

The checked-in comparisons are useful examples, but the old unpaced cloud
sample misses the reliability gate and the single paced run misses the sample
gate. PostgreSQL report availability on a given deployment must be checked
separately. Trusted curation is vulnerable to selection bias. The registry
does not pin remote model weights, and its recorded timeouts do not capture
all serving changes. A low sample median can hide bad tail latency. Even a
qualified route may fail after preview, and small fixed datasets can be
overfit. Keep route explanations auditable and retain explicit model choice.

## 9A verification

`mvn -Dmaven.repo.local=.m2/repository -B -ntp verify` passed all 35 existing
tests, including two disposable PostgreSQL integration tests. The run used stub
model providers. The optional real Docker coding integration path was outside
this documentation-only checkpoint.

## 9B preview

The preview is opt-in. Start the host application with the usual database and
provider configuration and add these flags. Substitute UUIDs from trusted
live reports in the current PostgreSQL database. Choose the cutoff to match
the current model and deployment configuration.

```text
--modelhub.routing.preview.enabled=true
--modelhub.routing.preview.cutoff=2026-11-06T00:00:00Z
--modelhub.routing.preview.report-ids=<uuid1>,<uuid2>,<uuid3>
```

Request `GET /api/routing/preview?profile=basic`. The API reads only the
configured UUIDs and returns a recommendation with counts and reasons.
Cloud entries are diagnostic and carry `CLOUD_NOT_VERIFIED`. A missing UUID
is an exclusion. A database read or decoding failure causes a local fallback
or `NO_ROUTE`. Omitting the cutoff prevents reports from qualifying.
Preview is read-only and uses the same trusted-local deployment assumptions
as the benchmark API.

Verification ran with `mvn -Dmaven.repo.local=.m2/repository -B -ntp verify`.
All 39 tests passed, including two disposable PostgreSQL integration tests.
The preview integration test verifies approved report lookup, local fallback,
missing and unreadable report handling, and zero provider calls.

## Opt-in cloud pricing verification

Add `--modelhub.routing.preview.verify-cloud=true` to enable pricing
verification. When a fixed OpenRouter `:free` model passes the benchmark
evidence gates, the preview checks its exact ID in the
[public OpenRouter model catalog](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties).
The configured generation endpoint must be the official OpenRouter endpoint.
The catalog request sends no credentials or prompt, follows no redirects,
and limits the request to five seconds and four MiB of catalog data.

The matching entry must have explicit zero prompt and completion prices.
Any other listed price must also be a valid zero price string. Missing,
ambiguous, malformed, nonzero, or unavailable pricing excludes the cloud
candidate and adds a reason to the preview. The response includes
`pricingObservedAt` when a check was made. The policy accepts a check for
at most 60 seconds and each eligible preview checks the catalog afresh.

This check applies to recommendations. Pricing, availability, and quota can
change after the check. Cloud dispatch uses its own fresh check.

## Local routed generation

Enable both `--modelhub.routing.preview.enabled=true` and
`--modelhub.routing.generation.enabled=true`, then send
`POST /api/routing/generate` with a JSON body:

```json
{"profile":"basic","prompt":"Explain how a database index works."}
```

The endpoint reads the configured reports and makes a new decision for each
request. It returns `decision` and the standard `generation` result. A
provider failure keeps the decision in the response and uses the same HTTP
status as explicit generation. If the policy returns `NO_ROUTE`, the
endpoint returns HTTP 503 with a null generation result. Each routed request
can dispatch at most once and uses normal execution history when configured.

Dispatch defaults to local-only. Fixed-free cloud candidates retain
their evidence metrics and show `CLOUD_DISPATCH_DISABLED`, even when the
separate preview can verify their pricing.

### Opt-in cloud dispatch

Set `--modelhub.routing.generation.allow-cloud=true` to allow a configured
OpenRouter model to compete for a routed request. Each eligible dispatch
checks the official catalog again and requires an exact fixed `:free` model
with verified zero pricing. The preview setting is independent. Failed
pricing verification excludes the cloud candidate before sending any prompt.

If cloud wins, ModelHub sends one text-only request for the selected model
to the official endpoint. It disables OpenRouter provider fallbacks and sets
the documented prompt, completion, request, and image price ceilings to zero.
Provider errors are returned with the decision. Existing explicit generation
and benchmark requests retain their original payloads.
[OpenRouter provider routing documentation](https://openrouter.ai/docs/guides/routing/provider-selection#max-price)

Tests use fixture catalog responses and stub providers. Live enforcement of
zero-valued limits and account-level billing behavior remain unverified.

## Routing Preview in the web interface

For the Windows launcher and Docker Compose, add these settings to the root
`.env` and restart ModelHub. Use a UTC cutoff that matches the current
deployment, and replace the example IDs with up to 20 approved Basics v2
report IDs from the current database.

```dotenv
MODELHUB_ROUTING_PREVIEW_ENABLED=true
MODELHUB_ROUTING_PREVIEW_CUTOFF=2026-11-06T00:00:00Z
MODELHUB_ROUTING_PREVIEW_REPORT_IDS=<uuid1>,<uuid2>,<uuid3>
```

Open **Routing Preview** and select **Preview route**. It uses
`GET /api/routing/preview?profile=basic` to display the recommendation,
local fallback, or no-route outcome, together with candidate metrics,
thresholds, evidence cutoff, and links to qualifying saved reports.
Exclusions explain why other reports did not qualify. Empty evidence
shows a fallback or no route according to the existing policy.

**Preview route** reads on demand and sends no generation requests. The
Compose settings above enable the preview. Host deployments can use the
[existing preview flags](#9b-preview).

## Local dispatch in the web interface

To enable the separate **Generate locally** action, set
`MODELHUB_ROUTING_GENERATION_ENABLED=true` alongside
`MODELHUB_ROUTING_PREVIEW_ENABLED=true` in the root `.env` and restart
ModelHub. Enter a prompt in the local routing form and submit it explicitly.

The browser calls `POST /api/routing/generate-local` with the same
`profile` and `prompt` fields as the existing routed generation endpoint.
It recalculates the route with local candidates only, even when the
server allows cloud dispatch on the existing endpoint. The browser shows
the actual dispatch decision and retains it when the local model returns
an error. A no-route decision makes zero model calls. A recommendation
from **Preview route** can differ from the actual local dispatch because
the evidence is checked again and the local action excludes cloud models.

## Two-model Ollama routing

The default routing roster is the primary Ollama model and, if enabled,
the configured OpenRouter model. To use two local routing candidates, run
`ollama list` and choose two distinct, already installed model IDs. In the
root `.env`, use these settings and substitute the actual secondary ID.

```dotenv
OLLAMA_MODEL=qwen3:4b
OLLAMA_SECONDARY_MODEL=<installed-secondary-id>
OPENROUTER_ENABLED=false
MODELHUB_ROUTING_LOCAL_PAIR_ENABLED=true
MODELHUB_ROUTING_PREVIEW_ENABLED=true
MODELHUB_ROUTING_GENERATION_ENABLED=true
MODELHUB_ROUTING_PREVIEW_REPORT_IDS=
```

Before collecting evidence, use PowerShell to print a cutoff and add the
result to `.env`.

```powershell
"MODELHUB_ROUTING_PREVIEW_CUTOFF=$([DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss'Z'"))"
```

Run **Start-ModelHub.cmd** to apply the settings. In **Routing Preview**,
confirm that both candidate IDs appear as
Ollama models and that the primary remains the fallback before evidence is
available. The local-pair setting affects only the routing roster. Explicit
model selection continues to list all configured models. If OpenRouter is
enabled separately, it stays outside the local-pair roster.

Submit three sequential requests to `POST /api/benchmarks` with the body
below, replacing the placeholder with the same exact ID. Leave the schedule
omitted and wait for each report before starting the next. The benchmark
runner can submit the two models concurrently for each task.

```json
{"datasetId":"basics","datasetVersion":"2","models":["qwen3:4b","<installed-secondary-id>"]}
```

Check that each saved report has 20 results covering the same 10 tasks for
both models, one attempt per result, and unique execution IDs across all
three reports. Keep the runtime, model digests, timeout, and deployment
stable. The policy still verifies roster compatibility, the dataset and
scorer, cutoff, 14-day freshness, and saved answers. Each candidate still
needs 30 attempts, at least 24 scored responses, and 80% provider reliability.

Put the three saved report IDs in `MODELHUB_ROUTING_PREVIEW_REPORT_IDS` as a
comma-separated list and restart ModelHub. Choose **Preview route** and check
for `EVIDENCE_SELECTED` and the three qualifying report IDs. Then use
**Generate locally** to verify the decision on a real prompt. Routed Ollama
generation checks the configured server's inventory before dispatch and
rejects missing, ambiguous, or remote entries. It does not pull models.

## Local-only evidence verification before local-pair opt-in

The `basic-preview-v2` policy requires exactly two eligible routing candidates
and a complete paired report for that roster. Before the local-pair option,
with `OPENROUTER_ENABLED=false` the registry offered only its primary Ollama
model to routing.
Three complete single-model reports would each fail the compatibility gate
with `CONFIGURATION_MISMATCH`, which still applies to single-model reports
with local-pair routing enabled.

On 11 November 2026 (Vietnam time), the running Compose app exposed
`basics` v2 with all 10 tasks and `qwen3:4b` as the only configured model.
Setting `MODELHUB_ROUTING_PREVIEW_CUTOFF=2026-11-10T18:08:48Z` changed
the live preview from `CUTOFF_REQUIRED` to `NO_EVIDENCE`. It returned
`FALLBACK_LOCAL` and selected `qwen3:4b`. PostgreSQL retained zero reports
and four existing execution records. The routing, catalog, and benchmark
service tests passed (21 tests). This verification made no model calls.

For a separate local baseline, keep the installed model digest, Ollama
version, timeout, and deployment stable. Submit three sequential
`POST /api/benchmarks` requests with
`{"datasetId":"basics","datasetVersion":"2","models":["qwen3:4b"]}`.
Each report should contain one attempt for each of the 10 task IDs, and all
30 execution IDs should be unique and match the saved execution history.
Verify the saved dataset snapshot, hash, and `objective-v1` scores. Keep
the genuine reports in PostgreSQL. This measures repeatability for one
model. The local-pair roster requires the paired collection above.

## Live two-model verification on 11 November 2026

The Compose deployment at `ad55458` used Ollama 0.40.16, a 120-second
timeout, and a UTC evidence cutoff of `2026-11-11T16:21:28Z`. Three sequential,
unpaced Basics v2 reports each covered both installed models and all 10 tasks.
The model digests and Ollama version matched before and after collection.
All 60 execution IDs were unique. The saved rows matched PostgreSQL history
for the prompt, model, response, status, error, and duration.

| Model | Correct per report | Correct overall | Provider reliability | Median scored latency |
| --- | --- | --- | --- | --- |
| `qwen3:4b` | 10/10, 10/10, 10/10 | 30/30 | 30/30 | 15,051.5 ms |
| `qwen2.5-coder:3b-instruct-q4_K_M` | 5/10, 5/10, 5/10 | 15/30 | 30/30 | 8,051.5 ms |

Routing Preview accepted all three reports with zero exclusions and returned
`EVIDENCE_SELECTED` for `qwen3:4b` with
`CORRECTNESS_BAND_THEN_LATENCY`. The 3B model fell outside the five-point
correctness band. A single local dispatch returned `37` for the prompt
“What is 14 + 23? Reply with only the number.” The selected model and decision
reason matched Preview. The API recorded 12,065 ms, and the response and
execution metadata matched the saved history entry.

PostgreSQL holds the three reports, and the active local configuration
references their IDs. The checked-in archive contains the three original
report responses, the preview and dispatch, and their verification manifest.
The local collection directory also holds the broader execution history
exports used to check those reports. The evaluation repeats the same 10
tasks on one host. The benchmark can run both models concurrently for a task,
so its latencies describe this paired workload.
[Live evidence manifest](../benchmarks/evidence/local-routing-2026-11-11/verified.json)

After collection and dispatch, Maven verification passed all 63 backend
tests, including the PostgreSQL and restricted grading integration tests.
The frontend suite passed all 21 tests.
