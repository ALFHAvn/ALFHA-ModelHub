# Proposal and review evaluation plan

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
