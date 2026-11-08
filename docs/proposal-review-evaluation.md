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

Use PowerShell 7.2 or later. A successful step passes when its raw JSON
contains exactly the expected set of finding IDs. Order and JSON whitespace
are immaterial. Unknown or duplicate IDs, duplicate object fields, extra
fields, Markdown fences, and trailing text are format errors. A valid set
with incorrect membership is a wrong answer. Failed and skipped steps
are unavailable for correctness scoring, with the original error code retained.

The report records the fixture version, hashes for the fixture, prompt, and
saved workflow file, both execution IDs where available, per-step latency and
findings, and the paired exact-match outcome. These hashes identify inputs.
A later collector must verify that the saved workflow used the emitted prompt,
bind it to history, and enforce distinct executions across all runs before
the scores can count as measured evidence. The four examples are pilot
fixtures for checking the scoring contract and report shape.

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
sandboxes. No evaluation results have been collected for this workflow.
