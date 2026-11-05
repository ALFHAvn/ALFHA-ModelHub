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

Routing policy v2 re-scores successful responses against the registered
task using the benchmark's objective scorer. A disagreement with a stored
PASS or FAIL excludes the paired report as `SCORE_MISMATCH`. The check leaves
the stored report intact. Report provenance still relies on operator curation.

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
