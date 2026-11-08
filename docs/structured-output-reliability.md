# Milestone 12A structured-output reliability

ModelHub now offers opt-in output contracts for local Ollama generation and
Java grading. JSON validation preserves the original response and records
format failures separately from provider errors. The proposal-and-review
route can require a JSON object with a `defectIds` array. The Java routes
offer separately versioned format checks before the existing restricted
Docker grader. Existing routes and historical benchmark verdicts retain
their original behavior. [JSON API](../README.md#opt-in-json-generation) ·
[Workflow API](../README.md#opt-in-defect-id-output) ·
[Java grading](benchmarks.md#opt-in-prose-envelope-grading)

| Check | Observation |
| --- | --- |
| Local schema compatibility | One short schema probe passed on Qwen3 4B and Qwen2.5 Coder 3B and 7B. |
| Paired proposal-and-review run | The structured arm returned valid JSON on 12/12 steps and matched the answer key on 2/12. The standard arm returned valid JSON on 0/12. All 24 local model calls succeeded. |
| Raw Java prompt comparison | All four responses contained Markdown fences. The explicit instruction removed surrounding prose in the two observed responses. None passed strict grading. |
| Offline Java wrapper detector | V2 caught 10/10 synthetic wrappers, compared with 5/10 for v1. Neither flagged any of the six valid Java controls, which passed all 36 restricted Docker cases. |

The proposal-and-review run covers six tasks with one attempt per arm and one
model pairing. The raw Java experiment covers one task with one attempt per
prompt and model. The wrapper tests measure coverage on hand-written examples.
These observations support reporting format compliance separately from answer
correctness. They leave model-level accuracy and reliability rates unresolved.
[Paired JSON evaluation](proposal-review-evaluation.md#first-paired-run-on-8-november-2026) ·
[Raw Java comparison](benchmarks.md#raw-java-prompt-comparison-v1) ·
[Wrapper coverage](benchmarks.md#detector-v2)

New Java benchmark summaries expose how many submissions reached the format
check, how many it rejected, which sandbox verdicts were recorded, and which
executions failed before grading. Keep summaries grouped by dataset and scoring
version. The benchmark reports preserve the original model text and per-case
outcomes alongside these metrics. [Report fields](benchmarks.md#coding-report-breakdown)
