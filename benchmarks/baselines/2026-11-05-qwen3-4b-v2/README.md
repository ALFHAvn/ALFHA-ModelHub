# Three local repetitions of basics v2

Measured November 5, 2026, approximately 02:57–03:05 Asia/Saigon, using source
commit `cae65138834c10f925b73590ff403a15270f6eb1` and the same installed
`qwen3:4b` (4.0B, Q4_K_M), Ollama `0.40.14`.

| Run | Tasks passed | Provider failures | Average provider duration |
| --- | --- | --- | --- |
| 1 | 10/10 | 0 | 13394.5 ms |
| 2 | 10/10 | 0 | 16816.5 ms |
| 3 | 10/10 | 0 | 16512.8 ms |
| Combined attempts | 30/30 | 0 | 15574.6 ms |

All ten task IDs passed in each repetition. The previously ambiguous `null-field`
task returned `{"value":null,"label":"empty"}` in all three runs. Differences in
JSON whitespace and the unrestricted JSON-object task did not change scores,
as required by the existing structural scoring rules.

## What changed from v1

The [original v1 baseline](../2026-11-05-qwen3-4b/README.md) remains unchanged:
9/10 passes in one run, with the `null-field` answer using an empty label.
Version 2 changes only that task's prompt to explicitly request:

> label set to the literal JSON string "empty" (not an empty string)

The expected fields and `objective-v1` scorer did not change. The nine other
tasks are identical. The catalog retains v1 and its original hash; API requests
without `datasetVersion` continue to select v1.

This is a descriptive comparison of different prompt conditions: one v1 run
versus three v2 runs. It is not a controlled estimate of a causal prompt effect,
a change to the model, or evidence of general 100% accuracy. The small repeated
sample cannot establish statistical superiority. Latency varied, and hardware
load, caching, and model-loading time were not separately controlled or measured.

## Verification and artifacts

- `run-1.json`, `run-2.json`, `run-3.json`: complete reports retrieved from PostgreSQL.
- `summary.json`: summary recomputed from all 30 measured executions.
- `runtime-before.json`, `runtime-after.json`: actual runtime version and model
  digest measured before and after the repetitions; both matched.
- `verification.json`: source commit, report UUIDs, 30 history matches, and
  the cloud-disabled flag.

All 30 execution UUIDs, raw responses, and operational statuses matched saved
execution history. Each report contains dataset v2 with SHA-256
`25e3bdbecce5b8e41b3e8acf4f896de3226940ac7bb981def7801a622bafe2c1`.
The model digest was
`359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7`.

Runs were sequential on one server. The runner did not intentionally reload the
model, clear its caches, change sampling defaults, set a seed, or retry any task.
No cloud calls were made. The isolated Compose stack was stopped and removed
afterward, preserving `modelhub-repeated-baseline_postgres-data`.

The full Java build passed 32 tests: 30 unit/HTTP tests and 2 real PostgreSQL
integration tests. Tests verify the unchanged v1 identity, version selection,
unknown-version rejection before inference, v2 persistence, and existing behavior.
The container build additionally ran the 30 unit/HTTP tests successfully.

## Reproduce

With Docker and local Ollama running and a database password configured in the
ignored `.env`:

```powershell
./scripts/run-local-baselines.ps1 -DatasetVersion 2 -Repetitions 3
```

This performs 30 new local calls and exports to a new timestamped directory under
`target`; it does not overwrite these checked-in reports or alter `.env`.

Next useful comparison: use a specific configured free cloud model ID against
the same v2 dataset and scorer. Preserve that provider's individual failures and
request limits rather than substituting the variable `openrouter/free` router.
