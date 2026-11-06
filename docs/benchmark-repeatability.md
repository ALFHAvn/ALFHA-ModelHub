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
executions in saved reports. Dataset family review, run-window provenance,
coverage expansion, and scoring improvements remain separate steps.
