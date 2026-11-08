# Proposal and review evaluation plan

## Offline scoring pilot

The [v1 fixture set](../benchmarks/proposal-review/defect-identification-v1.json)
contains four examples, with one defective example and one clean control in
each language. The scorer reads a saved workflow response for one fixture.
It can also print the exact task prompt for a future collection run.

```powershell
./scripts/score-proposal-review.ps1 -TaskId java-positive-sum
./scripts/score-proposal-review.ps1 -TaskId java-positive-sum -WorkflowPath ./target/saved-workflow.json
./scripts/test-proposal-review-scoring.ps1
```

Use PowerShell 7.5 or later so JSON strings that resemble timestamps retain
their original values. A successful step passes when its raw JSON
contains exactly the expected set of finding IDs. Order and JSON whitespace
are immaterial. Unknown or duplicate IDs, duplicate object fields, extra
fields, Markdown fences, and trailing text are format errors. A valid set
with incorrect membership is a wrong answer. Failed and skipped steps
are unavailable for correctness scoring, with the original error code retained.

The report records the fixture version, hashes for the fixture, prompt, and
saved workflow file, both execution IDs where available, per-step latency and
findings, and the paired exact-match outcome. The four examples are pilot
fixtures for checking the scoring contract and report shape.

## Local collection

Start ModelHub with PostgreSQL and two distinct installed Ollama models. Set
`OLLAMA_MODEL` and `OLLAMA_SECONDARY_MODEL` as described in the
[workflow setup](../README.md#two-model-proposal-and-review). Use a quiet
instance because the collector checks the 100 most recent history records.

```powershell
./scripts/collect-proposal-review-pilot.ps1 `
  -ProposerModel qwen2.5-coder:3b-instruct-q4_K_M `
  -ReviewerModel qwen2.5-coder:7b-instruct-q4_K_M
./scripts/test-proposal-review-collector.ps1
```

The collector connects to the loopback API with cloud use disabled. Each run
submits the four fixtures in order, with at most eight local model calls. A
lock prevents overlapping runs from the same checkout. The collector saves
the request, raw response, and elapsed workflow time under
`target/proposal-review-pilot-*`. Before writing each score it verifies the
execution IDs, model IDs, responses, statuses, latencies, errors, and prompts
against history within the collection window. It also checks the reviewer
context against the original prompt and exact proposal text. Skipped steps
have no history row and remain unavailable for scoring.

`summary.json` appears after all four fixtures are verified. If verification
stops early, any captured artifacts remain available for inspection. Each
invocation starts a fresh batch with no automatic retries or resume. The
collector rejects reused execution IDs within a batch. Combining batches
requires checking execution IDs across batches and recording model build and
runtime settings separately.

## First local pilot on 8 November 2026

The Qwen2.5 Coder 3B Q4_K_M proposer and 7B Q4_K_M reviewer completed one
workflow per fixture. The installed model digests matched before and after
the run. All eight executions had unique IDs, matched PostgreSQL history,
and reproduced their original scores on offline replay.

| Metric | 3B proposal | 7B review |
| --- | ---: | ---: |
| Successful provider calls | 4/4 | 4/4 |
| Passing raw responses | 0/4 | 0/4 |
| Raw JSON format errors | 4 | 4 |
| Median generation latency | 5,825 ms | 9,388 ms |

Median elapsed time for the complete workflow request was 15,550 ms. Every
response was wrapped in a Markdown JSON fence. Strict grading therefore
recorded four ties at failure. The pilot gives no evidence about whether
review improved defect detection within the enclosed answers. A separate
offline analysis can score a single unambiguous JSON block from each saved
response while preserving the raw scores and making zero new model calls.

## Paired evaluation protocol

Start with a local, paired evaluation of defect identification. Freeze a
versioned set of small Java and SQL examples with known defects and some clean
controls. Assign stable IDs to candidate findings for each example and freeze
the correct ID sets before collecting responses. The task asks for the IDs as a
JSON object. The reviewer feedback is scored in the same format.

For each task and repetition, use the proposal from a workflow run as the
single-call baseline. This is the same generation that would run alone with
the selected proposer and original prompt. Score the review against the same
answer key and record whether it corrected a missed defect, introduced a
false alarm, or removed a correct finding. This pairing holds the initial
proposal constant and avoids another generation call for the baseline. Add a
separate two-call control with the proposer reviewing its own saved output
under the same review prompt if the goal is to measure model diversity apart
from the effect of a second call.

1. Freeze prompts, scoring, model builds and settings, repetition count, call
   budget, and collection schedule. Use separate scenario families when tuning
   the review prompt. Start with existing local models and no cloud requests.
2. Score exact-set accuracy as the primary metric. Also report true and false
   positives, missed defects, and format errors. Treat malformed output as a
   failed submission under the declared raw JSON contract. Report skipped steps
   and provider or storage failures separately from answer errors.
3. Report paired improvements, regressions, ties, and unavailable pairs. Show
   coverage over all planned pairs alongside correctness for completed pairs.
   Record proposal latency, review latency, and elapsed workflow time. Count
   repeated generations of the same example as repeats within that example.

The first experiment measures defect detection and review under a fixed output
contract. Measuring whether feedback leads to better code would require a
separately designed revision step and grading in the existing restricted
sandboxes. The first local pilot covers four tasks with one attempt each.
