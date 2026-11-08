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
recorded four ties at failure.

## Offline JSON fence diagnostic

The replay verified the four raw workflow hashes, eight unique execution IDs,
saved history, and strict scores. It accepted a single lowercase `json`
Markdown block with no surrounding prose, then passed the enclosed content
to the same exact-set scorer. It made zero model calls and wrote derived
responses and `diagnostic.json` to a new ignored directory under `target`.

```powershell
./scripts/replay-proposal-review-json-fences.ps1 -PilotDirectory ./target/proposal-review-pilot-c4510968cac145ba99820d7ac76aa0a4
```

| Fixture | Proposal after extraction | Review after extraction | Change in findings |
| --- | --- | --- | --- |
| Java positive sum | Pass | Pass | Both found A and B |
| Java prefix clean control | Wrong answer | Wrong answer | Review added false alarm B |
| SQL access precedence | Wrong answer | Wrong answer | Both found A and falsely reported C |
| SQL empty category clean control | Wrong answer | Wrong answer | Review added false alarm B |

Both roles passed one of four examples after extraction. The reviewer found
the same three actual defects and increased the total false alarms from four
to six. The original zero-pass scores remain the strict campaign result.
The diagnostic covers one model pairing and one attempt per fixture. A
follow-up should include more independent examples and a same-model review
control before attributing any effect to the second model.

## Follow-up fixtures and same-model control

The [v2 fixture set](../benchmarks/proposal-review/defect-identification-v2.json)
contains six new tasks. Java covers decimal equality, caller-owned state, and
map updates. SQL covers NULL handling in an anti-join, subquery correlation,
and UTC time boundaries. Each language has two defective examples and one
clean control. The v1 collector and scorer remain the defaults.

```powershell
./scripts/collect-proposal-review-pilot.ps1 -DatasetVersion 2 -IncludeSelfReview `
  -ProposerModel qwen2.5-coder:3b-instruct-q4_K_M `
  -ReviewerModel qwen2.5-coder:7b-instruct-q4_K_M
```

For each fixture, the original proposal is the one-call baseline. After the
cross-model review, the collector submits the exact saved review prompt to
the proposer through the existing local generation API. Both reviewers get
the same task and proposal. The control gets its own execution ID, timing,
raw response, and verified history row. If the cross-model review was
skipped, the summary still records that the control was requested and the
task has no control response. `reviewVsSelfReview` compares the two reviewers
on strict exact-set scoring when both produce a response. Failed calls
remain unavailable for correctness scoring.

One v2 batch makes at most 18 sequential local calls. Before collection,
record model builds and runtime settings, freeze the call budget, and decide
whether a separate offline format diagnostic will be reported. The cross-model
review always runs first because its saved prompt is reused by the control.
This fixes the input while leaving possible order and warm-up effects. The
automated tests exercise the three-step collection with simulated responses.

## Local v2 batch on 8 November 2026

One batch ran all six fixtures with Qwen2.5 Coder 3B Q4_K_M as the proposer
and same-model reviewer and 7B Q4_K_M as the cross-model reviewer. The
collector verified 18 distinct Ollama executions against PostgreSQL history.
Ollama was version 0.40.15. The installed model digests and the frozen
fixture, scorer, and collector hashes matched before and after collection.
The run used the model defaults and made zero cloud calls.

| Metric | Initial proposal | Cross-model review | Same-model review |
| --- | ---: | ---: | ---: |
| Successful provider calls | 6/6 | 6/6 | 6/6 |
| Strict raw passes | 0/6 | 0/6 | 0/6 |
| Median generation latency | 231 ms | 9,555 ms | 5,986.5 ms |
| Responses scored after fence extraction | 6 | 6 | 5 |
| Passes after fence extraction | 2 | 1 | 1 |
| Actual defects identified after extraction | 3 | 4 | 4 |
| False alarms after extraction | 5 | 12 | 8 |

The median elapsed time for the two-step API request was 9,795 ms. This
excludes the separately collected same-model review. All 18 raw responses
failed the JSON format check. Seventeen contained one eligible fenced block.
The remaining same-model response, for the SQL correlation task, was prose
and had no extractable JSON block. The diagnostic verified the history and
raw scores before replay and preserved all 55 original batch files byte for
byte. It made zero model calls.

After extraction, cross-model review had five ties and one regression
against the initial proposal. Against same-model review, it had one win, one
loss, three ties, and one unavailable pair. The roles identify some different
defects and report many false alarms. One attempt per fixture and the fixed
review order limit what can be inferred about model choice.

```powershell
./scripts/replay-proposal-review-json-fences.ps1 -PilotDirectory ./target/proposal-review-pilot-4885d1b142f34b1cacbcf29021a0c7b8
```

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

## Structured-output comparison v1

Use the six existing v2 fixtures in a fresh paired batch. The two arms call
`/api/workflows/proposal-review` and `/api/workflows/proposal-review/defect-ids`
with the same model roles and original task prompts. Run one workflow per arm
per task, alternate which arm runs first by fixture order, and cap the batch
at 24 sequential local generations. Record the backend commit, fixture hash,
model digests, Ollama settings, and actual arm order. Keep this batch separate
from the published v1 and v2 results.

Score the original text from both arms. Count a malformed standard response
or `OUTPUT_FORMAT_ERROR` as a format failure. A conforming `defectIds` array
with incorrect membership, including unknown IDs, is an incorrect answer.
Report exact-set passes over all planned steps and over conforming responses.
Show skipped reviews, provider failures, and storage failures separately.
Verify each attempted step's request ID and raw response against history and
check uniqueness across both arms. The two review inputs can differ because
each reviewer receives its own arm's proposal. The comparison measures the
effect of structured output across the whole workflow.

Run the paired collector with PowerShell 7.5 or later against a quiet local
ModelHub instance with PostgreSQL. Use the installed model identifiers from
the runtime configuration. The comparison output goes into a new
`target/proposal-review-structured-v1-*` directory.

```powershell
./scripts/collect-proposal-review-pilot.ps1 -DatasetVersion 2 -StructuredComparisonV1 `
  -ProposerModel qwen2.5-coder:3b-instruct-q4_K_M `
  -ReviewerModel qwen2.5-coder:7b-instruct-q4_K_M
```

The summary lists the arms in dispatch order and reports outcome counts by
arm and role. The collector stops if an attempted step cannot be verified in
history. Its raw response remains available for investigation. The scorer
uses the comparison rules only with `-StructuredComparisonV1`. The default
collector and scorer retain the historical protocol.

In a two-call smoke test on a synthetic task, Qwen2.5 Coder 3B and 7B both
returned valid JSON. They reported IDs outside the task's candidate list.
This confirms the endpoint can enforce shape while the experiment still
needs to measure answer correctness separately. The smoke test is outside
the paired batch.

### First paired run on 8 November 2026

The batch used Qwen2.5 Coder 3B Q4_K_M as proposer and 7B Q4_K_M as reviewer
on Ollama 0.40.15, with model defaults and backend commit `01dd206`.
The collector verified 24 distinct executions across 12 workflows against
PostgreSQL. Each task used an identical request body in both arms. A replay
reproduced all 12 saved scores, and the model digests and frozen input hashes
matched before and after collection.

| Arm and step | Valid format | Exact-set passes | Conforming wrong answers | Format failures |
| --- | ---: | ---: | ---: | ---: |
| Standard proposal | 0/6 | 0/6 | 0 | 6 |
| Standard review | 0/6 | 0/6 | 0 | 6 |
| Structured proposal | 6/6 | 1/6 | 5 | 0 |
| Structured review | 6/6 | 1/6 | 5 | 0 |

All provider calls completed and no reviews were skipped. Median workflow
time was 15.27 s for the standard arm and 15.38 s for the structured arm.
Within the structured arm, review improved one exact-set verdict, regressed
one, and tied four. The standard arm yielded no format-conforming responses,
so correctness conditional on valid format cannot be compared across arms.
The run measures one model pair and one attempt per fixture. Raw responses
and prior benchmark results remain separate from this comparison. Collection
made zero cloud calls and applied no extraction or normalization.
